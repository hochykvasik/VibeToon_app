package com.vibetoon.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import com.vibetoon.domain.render.Camera
import com.vibetoon.domain.render.SceneData
import com.vibetoon.nativebridge.RenderNative
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG_GL = "VT/GL"

/** Параметры кадра, которые UI меняет в любой момент: камера и рамка выделения. */
data class FrameParams(
    val camera: Camera = Camera(),
    val selectionQuad: FloatArray? = null,
    val background: Int = 0,
    /** Идёт жест камеры (пан/зум/поворот): нативный кэш эффектов можно рисовать с преобразованием вида. */
    val interactive: Boolean = false
)

/** Адаптер колбэков GLSurfaceView -> C++. Работает на GL-потоке. */
class VtRenderer(
    private val takeScene: () -> SceneData?,
    private val frameParams: () -> FrameParams,
    private val requestRender: () -> Unit,
    private val onReady: () -> Unit,
    private val onContextRecreated: () -> Unit = {},
    /** Пакет 29: бюджет текстур по памяти устройства (аргумент - GL_MAX_TEXTURE_SIZE). */
    private val textureBudget: (Int) -> Long = { com.vibetoon.domain.render.TextureBudget.DEFAULT },
    /** Пакет 29: события памяти текстур (вытеснение, отказ) - вызывается ИЗ GL-потока. */
    private val onTextureEvents: (IntArray) -> Unit = {}
) : GLSurfaceView.Renderer {
    private var handle = 0L
    @Volatile private var disposed = false
    fun deactivate() { disposed = true }
    private var lastScene: SceneData? = null
    private var sampleReq: Triple<Int, Int, (Int) -> Unit>? = null
    private val afterFrameChecks = ArrayList<Pair<Int, (IntArray?) -> Unit>>()
    fun checkTextureAfterFrame(id: Int, callback: (IntArray?) -> Unit) { afterFrameChecks.add(id to callback) }

    /** Пипетка: цвет пикселя после ближайшего кадра (в GL-потоке). */
    fun sampleColor(x: Int, y: Int, cb: (Int) -> Unit) { sampleReq = Triple(x, y, cb) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (disposed) return
        // Пакет 27: контекст пересоздан посреди экспорта - его FBO и поверхность кодека недействительны.
        deferredViewport = null
        exportJob?.contextLost()
        // Контекст мог быть потерян: старый рендерер освобождаем и создаём заново.
        if (handle != 0L) RenderNative.destroy(handle)
        handle = RenderNative.create()
        val maxTex = RenderNative.initGL(handle)
        Log.i(TAG_GL, "initGL maxTexture=$maxTex")
        // пакет 29: бюджет текстур по памяти устройства; список вытесняемых - тот же, что у прежнего рендерера
        val budget = textureBudget(maxTex)
        RenderNative.setTextureBudget(handle, budget)
        TextureMemoryHost.budgetBytes = budget
        TextureMemoryHost.maxTextureSize = maxTex
        Log.i(TAG_GL, "texture budget ${budget / (1024 * 1024)} MB")
        lastEvictable?.let { RenderNative.setEvictable(handle, it) }
        // Текстуры потеряны вместе с контекстом; сцену отправим заново (TODO: перезагрузка слоёв).
        lastScene?.let { applyScene(it) }
        onReady()
        onContextRecreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (disposed || handle == 0L) return
        if (exportJob != null) { deferredViewport = width to height; return }   // вид экспорта не меняем
        RenderNative.setViewport(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (disposed || handle == 0L) return
        if (exportJob != null) {
            // Идёт экспорт: рендерер в виде экспорта, кадр редактора не рисуем (окно закрыто диалогом экспорта).
            // Кадр сюда попадает только при пересоздании/смене размера окна - заливаем фоном редактора, без мусора.
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_FRAMEBUFFER, 0)
            android.opengl.GLES30.glDisable(android.opengl.GLES30.GL_SCISSOR_TEST)
            android.opengl.GLES30.glClearColor(0.2f, 0.2f, 0.22f, 1f)
            android.opengl.GLES30.glClear(android.opengl.GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        takeScene()?.let { lastScene = it; applyScene(it) }
        val p = frameParams()
        RenderNative.setCamera(handle, p.camera.zoom, p.camera.panX, p.camera.panY, p.camera.rotDeg, p.camera.flipX, p.camera.flipY)
        RenderNative.setInteractive(handle, p.interactive)
        RenderNative.setSelection(handle, p.selectionQuad)
        RenderNative.setBackground(handle, lastScene?.backgroundOverride ?: p.background)
        val t0 = if (RenderMetrics.enabled) System.nanoTime() else 0L
        val more = RenderNative.drawFrame(handle)
        if (RenderMetrics.enabled) {
            RenderMetrics.drawMs = (System.nanoTime() - t0) / 1e6f
            RenderNative.frameStats(handle)?.let { st ->
                if (st.size >= 4) { RenderMetrics.drawn = st[0]; RenderMetrics.culled = st[1]; RenderMetrics.scissoredFx = st[2]; RenderMetrics.fxPasses = st[3] }
                if (st.size >= 5) RenderMetrics.fxCached = st[4]
                if (st.size >= 7) RenderMetrics.groupCached = st[6]
            }
        }
        pollTextureEvents()
        sampleReq?.let { (x, y, cb) -> sampleReq = null; cb(RenderNative.readPixel(handle, x, y)) }
        val checks = afterFrameChecks.toList(); afterFrameChecks.clear()
        checks.forEach { (id, callback) -> callback(layerSize(id)) }
        if (more) requestRender() // в очереди остались загрузки
    }

    // ---- пакет 29: память текстур ----
    private var lastEvictable: IntArray? = null

    /** Какие текстуры можно вытеснять (только GL-поток); переживает пересоздание контекста. */
    fun setEvictable(ids: IntArray) {
        lastEvictable = ids
        if (handle != 0L) RenderNative.setEvictable(handle, ids)
    }

    /** Забрать события памяти текстур после кадра и отдать ViewModel (только GL-поток). */
    private fun pollTextureEvents() {
        if (handle == 0L) return
        val ev = RenderNative.takeTextureEvents(handle) ?: return
        RenderNative.textureMemory(handle)?.let { if (it.size >= 2) { TextureMemoryHost.usedBytes = it[0]; TextureMemoryHost.budgetBytes = it[1] } }
        runCatching { onTextureEvents(ev) }
    }

    /** Снимок кадра: при [scene] != null временно подставляет её, потом возвращает прежнюю. Только из GL-потока. */
    fun capture(scene: SceneData?, width: Int, height: Int, transparent: Boolean, background: Int, flags: Int = 0): ByteArray? {
        if (handle == 0L || exportJob != null) return null   // посреди экспорта MP4 сцена и вид заняты им
        if (scene != null) { applyScene(scene); scene.beforeDraw?.invoke() }
        RenderNative.setBackground(handle, scene?.backgroundOverride ?: background)
        val r = RenderNative.captureFrame(handle, width, height, transparent, flags)
        pollTextureEvents()
        if (scene != null) lastScene?.let { applyScene(it) }
        return r
    }

    /** Пакет 29: снимок сцены прямо в текстуру [id] (см. RenderNative.captureToTexture). Только из GL-потока. */
    fun captureToTexture(scene: SceneData, id: Int, width: Int, height: Int, transparent: Boolean, background: Int, flags: Int): Int {
        if (handle == 0L || exportJob != null) return -1
        applyScene(scene)
        RenderNative.setBackground(handle, scene.backgroundOverride ?: background)
        val r = RenderNative.captureToTexture(handle, id, width, height, transparent, flags)
        pollTextureEvents()
        lastScene?.let { applyScene(it) }
        return r
    }

    /** Только из GL-потока. */
    fun hitTest(x: Float, y: Float): Int = if (handle == 0L) -1 else RenderNative.hitTest(handle, x, y)

    fun layerThumb(layerId: Int, size: Int): ByteArray? = if (handle == 0L) null else RenderNative.layerThumb(handle, layerId, size)

    fun layerPixels(layerId: Int): ByteArray? = if (handle == 0L) null else RenderNative.layerPixels(handle, layerId)

    /** Только из GL-потока. */
    fun layerAlpha(layerId: Int): ByteArray? = if (handle == 0L) null else RenderNative.layerAlpha(handle, layerId)

    fun layerSize(layerId: Int): IntArray? = if (handle == 0L) null else RenderNative.layerSize(handle, layerId)

    fun clearTextures() { if (handle != 0L) RenderNative.clearTextures(handle) }

    // ---- рисование (только из GL-потока) ----
    fun paintBegin(layerId: Int, mode: Int, opacity: Float, alphaLock: Boolean, r: Float, g: Float, b: Float, shape: Int, hardness: Float,
                   grainKind: Int, grainMode: Int, grainScale: Float, grainContrast: Float, roundness: Float, curve: Int, grain: ByteArray?): Boolean =
        handle != 0L && RenderNative.paintBegin(handle, layerId, mode, opacity, alphaLock, r, g, b, shape, hardness, grainKind, grainMode, grainScale, grainContrast, roundness, curve, grain)
    fun brushPreview(mode: Int, r: Float, g: Float, b: Float, shape: Int, hardness: Float, grainKind: Int, grainMode: Int,
                     grainScale: Float, grainContrast: Float, roundness: Float, curve: Int, grain: ByteArray?, dabs: FloatArray, count: Int, w: Int, h: Int): ByteArray? =
        if (handle == 0L) null else RenderNative.brushPreview(handle, mode, r, g, b, shape, hardness, grainKind, grainMode, grainScale, grainContrast, roundness, curve, grain, dabs, count, w, h)
    fun paintDabs(dabs: FloatArray, count: Int) { if (handle != 0L) RenderNative.paintDabs(handle, dabs, count) }
    fun paintGradient(layerId: Int, geometry: FloatArray): Boolean = handle != 0L && RenderNative.paintGradient(handle, layerId, geometry)
    fun paintEnd(commit: Boolean): ByteArray? = if (handle == 0L) null else RenderNative.paintEnd(handle, commit)
    fun paintEndAsync(layerId: Int): Boolean = handle != 0L && RenderNative.paintEndAsync(handle, layerId)
    fun paintPoll(): ByteArray? = if (handle == 0L) null else RenderNative.paintPoll(handle)
    fun paintRestore(layerId: Int, x: Int, y: Int, w: Int, h: Int, data: ByteArray): Boolean =
        handle != 0L && RenderNative.paintRestore(handle, layerId, x, y, w, h, data)

    fun replaceTextureChecked(layerId: Int, expectedW: Int, expectedH: Int, width: Int, height: Int, data: ByteArray): Int =
        if (handle == 0L) -2 else RenderNative.replaceTextureChecked(handle, layerId, expectedW, expectedH, width, height, data)

    private fun applyScene(s: SceneData) {
        RenderNative.setCanvas(handle, s.canvasWidth, s.canvasHeight)
        RenderNative.setScene(handle, s.ops, s.params)
        RenderNative.setWarps(handle, s.warpIds, s.warpData)
        RenderNative.setMeshes(handle, s.meshIds, s.meshCounts, s.meshVerts, s.meshTris)
        RenderNative.setPerspectives(handle, s.perspIds, s.perspData)
        RenderNative.setTurbulence(handle, s.turbIds, s.turbData)
        RenderNative.setEffects(handle, s.fxIds, s.fxCounts, s.fxData)
        val a = s.aliases   // неизменяемый набор: формы и сцена применяются одним кадром
        RenderNative.setTextureAliases(handle, a.ids, a.primary, a.fallback, a.rects)
    }

    /** Только из GL-потока. */
    fun dropTextures(ids: IntArray) { if (handle != 0L && ids.isNotEmpty()) RenderNative.dropTextures(handle, ids) }

    // ---- пакет 27: экспорт MP4 покадровыми задачами GL-потока ----
    private var exportJob: Mp4ExportJob? = null
    /** Размер окна, пришедший посреди экспорта: применяется после него (вид экспорта не трогаем). */
    private var deferredViewport: Pair<Int, Int>? = null
    val exportRunning: Boolean get() = exportJob != null

    /** Начинает экспорт MP4 (только из GL-потока). Дальше VtGLSurfaceView вызывает [exportStep] по задаче на кадр. */
    fun startExport(
        spec: ExportSpec, background: Int, sceneAt: (Int) -> SceneData, onProgress: (Int) -> Unit,
        cancel: java.util.concurrent.atomic.AtomicBoolean, interrupted: () -> Boolean, onDone: (Result<Unit>) -> Unit
    ) {
        if (handle == 0L || exportJob != null) { onDone(Result.failure(IllegalStateException("Рендерер не готов"))); return }
        exportJob = Mp4ExportJob(spec, sceneAt, onProgress, cancel, { r -> finishExport(); onDone(r) }) { job, yuv ->
            VideoExporter.sink(spec, exportHooks(job, background, interrupted), cancel, interrupted, yuv)
        }
    }

    /** Одна задача экспорта. true - сразу ставить следующую. */
    fun exportStep(paused: Boolean): Boolean = exportJob?.step(paused) ?: false

    private fun finishExport() {
        exportJob = null
        deferredViewport?.let { (w, h) -> if (handle != 0L) RenderNative.setViewport(handle, w, h) }
        deferredViewport = null
    }

    private fun exportHooks(job: Mp4ExportJob, background: Int, interrupted: () -> Boolean) = object : ExportHooks {
        override fun begin(width: Int, height: Int) {
            RenderNative.beginExport(handle, width, height)
            RenderNative.setBackground(handle, background)
            RenderNative.setSelection(handle, null)
        }

        override fun readFrame(index: Int, width: Int, height: Int): ByteArray? {
            if (interrupted()) return null
            val s = job.prefetch.get(index)
            applyScene(s)
            s.beforeDraw?.invoke()   // пакет 29: картинки именно этого кадра - прямо перед его отрисовкой
            RenderNative.setBackground(handle, s.backgroundOverride ?: background)
            RenderNative.setSelection(handle, null)
            return RenderNative.captureFrame(handle, width, height, false, 0).also { pollTextureEvents() }
        }

        override fun drawFrame(index: Int): Boolean {
            val s = job.prefetch.get(index)
            applyScene(s)
            s.beforeDraw?.invoke()   // пакет 29: картинки именно этого кадра - прямо перед его отрисовкой
            RenderNative.setBackground(handle, s.backgroundOverride ?: background)   // пакет 24: переход через цвет
            // пока в очереди есть загрузки текстур, кадр рисуется повторно (лимит от зависания); пауза/отмена прерывают
            // кадр - он не уходит в кодек и будет нарисован заново
            var guard = 0
            while (RenderNative.drawFrame(handle) && guard++ < 500) {
                if (job.cancel.get() || interrupted()) { pollTextureEvents(); return false }
            }
            pollTextureEvents()
            return true
        }

        override fun end() {
            RenderNative.endExport(handle)
            lastScene?.let { applyScene(it) }
        }
    }

    /** Вызывать из GL-потока (через queueEvent). */
    fun release() {
        if (handle != 0L) RenderNative.destroy(handle)
        handle = 0L
    }
}

class VtGLSurfaceView(context: Context) : GLSurfaceView(context) {
    private val pendingScene = AtomicReference<SceneData?>(null)
    private val params = AtomicReference(FrameParams())
    private val rendererReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    var onContextRecreated: (() -> Unit)? = null
    /** Пакет 29: события памяти текстур (пары [вид, id], см. TextureEvents); вызывается ИЗ GL-потока. */
    var onTextureEvents: ((IntArray) -> Unit)? = null
    private val vtRenderer = VtRenderer({ pendingScene.getAndSet(null) }, { params.get() }, { requestRender() }, { rendererReady.complete(Unit) },
        { onContextRecreated?.invoke() }, ::computeTextureBudget, { ev -> onTextureEvents?.invoke(ev) })

    /** Пакет 29: бюджет текстур по памяти устройства (ActivityManager) и возможностям GPU. */
    private fun computeTextureBudget(maxTex: Int): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        com.vibetoon.domain.render.TextureBudget.compute(mi.totalMem, mi.availMem, am.isLowRamDevice, maxTex)
    }.getOrDefault(com.vibetoon.domain.render.TextureBudget.DEFAULT)

    /** Пакет 29: список текстур, которые рендерер может вытеснить (применяется в GL-потоке). */
    fun setEvictable(ids: IntArray) {
        queueEvent { vtRenderer.setEvictable(ids) }
    }

    /**
     * Поверхность уходит на паузу. GLSurfaceView.onPause ждёт GL-поток, а тот сначала выполняет ВСЕ события очереди
     * (queueEvent) и только потом замечает паузу: накопленные снимки символов держали главный поток секундами - ANR при
     * сворачивании/возврате. Пока флаг стоит, тяжёлые события из очереди сразу отдают null (вызывающий повторит позже).
     */
    @Volatile var surfacePaused = false
        private set

    // ---- пакет 27: экспорт MP4 по задаче GL-потока на кадр ----
    private val exportLock = Any()
    /** Цепочка задач экспорта остановлена на паузе и ждёт возврата приложения (под [exportLock]). */
    private var exportParked = false
    /** Идёт экспорт: кадры редактора не запрашиваются (рендерер в виде экспорта); в конце кадр редактора запрашивается сам. */
    @Volatile private var exportActive = false

    override fun onPause() {
        // Флаг до super.onPause: очередная задача экспорта увидит паузу и «запаркуется», очередь GL опустеет и
        // GLSurfaceView подтвердит паузу через один кадр, а не после всего экспорта (раньше экспорт приходилось отменять).
        synchronized(exportLock) { surfacePaused = true }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val kick: Boolean
        synchronized(exportLock) {
            surfacePaused = false
            kick = exportParked
            exportParked = false
        }
        if (kick) queueEvent { exportTask() }   // экспорт продолжается с того же кадра
    }

    override fun requestRender() {
        if (exportActive) return
        super.requestRender()
    }

    /** View инициализирована не сразу: загрузка проекта ждёт реального создания GL-контекста. */
    suspend fun awaitRendererReady() = rendererReady.await()

    /** Барьер на GL-потоке: старые текстуры удалены до постановки новых в глобальную очередь. */
    suspend fun clearTexturesAndWait() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        queueEvent {
            try { vtRenderer.clearTextures(); done.complete(Unit) }
            catch (t: Throwable) { done.completeExceptionally(t) }
        }
        requestRender()
        done.await()
    }

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(vtRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Из любого потока: новая сцена применится на ближайшем кадре. */
    fun setScene(scene: SceneData) {
        pendingScene.set(scene)
        requestRender()
    }

    fun setCamera(camera: Camera) {
        params.updateAndGet { it.copy(camera = camera) }
        requestRender()
    }

    /** Начало/конец жеста камеры. После жеста кадр перерисовывается точно (без растянутого кэша эффектов). */
    fun setInteractive(on: Boolean) {
        val prev = params.getAndUpdate { it.copy(interactive = on) }
        if (prev.interactive != on) requestRender()
    }

    /** Цвет фона холста проекта (ARGB). */
    fun setBackground(argb: Int) {
        params.updateAndGet { it.copy(background = argb) }
        requestRender()
    }

    /** Рамка выделения слоя или группы (8 float) либо null. */
    fun setSelection(quad: FloatArray?) {
        params.updateAndGet { it.copy(selectionQuad = quad) }
        requestRender()
    }

    /** Пипетка: результат (ARGB) придёт в callback ИЗ GL-потока. */
    fun sampleColor(x: Int, y: Int, callback: (Int) -> Unit) {
        queueEvent { vtRenderer.sampleColor(x, y, callback) }
        requestRender()
    }

    /** Карта прозрачности слоя (см. RenderNative.layerAlpha); callback вызывается ИЗ GL-потока. */
    fun layerAlpha(layerId: Int, callback: (ByteArray?) -> Unit) {
        queueEvent { callback(vtRenderer.layerAlpha(layerId)) }
    }

    /** Миниатюра слоя (см. RenderNative.layerThumb); callback вызывается ИЗ GL-потока. */
    fun layerThumb(layerId: Int, size: Int, callback: (ByteArray?) -> Unit) {
        queueEvent { callback(vtRenderer.layerThumb(layerId, size)) }
    }

    /** Пиксели слоя (см. RenderNative.layerPixels); callback вызывается ИЗ GL-потока. */
    fun layerPixels(layerId: Int, callback: (ByteArray?) -> Unit) {
        queueEvent { callback(vtRenderer.layerPixels(layerId)) }
    }

    /** Смена проекта: текстуры прежних слоёв удаляются на GL-потоке. */
    fun clearTextures() {
        queueEvent { vtRenderer.clearTextures() }
    }

    /** Размер текстуры слоя [w, h]; callback ИЗ GL-потока (null - слой ещё не загружен). */
    fun layerSize(layerId: Int, callback: (IntArray?) -> Unit) {
        queueEvent { callback(vtRenderer.layerSize(layerId)) }
    }

    /** После кадра очередь загрузки уже обработана; не путать постановку bitmap с успешным созданием GL-текстуры. */
    fun layerSizeAfterFrame(layerId: Int, callback: (IntArray?) -> Unit) {
        queueEvent { vtRenderer.checkTextureAfterFrame(layerId, callback); requestRender() }
        requestRender()
    }

    /** Результат замены приходит из GL-потока; вызывающий сериализует редактирование слоя. */
    fun replaceTextureChecked(layerId: Int, expectedW: Int, expectedH: Int, width: Int, height: Int, data: ByteArray, callback: (com.vibetoon.domain.raster.TextureReplaceResult) -> Unit): com.vibetoon.domain.raster.QueuedGlRequest {
        val request = com.vibetoon.domain.raster.QueuedGlRequest()
        queueEvent {
            if (request.tryStart()) {
                val code = vtRenderer.replaceTextureChecked(layerId, expectedW, expectedH, width, height, data)
                request.finish()
                callback(com.vibetoon.domain.raster.TextureReplaceResult.fromCode(code)); requestRender()
            }
        }
        requestRender()
        return request
    }

    /** Начало штриха; [callback] получает успех ИЗ GL-потока. */
    fun paintBegin(
        layerId: Int, mode: Int, opacity: Float, alphaLock: Boolean, r: Float, g: Float, b: Float, shape: Int, hardness: Float,
        grainKind: Int, grainMode: Int, grainScale: Float, grainContrast: Float, roundness: Float, curve: Int, grain: ByteArray?, callback: (Boolean) -> Unit
    ) {
        queueEvent { callback(vtRenderer.paintBegin(layerId, mode, opacity, alphaLock, r, g, b, shape, hardness, grainKind, grainMode, grainScale, grainContrast, roundness, curve, grain)); requestRender() }
    }

    /** Превью кисти w x h (см. RenderNative.brushPreview); callback ИЗ GL-потока. */
    fun brushPreview(
        mode: Int, r: Float, g: Float, b: Float, shape: Int, hardness: Float, grainKind: Int, grainMode: Int,
        grainScale: Float, grainContrast: Float, roundness: Float, curve: Int, grain: ByteArray?, dabs: FloatArray, count: Int, w: Int, h: Int, callback: (ByteArray?) -> Unit
    ) {
        queueEvent { callback(vtRenderer.brushPreview(mode, r, g, b, shape, hardness, grainKind, grainMode, grainScale, grainContrast, roundness, curve, grain, dabs, count, w, h)) }
    }

    /** Пакет штампов (x, y, радиус, непрозрачность); превью сразу запекается в слой. */
    fun paintDabs(dabs: FloatArray, count: Int) {
        queueEvent { vtRenderer.paintDabs(dabs, count) }
        requestRender()
    }

    /** Конец штриха; [callback] получает пиксели "до" и "после" (см. RenderNative.paintEnd) ИЗ GL-потока. */
    fun paintEnd(commit: Boolean, callback: (ByteArray?) -> Unit) {
        queueEvent { callback(vtRenderer.paintEnd(commit)); requestRender() }
    }

    /**
     * Конец штриха без остановки рендера: чтение "до"/"после" идёт через PBO, результат приходит в [callback] ИЗ GL-потока,
     * когда GPU закончит (обычно через 1-3 кадра). Если асинхронное чтение недоступно, работает прежний синхронный путь.
     */
    fun paintEndSoft(layerId: Int, callback: (ByteArray?) -> Unit) {
        queueEvent {
            if (!vtRenderer.paintEndAsync(layerId)) { callback(vtRenderer.paintEnd(true)); requestRender(); return@queueEvent }
            pollPaint(callback)
        }
    }

    /** One GL queue operation: failed gradients are cancelled, never committed without history. */
    fun paintGradientEnd(layerId: Int, geometry: FloatArray, callback: (ByteArray?) -> Unit) {
        queueEvent {
            if (!vtRenderer.paintGradient(layerId, geometry)) {
                vtRenderer.paintEnd(false); callback(null); requestRender(); return@queueEvent
            }
            if (!vtRenderer.paintEndAsync(layerId)) { callback(vtRenderer.paintEnd(true)); requestRender(); return@queueEvent }
            pollPaint(callback)
            requestRender()
        }
    }

    private fun pollPaint(callback: (ByteArray?) -> Unit) {
        val r = vtRenderer.paintPoll()
        when {
            r == null -> callback(null)
            r.isEmpty() -> postDelayed({ queueEvent { pollPaint(callback) } }, 6)
            else -> { callback(r); requestRender() }
        }
    }

    /** Undo/Redo: перезапись прямоугольника слоя; [callback] из GL-потока. */
    fun paintRestore(layerId: Int, x: Int, y: Int, w: Int, h: Int, data: ByteArray, callback: (Boolean) -> Unit = {}) {
        queueEvent { callback(vtRenderer.paintRestore(layerId, x, y, w, h, data)); requestRender() }
    }

    /** Запрос нельзя объявлять отменённым после начала выполнения на GL. */
    fun paintRestoreChecked(layerId: Int, x: Int, y: Int, w: Int, h: Int, data: ByteArray, callback: (Boolean) -> Unit): com.vibetoon.domain.raster.QueuedGlRequest {
        val request = com.vibetoon.domain.raster.QueuedGlRequest()
        queueEvent {
            if (request.tryStart()) {
                val ok = try { vtRenderer.paintRestore(layerId, x, y, w, h, data) } catch (_: Throwable) { false }
                request.finish(); callback(ok); requestRender()
            }
        }
        requestRender()
        return request
    }

    /** Снимок кадра холста w x h (RGBA premultiplied, строка 0 - низ); [callback] из GL-потока. */
    fun capture(scene: SceneData?, width: Int, height: Int, transparent: Boolean, background: Int, flags: Int = 0, callback: (ByteArray?) -> Unit) {
        if (surfacePaused) { callback(null); return }
        queueEvent {
            if (surfacePaused) { callback(null); return@queueEvent }
            callback(vtRenderer.capture(scene, width, height, transparent, background, flags)); requestRender()
        }
    }

    /** Пакет 29: снимок в текстуру [id] в GL-потоке; результат (1/0/-1/-2) придёт в callback ИЗ GL-потока. */
    fun captureToTexture(scene: SceneData, id: Int, width: Int, height: Int, transparent: Boolean, background: Int, flags: Int, callback: (Int) -> Unit) {
        if (surfacePaused) { callback(-1); return }
        queueEvent {
            if (surfacePaused) { callback(-1); return@queueEvent }
            callback(vtRenderer.captureToTexture(scene, id, width, height, transparent, background, flags)); requestRender()
        }
    }

    /** Hit-test выполняется в GL-потоке; результат придёт в callback ИЗ GL-потока. */
    fun hitTest(x: Float, y: Float, callback: (Int) -> Unit) {
        queueEvent { callback(vtRenderer.hitTest(x, y)) }
    }

    /**
     * Запускает экспорт MP4: по задаче GL-потока на кадр (пакет 27). Между кадрами поток свободен: пауза приложения
     * останавливает экспорт на текущем кадре, возврат продолжает его. [onProgress] и [onDone] вызываются из GL-потока.
     */
    fun exportVideo(
        spec: ExportSpec, background: Int, sceneAt: (Int) -> SceneData,
        onProgress: (Int) -> Unit, cancel: java.util.concurrent.atomic.AtomicBoolean, onDone: (Result<Unit>) -> Unit
    ) {
        exportActive = true
        queueEvent {
            vtRenderer.startExport(spec, background, sceneAt, onProgress, cancel, { surfacePaused }) { r ->
                exportActive = false
                onDone(r)
                super.requestRender()   // кадр редактора после экспорта
            }
            exportTask()
        }
    }

    /** Одна задача экспорта (GL-поток); ставит следующую или паркует цепочку на паузе. */
    private fun exportTask() {
        val paused = synchronized(exportLock) { surfacePaused }
        if (vtRenderer.exportStep(paused)) { queueEvent { exportTask() }; return }
        if (!vtRenderer.exportRunning) return
        // Задача ничего не сделала (пауза): ждём onResume; если он уже прошёл - продолжаем сразу.
        val resumeNow = synchronized(exportLock) {
            if (surfacePaused) { exportParked = true; false } else true
        }
        if (resumeNow) queueEvent { exportTask() }
    }

    /** Отмена экспорта, пока цепочка запаркована (приложение свёрнуто): одна задача освободит кодек. */
    fun kickExport() {
        val kick = synchronized(exportLock) { exportParked.also { exportParked = false } }
        if (kick) queueEvent { exportTask() }
    }

    /** Удаляет текстуры форм символов в GL-потоке. */
    fun dropTextures(ids: IntArray) {
        if (ids.isEmpty()) return
        queueEvent { vtRenderer.dropTextures(ids) }
    }

    /** Освобождает нативные GL-объекты в GL-потоке. */
    fun releaseNative() {
        vtRenderer.deactivate()
        queueEvent { vtRenderer.release() }
    }
}

/** Доступ к текущему GL-виду из ViewModel (ставится при создании вида, снимается при release). */
object RenderHost {
    @Volatile
    var view: VtGLSurfaceView? = null
}
