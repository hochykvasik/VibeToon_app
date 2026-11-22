package com.vibetoon.render

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import com.vibetoon.domain.anim.ExportMath
import com.vibetoon.domain.render.YuvMath
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VT/Export"

/**
 * Параметры экспорта: размер кадра, частота, число кадров готового видео.
 * [compat] - «совместимый» путь: кадр читается из GL и кодируется из YUV-буферов (медленнее, но без Surface кодека).
 */
data class ExportSpec(val width: Int, val height: Int, val fps: Int, val frames: Int, val file: File, val compat: Boolean = false) {
    val bitrate: Int get() = (width.toLong() * height * fps * 12 / 100).coerceIn(2_000_000L, 40_000_000L).toInt()
}

/** Экспорт прерван тем, что приложение свернули (GL-поток нельзя держать: onPause ждёт его и даёт ANR). */
class ExportInterruptedException : Exception("Экспорт прерван: приложение было свёрнуто. Запустите экспорт ещё раз")

/** Что экспортёру нужно от рендерера (реализует VtRenderer, всё в GL-потоке). */
interface ExportHooks {
    fun begin(width: Int, height: Int)
    /** Рисует кадр [index] (0-based) готового видео в текущую поверхность. */
    fun drawFrame(index: Int)
    fun end()
    /** Совместимый путь: кадр [index] в память, RGBA w x h, строка 0 - низ, непрозрачный фон. null - не снялся. */
    fun readFrame(index: Int, width: Int, height: Int): ByteArray?
}

/**
 * Рендер в MP4 быстрее реального времени. Основной путь: кадры рисуются прямо в Surface кодека MediaCodec (H.264),
 * без чтения пикселей в память. Запасной (совместимый) путь: кадр читается glReadPixels, переводится в YUV420 и
 * кладётся во входные буферы кодека с учётом их шага строки/пикселя. Вызывать из GL-потока.
 *
 * Зелёные полосы на части устройств (Sprint 6.9.1): поверхность кодека создавалась из конфига экранного контекста, у
 * которого нет EGL_RECORDABLE_ANDROID; такой буфер gralloc кодек читает с неверным форматом/шагом. Теперь берётся
 * recordable-конфиг (совместимый с контекстом), стороны кратны 16, размер проверяется возможностями кодека; если
 * recordable-конфига нет или поверхность не создаётся - автоматически совместимый путь.
 */
object VideoExporter {
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val EGL_OPENGL_ES3_BIT = 0x40

    /** Размер, который точно примет AVC-кодировщик устройства (кратно 16 и в пределах его возможностей). */
    fun supportedSize(w: Int, h: Int): Pair<Int, Int> {
        val caps = runCatching {
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            ) ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }?.name
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }
                ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        }.getOrNull() ?: return w to h
        val align = maxOf(ExportMath.ALIGN, caps.widthAlignment, caps.heightAlignment)
        return ExportMath.encoderSize(w, h, align) { sw, sh -> caps.isSizeSupported(sw, sh) } ?: (w to h)
    }

    fun encode(spec: ExportSpec, hooks: ExportHooks, onProgress: (Int) -> Unit, cancel: AtomicBoolean): Result<Unit> {
        if (spec.compat) return encodeBuffers(spec, hooks, onProgress, cancel)
        return when (val r = encodeSurface(spec, hooks, onProgress, cancel)) {
            is SurfaceResult.Done -> r.result
            // поверхность кодека не создалась ещё до первого кадра: тот же экспорт совместимым путём
            is SurfaceResult.NoSurface -> {
                Log.w(TAG, "surface path unavailable (${r.reason}), falling back to buffers")
                encodeBuffers(spec, hooks, onProgress, cancel)
            }
        }
    }

    private sealed interface SurfaceResult {
        class Done(val result: Result<Unit>) : SurfaceResult
        class NoSurface(val reason: String) : SurfaceResult
    }

    private fun avcFormat(spec: ExportSpec, colorFormat: Int) =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    private fun encodeSurface(spec: ExportSpec, hooks: ExportHooks, onProgress: (Int) -> Unit, cancel: AtomicBoolean): SurfaceResult {
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT) {
            return SurfaceResult.Done(Result.failure(IllegalStateException("Нет активного GL-контекста")))
        }
        val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var inputSurface: android.view.Surface? = null
        var begun = false
        var switched = false
        var framesStarted = false
        try {
            spec.file.parentFile?.mkdirs()
            if (spec.file.exists()) spec.file.delete()
            val config = recordableConfig(display, context) ?: return SurfaceResult.NoSurface("нет EGL_RECORDABLE_ANDROID конфига")
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(avcFormat(spec, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            eglSurface = EGL14.eglCreateWindowSurface(display, config, inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return SurfaceResult.NoSurface("eglCreateWindowSurface: ${EGL14.eglGetError()}")
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                val err = EGL14.eglGetError()
                EGL14.eglMakeCurrent(display, oldDraw, oldRead, context)
                return SurfaceResult.NoSurface("eglMakeCurrent: $err")
            }
            switched = true
            muxer = MediaMuxer(spec.file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            hooks.begin(spec.width, spec.height)
            begun = true

            val drain = Drain(codec, muxer)
            for (i in 0 until spec.frames) {
                if (cancel.get()) error("Отменено")
                framesStarted = true
                hooks.drawFrame(i)
                EGLExt.eglPresentationTimeANDROID(display, eglSurface, i * 1_000_000_000L / spec.fps)
                if (!EGL14.eglSwapBuffers(display, eglSurface)) error("eglSwapBuffers: ${EGL14.eglGetError()}")
                drain.run(endOfStream = false)
                onProgress(i + 1)
            }
            codec.signalEndOfInputStream()
            drain.run(endOfStream = true)
            if (!drain.started) error("Кодек не выдал ни одного кадра")
            return SurfaceResult.Done(Result.success(Unit))
        } catch (t: Throwable) {
            Log.e(TAG, "surface export failed", t)
            if (!framesStarted && !cancel.get()) return SurfaceResult.NoSurface(t.message ?: t.javaClass.simpleName)
            return SurfaceResult.Done(Result.failure(t))
        } finally {
            // возвращаем контекст на поверхность экрана и только потом освобождаем остальное
            if (switched) EGL14.eglMakeCurrent(display, oldDraw, oldRead, context)
            if (begun) runCatching { hooks.end() }
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /**
     * EGL-конфиг для поверхности кодека: обязательно EGL_RECORDABLE_ANDROID и тот же формат цвета, что у контекста
     * (иначе eglMakeCurrent с нашим контекстом не пройдёт). Сначала конфиг самого контекста, если он recordable.
     */
    private fun recordableConfig(display: android.opengl.EGLDisplay, context: android.opengl.EGLContext): EGLConfig? {
        val id = IntArray(1)
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)
        val one = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, one, 0, 1, n, 0) || n[0] == 0) return null
        val ctxCfg = one[0] ?: return null
        fun attr(c: EGLConfig, a: Int): Int { val v = IntArray(1); EGL14.eglGetConfigAttrib(display, c, a, v, 0); return v[0] }
        if (attr(ctxCfg, EGL_RECORDABLE_ANDROID) == 1) return ctxCfg
        val r = attr(ctxCfg, EGL14.EGL_RED_SIZE); val g = attr(ctxCfg, EGL14.EGL_GREEN_SIZE)
        val b = attr(ctxCfg, EGL14.EGL_BLUE_SIZE); val a = attr(ctxCfg, EGL14.EGL_ALPHA_SIZE)
        val want = intArrayOf(
            EGL14.EGL_RED_SIZE, r, EGL14.EGL_GREEN_SIZE, g, EGL14.EGL_BLUE_SIZE, b, EGL14.EGL_ALPHA_SIZE, a,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        val list = arrayOfNulls<EGLConfig>(16)
        if (!EGL14.eglChooseConfig(display, want, 0, list, 0, list.size, n, 0) || n[0] == 0) return null
        // eglChooseConfig отдаёт и конфиги «не меньше» запрошенных: нужен точно такой же формат цвета
        return list.take(n[0]).filterNotNull().firstOrNull { c ->
            attr(c, EGL14.EGL_RED_SIZE) == r && attr(c, EGL14.EGL_GREEN_SIZE) == g && attr(c, EGL14.EGL_BLUE_SIZE) == b && attr(c, EGL14.EGL_ALPHA_SIZE) == a
        }
    }

    /** Совместимый путь: glReadPixels -> YUV420 -> входные буферы кодека (Image с шагами строк/пикселей). */
    private fun encodeBuffers(spec: ExportSpec, hooks: ExportHooks, onProgress: (Int) -> Unit, cancel: AtomicBoolean): Result<Unit> {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val w = spec.width; val h = spec.height
        try {
            spec.file.parentFile?.mkdirs()
            if (spec.file.exists()) spec.file.delete()
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(avcFormat(spec, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(spec.file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val drain = Drain(codec, muxer)
            val y = ByteArray(w * h); val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
            for (i in 0 until spec.frames) {
                if (cancel.get()) error("Отменено")
                val rgba = hooks.readFrame(i, w, h) ?: error("Не удалось снять кадр ${i + 1}")
                YuvMath.rgbaToI420(rgba, w, h, true, y, u, v)
                val idx = dequeueInput(codec, drain, cancel)
                val img = codec.getInputImage(idx) ?: error("Кодек не дал буфер YUV420")
                val planes = img.planes
                for ((p, src) in listOf(0 to y, 1 to u, 2 to v)) {
                    val pl = planes[p]; val buf = pl.buffer
                    val pw = if (p == 0) w else w / 2; val ph = if (p == 0) h else h / 2
                    if (pl.pixelStride == 1) {
                        for (row in 0 until ph) { buf.position(row * pl.rowStride); buf.put(src, row * pw, pw) }
                    } else {
                        YuvMath.copyPlane(src, pw, ph, pl.rowStride, pl.pixelStride) { k, b -> buf.put(k, b) }
                    }
                }
                codec.queueInputBuffer(idx, 0, w * h * 3 / 2, i * 1_000_000L / spec.fps, 0)
                drain.run(endOfStream = false)
                onProgress(i + 1)
            }
            val last = dequeueInput(codec, drain, cancel)
            codec.queueInputBuffer(last, 0, 0, spec.frames * 1_000_000L / spec.fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain.run(endOfStream = true)
            if (!drain.started) error("Кодек не выдал ни одного кадра")
            return Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "buffer export failed", t)
            return Result.failure(t)
        } finally {
            runCatching { hooks.end() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun dequeueInput(codec: MediaCodec, drain: Drain, cancel: AtomicBoolean): Int {
        var waited = 0
        while (true) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) return idx
            if (cancel.get()) error("Отменено")
            drain.run(endOfStream = false)   // выход кодека освобождает входные буферы
            waited += 10
            if (waited > 10_000) error("Кодек не принимает кадры")
        }
    }

    /** Забирает готовые буферы кодека и пишет их в MP4. */
    private class Drain(private val codec: MediaCodec, private val muxer: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        var started = false
            private set

        fun run(endOfStream: Boolean) {
            var idleMs = 0
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        idleMs += 10
                        if (idleMs > 10_000) error("Кодек завис при завершении")
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                    idx >= 0 -> {
                        idleMs = 0
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && started) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }
}
