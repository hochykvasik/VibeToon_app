package com.vibetoon.render

import android.media.MediaCodec
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
data class ExportSpec(val width: Int, val height: Int, val fps: Int, val frames: Int, val file: File) {
    val bitrate: Int get() = (width.toLong() * height * fps * 12 / 100).coerceIn(2_000_000L, 40_000_000L).toInt()
}

/** Что экспортёру нужно от рендерера (реализует VtRenderer, всё в GL-потоке). */
interface ExportHooks {
    fun begin(width: Int, height: Int)
    /** Рисует кадр [index] (0-based) готового видео в текущую поверхность. */
    fun drawFrame(index: Int)
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
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(spec.file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            eglSurface = createWindowSurface(display, context, inputSurface)
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) error("eglMakeCurrent: ${EGL14.eglGetError()}")
            switched = true
            hooks.begin(spec.width, spec.height)
            begun = true

            val drain = Drain(codec, muxer)
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

    /** Поверхность окна для Surface кодека. Сначала конфиг самого контекста (всегда совместим), затем recordable. */
    private fun createWindowSurface(display: android.opengl.EGLDisplay, context: android.opengl.EGLContext, surface: android.view.Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val ctxConfigId = IntArray(1)
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, ctxConfigId, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val same = intArrayOf(EGL14.EGL_CONFIG_ID, ctxConfigId[0], EGL14.EGL_NONE)
        if (EGL14.eglChooseConfig(display, same, 0, configs, 0, 1, n, 0) && n[0] > 0) {
            val s = EGL14.eglCreateWindowSurface(display, configs[0], surface, attribs, 0)
            if (s != EGL14.EGL_NO_SURFACE) return s
        }
        val rec = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(display, rec, 0, configs, 0, 1, n, 0) || n[0] == 0) error("Нет EGL-конфига для кодека")
        val s = EGL14.eglCreateWindowSurface(display, configs[0], surface, attribs, 0)
        if (s == EGL14.EGL_NO_SURFACE) error("eglCreateWindowSurface: ${EGL14.eglGetError()}")
        return s
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
