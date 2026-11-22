package com.vibetoon.render

import android.media.MediaCodec
import android.media.MediaCodecList
import com.vibetoon.domain.export.VideoPixels
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VT/Export"

/** Параметры экспорта: размер кадра, частота, число кадров готового видео. */
data class ExportSpec(val width: Int, val height: Int, val fps: Int, val frames: Int, val file: File, val compatible: Boolean = false) {
    val bitrate: Int get() = (width.toLong() * height * fps * 12 / 100).coerceIn(2_000_000L, 40_000_000L).toInt()
}

/** Что экспортёру нужно от рендерера (реализует VtRenderer, всё в GL-потоке). */
interface ExportHooks {
    fun begin(width: Int, height: Int)
    /** Рисует кадр [index] (0-based) готового видео в текущую поверхность. */
    fun drawFrame(index: Int)
    fun readFrame(index: Int, width: Int, height: Int): ByteArray?
    fun end()
}

/**
 * Рендер в MP4 быстрее реального времени: кадры рисуются прямо в Surface кодека MediaCodec
 * (H.264), без чтения пикселей в память. Вызывать из GL-потока: текстуры принадлежат его контексту,
 * на время экспорта этот контекст временно переключается на поверхность кодека.
 */
object VideoExporter {
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    fun encode(spec: ExportSpec, hooks: ExportHooks, onProgress: (Int) -> Unit, cancel: AtomicBoolean): Result<Unit> {
        if (spec.width <= 0 || spec.height <= 0 || spec.width % 16 != 0 || spec.height % 16 != 0 || spec.fps <= 0 || spec.frames <= 0)
            return Result.failure(IllegalArgumentException("Некорректный размер видео"))
        if (cancel.get()) return Result.failure(IllegalStateException("Отменено"))
        if (spec.compatible) return encodeYuv(spec, hooks, onProgress, cancel)
        val direct = encodeSurface(spec, hooks, onProgress, cancel)
        if (direct.isSuccess || cancel.get()) return direct
        Log.w(TAG, "Surface encoder failed; retrying with RGBA/YUV readback", direct.exceptionOrNull())
        return encodeYuv(spec, hooks, onProgress, cancel)
    }

    private fun encoder(spec: ExportSpec, color: Int): MediaCodec {
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } && runCatching {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                color in caps.colorFormats && caps.videoCapabilities.isSizeSupported(spec.width, spec.height) && caps.videoCapabilities.isRateSupported(spec.fps.toDouble())
            }.getOrDefault(false)
        } ?: error("Нет совместимого кодека H.264")
        return MediaCodec.createByCodecName(info.name)
    }

    private fun encodeSurface(spec: ExportSpec, hooks: ExportHooks, onProgress: (Int) -> Unit, cancel: AtomicBoolean): Result<Unit> {
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT) {
            return Result.failure(IllegalStateException("Нет активного GL-контекста"))
        }
        val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var inputSurface: android.view.Surface? = null
        var begun = false
        var switched = false
        try {
            spec.file.parentFile?.mkdirs()
            if (spec.file.exists()) spec.file.delete()

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = encoder(spec, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(spec.file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            eglSurface = createWindowSurface(display, context, inputSurface)
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) error("eglMakeCurrent: ${EGL14.eglGetError()}")
            switched = true
            hooks.begin(spec.width, spec.height)
            begun = true

            val drain = Drain(codec, muxer, cancel)
            for (i in 0 until spec.frames) {
                if (cancel.get()) error("Отменено")
                hooks.drawFrame(i)
                EGLExt.eglPresentationTimeANDROID(display, eglSurface, i * 1_000_000_000L / spec.fps)
                if (!EGL14.eglSwapBuffers(display, eglSurface)) error("eglSwapBuffers: ${EGL14.eglGetError()}")
                drain.run(endOfStream = false)
                onProgress(i + 1)
            }
            codec.signalEndOfInputStream()
            drain.run(endOfStream = true)
            if (!drain.started) error("Кодек не выдал ни одного кадра")
            muxer.stop()
            return Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "export failed", t)
            return Result.failure(t)
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

    /** Only the actual context config is safe; an unrelated recordable config may be EGL_BAD_MATCH. */
    private fun createWindowSurface(display: android.opengl.EGLDisplay, context: android.opengl.EGLContext, surface: android.view.Surface): EGLSurface {
        val id = IntArray(1); val count = IntArray(1); val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0))
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        val recordable = IntArray(1)
        check(EGL14.eglGetConfigAttrib(display, configs[0], EGL_RECORDABLE_ANDROID, recordable, 0) && recordable[0] == 1) { "EGL config is not recordable" }
        val result = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(result != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface: ${EGL14.eglGetError()}" }
        return result
    }

    /** Slow but independent of encoder Surface drivers. Input images honor row/pixel strides. */
    private fun encodeYuv(spec: ExportSpec, hooks: ExportHooks, onProgress: (Int) -> Unit, cancel: AtomicBoolean): Result<Unit> {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            spec.file.parentFile?.mkdirs()
            spec.file.delete()
            codec = encoder(spec, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(spec.file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val drain = Drain(codec, muxer, cancel)
            fun input(): Int {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (true) {
                    check(!cancel.get()) { "Отменено" }
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) return index
                    drain.run(false)
                    check(System.nanoTime() < deadline) { "Кодек завис при завершении" }
                }
            }
            for (i in 0 until spec.frames) {
                check(!cancel.get()) { "Отменено" }
                val rgba = hooks.readFrame(i, spec.width, spec.height) ?: error("Не удалось снять кадр")
                val yuv = VideoPixels.fromGlRgba(rgba, spec.width, spec.height)
                val index = input()
                val image = codec.getInputImage(index) ?: error("Кодек не предоставляет YUV Image")
                val arrays = arrayOf(yuv.y, yuv.u, yuv.v)
                image.planes.forEachIndexed { p, plane ->
                    val width = if (p == 0) spec.width else spec.width / 2
                    val height = if (p == 0) spec.height else spec.height / 2
                    val buffer = plane.buffer
                    val start = buffer.position()
                    for (row in 0 until height) for (x in 0 until width)
                        buffer.put(start + row * plane.rowStride + x * plane.pixelStride, arrays[p][row * width + x])
                }
                codec.queueInputBuffer(index, 0, spec.width * spec.height * 3 / 2, i * 1_000_000L / spec.fps, 0)
                drain.run(false)
                onProgress(i + 1)
            }
            codec.queueInputBuffer(input(), 0, 0, spec.frames * 1_000_000L / spec.fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain.run(true)
            check(drain.started) { "Кодек не выдал ни одного кадра" }
            muxer.stop()
            return Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "YUV export failed", t)
            return Result.failure(t)
        } finally {
            runCatching { hooks.end() }
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }
    }

    /** Забирает готовые буферы кодека и пишет их в MP4. */
    private class Drain(private val codec: MediaCodec, private val muxer: MediaMuxer, private val cancel: AtomicBoolean) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        var started = false
            private set

        fun run(endOfStream: Boolean) {
            var idleMs = 0
            while (true) {
                check(!cancel.get()) { "Отменено" }
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
