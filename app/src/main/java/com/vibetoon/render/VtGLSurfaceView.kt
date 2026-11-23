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
    private val onContextRecreated: () -> Unit = {}
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
        // Контекст мог быть потерян: старый рендерер освобождаем и создаём заново.
        if (handle != 0L) RenderNative.destroy(handle)
        handle = RenderNative.create()
        val maxTex = RenderNative.initGL(handle)
        Log.i(TAG_GL, "initGL maxTexture=$maxTex")
        // Текстуры потеряны вместе с контекстом; сцену отправим заново (TODO: перезагрузка слоёв).
        lastScene?.let { applyScene(it) }
        onReady()
        onContextRecreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (disposed || handle == 0L) return
        RenderNative.setViewport(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (disposed || handle == 0L) return
        takeScene()?.let { lastScene = it; applyScene(it) }
        val p = frameParams()
        RenderNative.setCamera(handle, p.camera.zoom, p.camera.panX, p.camera.panY, p.camera.rotDeg, p.camera.flipX, p.camera.flipY)
        RenderNative.setInteractive(handle, p.interactive)
        RenderNative.setSelection(handle, p.selectionQuad)
        RenderNative.setBackground(handle, p.background)
        val t0 = if (RenderMetrics.enabled) System.nanoTime() else 0L
        val more = RenderNative.drawFrame(handle)
        if (RenderMetrics.enabled) {
            RenderMetrics.drawMs = (System.nanoTime() - t0) / 1e6f
            RenderNative.frameStats(handle)?.let { st ->
                if (st.size >= 4) { RenderMetrics.drawn = st[0]; RenderMetrics.culled = st[1]; RenderMetrics.scissoredFx = st[2]; RenderMetrics.fxPasses = st[3] }
                if (st.size >= 5) RenderMetrics.fxCached = st[4]
            }
        }
        sampleReq?.let { (x, y, cb) -> sampleReq = null; cb(RenderNative.readPixel(handle, x, y)) }
        val checks = afterFrameChecks.toList(); afterFrameChecks.clear()
        checks.forEach { (id, callback) -> callback(layerSize(id)) }
        if (more) requestRender() // в очереди остались загрузки
    }

    /** Снимок кадра: при [scene] != null временно подставляет её, потом возвращает прежнюю. Только из GL-потока. */
    fun capture(scene: SceneData?, width: Int, height: Int, transparent: Boolean, background: Int, flags: Int = 0): ByteArray? {
        if (handle == 0L) return null
        if (scene != null) applyScene(scene)
        RenderNative.setBackground(handle, background)
        val r = RenderNative.captureFrame(handle, width, height, transparent, flags)
        if (scene != null) lastScene?.let { applyScene(it) }
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
    }

    /** Экспорт в MP4. Только из GL-потока. [sceneAt] строит сцену кадра готового видео. */
    fun exportVideo(
        spec: ExportSpec, background: Int, sceneAt: (Int) -> SceneData,
        onProgress: (Int) -> Unit, cancel: java.util.concurrent.atomic.AtomicBoolean
    ): Result<Unit> {
        if (handle == 0L) return Result.failure(IllegalStateException("Рендерер не готов"))
        val hooks = object : ExportHooks {
            override fun begin(width: Int, height: Int) {
                RenderNative.beginExport(handle, width, height)
                RenderNative.setBackground(handle, background)
                RenderNative.setSelection(handle, null)
            }

            override fun readFrame(index: Int, width: Int, height: Int): ByteArray? {
                applyScene(sceneAt(index))
                RenderNative.setBackground(handle, background)
                RenderNative.setSelection(handle, null)
                return RenderNative.captureFrame(handle, width, height, false, 0)
            }

            override fun drawFrame(index: Int) {
                applyScene(sceneAt(index))
                // пока в очереди есть загрузки текстур, кадр рисуется повторно (лимит от зависания)
                var guard = 0
                while (RenderNative.drawFrame(handle) && guard++ < 500 && !cancel.get()) { /* ждём все текстуры */ }
            }

            override fun end() {
                RenderNative.endExport(handle)
                lastScene?.let { applyScene(it) }
            }
        }
        return VideoExporter.encode(spec, hooks, onProgress, cancel)
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
    private val vtRenderer = VtRenderer({ pendingScene.getAndSet(null) }, { params.get() }, { requestRender() }, { rendererReady.complete(Unit) }, { onContextRecreated?.invoke() })

    /**
     * Поверхность уходит на паузу. GLSurfaceView.onPause ждёт GL-поток, а тот сначала выполняет ВСЕ события очереди
     * (queueEvent) и только потом замечает паузу: накопленные снимки символов держали главный поток секундами - ANR при
     * сворачивании/возврате. Пока флаг стоит, тяжёлые события из очереди сразу отдают null (вызывающий повторит позже).
     */
    @Volatile var surfacePaused = false
        private set

    override fun onPause() {
        surfacePaused = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        surfacePaused = false
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

    /** Hit-test выполняется в GL-потоке; результат придёт в callback ИЗ GL-потока. */
    fun hitTest(x: Float, y: Float, callback: (Int) -> Unit) {
        queueEvent { callback(vtRenderer.hitTest(x, y)) }
    }

    /** Запускает экспорт в GL-потоке; [onDone] вызывается оттуда же. */
    fun exportVideo(
        spec: ExportSpec, background: Int, sceneAt: (Int) -> SceneData,
        onProgress: (Int) -> Unit, cancel: java.util.concurrent.atomic.AtomicBoolean, onDone: (Result<Unit>) -> Unit
    ) {
        queueEvent {
            if (surfacePaused) cancel.set(true)   // экспорт стартовал уже после сворачивания - не держим поток
            val r = vtRenderer.exportVideo(spec, background, sceneAt, onProgress, cancel)
            onDone(r)
            requestRender()
        }
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
