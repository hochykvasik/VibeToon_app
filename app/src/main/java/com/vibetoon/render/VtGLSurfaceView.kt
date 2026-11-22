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
    val background: Int = 0
)

/** Адаптер колбэков GLSurfaceView -> C++. Работает на GL-потоке. */
class VtRenderer(
    private val takeScene: () -> SceneData?,
    private val frameParams: () -> FrameParams,
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer {
    private var handle = 0L
    private var lastScene: SceneData? = null
    private var sampleReq: Triple<Int, Int, (Int) -> Unit>? = null

    /** Пипетка: цвет пикселя после ближайшего кадра (в GL-потоке). */
    fun sampleColor(x: Int, y: Int, cb: (Int) -> Unit) { sampleReq = Triple(x, y, cb) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Контекст мог быть потерян: старый рендерер освобождаем и создаём заново.
        if (handle != 0L) RenderNative.destroy(handle)
        handle = RenderNative.create()
        val maxTex = RenderNative.initGL(handle)
        Log.i(TAG_GL, "initGL maxTexture=$maxTex")
        // Текстуры потеряны вместе с контекстом; сцену отправим заново (TODO: перезагрузка слоёв).
        lastScene?.let { applyScene(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        RenderNative.setViewport(handle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        takeScene()?.let { lastScene = it; applyScene(it) }
        val p = frameParams()
        RenderNative.setCamera(handle, p.camera.zoom, p.camera.panX, p.camera.panY)
        RenderNative.setSelection(handle, p.selectionQuad)
        RenderNative.setBackground(handle, p.background)
        val more = RenderNative.drawFrame(handle)
        sampleReq?.let { (x, y, cb) -> sampleReq = null; cb(RenderNative.readPixel(handle, x, y)) }
        if (more) requestRender() // в очереди остались загрузки
    }

    /** Снимок кадра: при [scene] != null временно подставляет её, потом возвращает прежнюю. Только из GL-потока. */
    fun capture(scene: SceneData?, width: Int, height: Int, transparent: Boolean, background: Int): ByteArray? {
        if (handle == 0L) return null
        if (scene != null) applyScene(scene)
        RenderNative.setBackground(handle, background)
        val r = RenderNative.captureFrame(handle, width, height, transparent)
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
    fun paintEnd(commit: Boolean): ByteArray? = if (handle == 0L) null else RenderNative.paintEnd(handle, commit)
    fun paintEndAsync(layerId: Int): Boolean = handle != 0L && RenderNative.paintEndAsync(handle, layerId)
    fun paintPoll(): ByteArray? = if (handle == 0L) null else RenderNative.paintPoll(handle)
    fun paintRestore(layerId: Int, x: Int, y: Int, w: Int, h: Int, data: ByteArray): Boolean =
        handle != 0L && RenderNative.paintRestore(handle, layerId, x, y, w, h, data)

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

            override fun drawFrame(index: Int) {
                applyScene(sceneAt(index))
                // пока в очереди есть загрузки текстур, кадр рисуется повторно (лимит от зависания)
                var guard = 0
                while (RenderNative.drawFrame(handle) && guard++ < 500) { /* ждём все текстуры */ }
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
    private val vtRenderer = VtRenderer({ pendingScene.getAndSet(null) }, { params.get() }, { requestRender() })

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
        queueEvent { callback(vtRenderer.capture(scene, width, height, transparent, background)); requestRender() }
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
            val r = vtRenderer.exportVideo(spec, background, sceneAt, onProgress, cancel)
            onDone(r)
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
