package com.vibetoon.render

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.SurfaceHolder
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
    val background: Int = 0
)

/** Адаптер колбэков GLSurfaceView -> C++. Работает на GL-потоке. */
class VtRenderer(
    private val takeScene: () -> SceneData?,
    private val frameParams: () -> FrameParams,
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer {
    @Volatile private var handle = 0L
    @Volatile var maxCaptureSide = 4096
        private set
    private var contexts = 0
    /** Вызывается из GL-потока после создания контекста: [recreated] - контекст этого вида создан не впервые. */
    @Volatile var onContextReady: ((recreated: Boolean) -> Unit)? = null
    private var lastScene: SceneData? = null

    /** GL можно трогать: рендерер создан и у потока есть текущий EGL-контекст (после потери контекста вызовы GL - пустые). */
    private fun glAlive(): Boolean = handle != 0L && EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT
    private var sampleReq: Triple<Int, Int, (Int) -> Unit>? = null

    /** Пипетка: цвет пикселя после ближайшего кадра (в GL-потоке). */
    fun sampleColor(x: Int, y: Int, cb: (Int) -> Unit) { sampleReq = Triple(x, y, cb) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // onSurfaceCreated приходит только с НОВЫМ контекстом: старый уже уничтожен вместе со всеми текстурами и FBO.
        // Его имена объектов в новом контексте ничего не значат (или значат чужое) - освобождаем без вызовов GL.
        val old = handle
        handle = 0L
        if (old != 0L) RenderNative.abandon(old)
        val h = RenderNative.create()
        val maxTex = RenderNative.initGL(h)
        maxCaptureSide = RenderNative.maxCaptureSide(h)
        handle = h
        contexts++
        Log.i(TAG_GL, "initGL maxTexture=$maxTex capture<=$maxCaptureSide context #$contexts")
        lastScene?.let { applyScene(it) }
        // ViewModel заново загружает текстуры слоёв, символов, кадров и текстов (раньше здесь был TODO: после
        // возврата из фона половина холста оставалась чёрной, рот-символ пропадал)
        onContextReady?.invoke(contexts > 1)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (handle != 0L) RenderNative.setViewport(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (handle == 0L) return
        takeScene()?.let { lastScene = it; applyScene(it) }
        val p = frameParams()
        RenderNative.setCamera(handle, p.camera.zoom, p.camera.panX, p.camera.panY, p.camera.rotation, p.camera.flipX, p.camera.flipY)
        RenderNative.setSelection(handle, p.selectionQuad)
        RenderNative.setBackground(handle, p.background)
        val more = RenderNative.drawFrame(handle)
        sampleReq?.let { (x, y, cb) -> sampleReq = null; cb(RenderNative.readPixel(handle, x, y)) }
        if (more) requestRender() // в очереди остались загрузки
    }

    /** Снимок кадра: при [scene] != null временно подставляет её, потом возвращает прежнюю. Только из GL-потока. */
    fun capture(scene: SceneData?, width: Int, height: Int, transparent: Boolean, background: Int): ByteArray? {
        if (!glAlive()) return null
        if (scene != null) applyScene(scene)
        RenderNative.setBackground(handle, background)
        val r = RenderNative.captureFrame(handle, width, height, transparent)
        if (scene != null) lastScene?.let { applyScene(it) }
        return r
    }

    /** Только из GL-потока. */
    fun hitTest(x: Float, y: Float): Int = if (!glAlive()) -1 else RenderNative.hitTest(handle, x, y)

    fun layerThumb(layerId: Int, size: Int): ByteArray? = if (!glAlive()) null else RenderNative.layerThumb(handle, layerId, size)

    fun layerPixels(layerId: Int): ByteArray? = if (!glAlive()) null else RenderNative.layerPixels(handle, layerId)

    /** Только из GL-потока. */
    fun textureStats(): IntArray? = if (!glAlive()) null else RenderNative.textureStats(handle)

    /** Только из GL-потока. */
    fun layerAlpha(layerId: Int): ByteArray? = if (!glAlive()) null else RenderNative.layerAlpha(handle, layerId)

    fun layerSize(layerId: Int): IntArray? = if (!glAlive()) null else RenderNative.layerSize(handle, layerId)

    fun clearTextures() { if (glAlive()) RenderNative.clearTextures(handle) }

    // ---- рисование (только из GL-потока) ----
    fun paintBegin(layerId: Int, mode: Int, opacity: Float, alphaLock: Boolean, r: Float, g: Float, b: Float, shape: Int, hardness: Float,
                   grainKind: Int, grainMode: Int, grainScale: Float, grainContrast: Float, roundness: Float, curve: Int, grain: ByteArray?): Boolean =
        glAlive() && RenderNative.paintBegin(handle, layerId, mode, opacity, alphaLock, r, g, b, shape, hardness, grainKind, grainMode, grainScale, grainContrast, roundness, curve, grain)
    fun brushPreview(mode: Int, r: Float, g: Float, b: Float, shape: Int, hardness: Float, grainKind: Int, grainMode: Int,
                     grainScale: Float, grainContrast: Float, roundness: Float, curve: Int, grain: ByteArray?, dabs: FloatArray, count: Int, w: Int, h: Int): ByteArray? =
        if (!glAlive()) null else RenderNative.brushPreview(handle, mode, r, g, b, shape, hardness, grainKind, grainMode, grainScale, grainContrast, roundness, curve, grain, dabs, count, w, h)
    fun paintDabs(dabs: FloatArray, count: Int) { if (glAlive()) RenderNative.paintDabs(handle, dabs, count) }
    fun paintEnd(commit: Boolean): ByteArray? = if (!glAlive()) null else RenderNative.paintEnd(handle, commit)
    fun paintEndAsync(layerId: Int): Boolean = glAlive() && RenderNative.paintEndAsync(handle, layerId)
    fun paintPoll(): ByteArray? = if (!glAlive()) null else RenderNative.paintPoll(handle)
    fun paintRestore(layerId: Int, x: Int, y: Int, w: Int, h: Int, data: ByteArray): Boolean =
        glAlive() && RenderNative.paintRestore(handle, layerId, x, y, w, h, data)

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
        if (!glAlive()) return Result.failure(IllegalStateException("Рендерер не готов"))
        val hooks = object : ExportHooks {
            override fun begin(width: Int, height: Int) {
                RenderNative.beginExport(handle, width, height)
                RenderNative.setBackground(handle, background)
                RenderNative.setSelection(handle, null)
            }

            override fun drawFrame(index: Int) {
                applyScene(sceneAt(index))
                // все текстуры кадра загружаются, затем ровно одна отрисовка (раньше - до 500 перерисовок подряд)
                RenderNative.drawFrameComplete(handle)
            }

            override fun readFrame(index: Int, width: Int, height: Int): ByteArray? {
                if (!glAlive()) return null
                applyScene(sceneAt(index))
                RenderNative.setBackground(handle, background)
                RenderNative.setSelection(handle, null)
                return RenderNative.captureFrame(handle, width, height, false)
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
        val h = handle
        handle = 0L
        if (h == 0L) return
        if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) RenderNative.destroy(h) else RenderNative.abandon(h)
    }
}

class VtGLSurfaceView(context: Context) : GLSurfaceView(context) {
    private val pendingScene = AtomicReference<SceneData?>(null)
    private val params = AtomicReference(FrameParams())
    private val vtRenderer = VtRenderer({ pendingScene.getAndSet(null) }, { params.get() }, { requestRender() })

    // --- жизненный цикл (Sprint 6.9.1) ---
    // GLSurfaceView.onPause/surfaceDestroyed/onDetachedFromWindow БЛОКИРУЮТ главный поток, пока GL-поток не освободится,
    // а очередь queueEvent выполняется раньше обработки паузы. Длинные задачи в очереди (экспорт, сотни перерисовок
    // снимка, повторы символов) = «Приложение не отвечает» при сворачивании/возврате. Поэтому: тяжёлые задачи не
    // ставятся в очередь, когда вид неактивен, а идущий экспорт останавливается до вызова super.onPause().
    @Volatile private var paused = false
    @Volatile private var hasSurface = false
    @Volatile private var attached = false
    private val runningExport = AtomicReference<java.util.concurrent.atomic.AtomicBoolean?>(null)
    @Volatile private var exportStoppedByPause = false

    /** Можно ставить тяжёлые GL-задачи: вид на экране, не на паузе, поверхность есть. */
    val active: Boolean get() = attached && !paused && hasSurface

    /** Предел стороны снимка (символы, PNG): GL_MAX_TEXTURE_SIZE устройства, не больше 8192. */
    val maxCaptureSide: Int get() = vtRenderer.maxCaptureSide

    /** Колбэк ViewModel: контекст GL создан ([recreated] - не впервые у этого вида). Вызывается из GL-потока. */
    var onContextReady: ((recreated: Boolean) -> Unit)?
        get() = vtRenderer.onContextReady
        set(v) { vtRenderer.onContextReady = v }

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(vtRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private fun stopExport() {
        runningExport.get()?.let { exportStoppedByPause = true; it.set(true) }
    }

    override fun onPause() {
        paused = true
        stopExport()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        paused = false
        requestRender()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        hasSurface = true   // до super: onSurfaceCreated рендерера (и колбэк ViewModel) может прийти раньше возврата
        super.surfaceCreated(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hasSurface = false
        stopExport()
        super.surfaceDestroyed(holder)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopExport()
        super.onDetachedFromWindow()
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
    /** Память текстур (см. RenderNative.textureStats); callback - в GL-потоке. */
    fun textureStats(callback: (IntArray?) -> Unit) {
        if (!active) { callback(null); return }
        queueEvent { callback(vtRenderer.textureStats()) }
    }

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
        if (!active) { callback(null); return }
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

    /** Снимок кадра холста w x h (RGBA premultiplied, строка 0 - низ); [callback] из GL-потока. */
    fun capture(scene: SceneData?, width: Int, height: Int, transparent: Boolean, background: Int, callback: (ByteArray?) -> Unit) {
        // тяжёлая задача: на паузе/без поверхности сразу null (вызывающий повторит после возврата)
        if (!active) { callback(null); return }
        queueEvent { callback(if (active) vtRenderer.capture(scene, width, height, transparent, background) else null); requestRender() }
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
        if (!active) { onDone(Result.failure(ExportInterruptedException())); return }
        queueEvent {
            if (!active) { onDone(Result.failure(ExportInterruptedException())); return@queueEvent }
            exportStoppedByPause = false
            runningExport.set(cancel)
            val r = try { vtRenderer.exportVideo(spec, background, sceneAt, onProgress, cancel) } finally { runningExport.set(null) }
            onDone(if (r.isFailure && exportStoppedByPause) Result.failure(ExportInterruptedException()) else r)
            requestRender()
        }
    }

    /** Освобождает нативные GL-объекты в GL-потоке. */
    fun releaseNative() {
        queueEvent { vtRenderer.release() }
    }
}

/** Доступ к текущему GL-виду из ViewModel (ставится при создании вида, снимается при release). */
object RenderHost {
    @Volatile
    var view: VtGLSurfaceView? = null
}
