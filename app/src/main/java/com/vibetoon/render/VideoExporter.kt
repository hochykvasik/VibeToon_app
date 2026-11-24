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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Параметры экспорта: размер кадра, частота, число кадров готового видео. */
data class ExportSpec(val width: Int, val height: Int, val fps: Int, val frames: Int, val file: File, val compatible: Boolean = false) {
    /** Битрейт H.264: 0.25 бит/пиксель/кадр (720p30 ≈ 6.9 Мбит/с) — без мыла на линиях и заливках. */
    val bitrate: Int get() = (width.toLong() * height * fps * 25 / 100).coerceIn(4_000_000L, 40_000_000L).toInt()
}

/** Что экспортёру нужно от рендерера (реализует VtRenderer, всё в GL-потоке). */
interface ExportHooks {
    fun begin(width: Int, height: Int)
    /**
     * Рисует кадр [index] (0-based) готового видео в текущую поверхность. false - кадр прерван (пауза приложения
     * или отмена посреди догрузки текстур): ничего не отдаётся кодеку, кадр будет нарисован заново.
     */
    fun drawFrame(index: Int): Boolean
    /** RGBA кадра [index] или null (пауза/ошибка снимка). */
    fun readFrame(index: Int, width: Int, height: Int): ByteArray?
    fun end()
}

/** GL-контекст пересоздан (потерян, пока приложение было свёрнуто): объекты экспорта недействительны. */
class GlContextLost : IllegalStateException("GL-контекст потерян")

/**
 * Пакет 27: один проход записи MP4, разбитый на покадровые задачи GL-потока (порядок задач - ExportTaskMachine).
 * Все методы - только из GL-потока. После любой ошибки или в конце вызывающий обязан вызвать [release].
 */
interface FrameSink {
    fun start()
    /** true - кадр записан; false - прерван (пауза), ничего не записано, кадр надо повторить. */
    fun frame(index: Int): Boolean
    fun finish()
    /** [contextAlive] = false: GL-контекст уже недействителен - никаких GL-вызовов, только кодек и файл. */
    fun release(contextAlive: Boolean = true)
}

/**
 * Рендер в MP4 быстрее реального времени: кадры рисуются прямо в Surface кодека MediaCodec
 * (H.264), без чтения пикселей в память. Вызывать из GL-потока: текстуры принадлежат его контексту,
 * на время кадра этот контекст временно переключается на поверхность кодека и сразу возвращается на экран -
 * между кадрами GL-поток свободен (пауза приложения, другие события GL).
 */
object VideoExporter {
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    /** null - параметры годятся, иначе текст ошибки. */
    fun validate(spec: ExportSpec): String? =
        if (spec.width <= 0 || spec.height <= 0 || spec.width % 16 != 0 || spec.height % 16 != 0 || spec.fps <= 0 || spec.frames <= 0)
            "Некорректный размер видео" else null

    /** Прямая запись в Surface кодека ([yuv] = false) или запасной путь RGBA -> YUV. */
    fun sink(spec: ExportSpec, hooks: ExportHooks, cancel: AtomicBoolean, interrupted: () -> Boolean, yuv: Boolean): FrameSink =
        if (yuv) YuvSink(spec, hooks, cancel, interrupted) else SurfaceSink(spec, hooks, cancel)

    /** Уровень H.264 по разрешению и частоте (High profile): 720p30 -> 4.1, 1080p60 -> 4.2. */
    private fun avcLevel(width: Int, height: Int, fps: Int): Int {
        val macroblocks = ((width + 15) / 16) * ((height + 15) / 16)
        return when {
            macroblocks * fps > 162_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
            macroblocks * fps > 81_920 -> MediaCodecInfo.CodecProfileLevel.AVCLevel41
            macroblocks * fps > 40_960 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }
    }

    /** High profile только если кодек его заявляет на нужном уровне; иначе остаётся профиль кодека по умолчанию. */
    internal fun highProfile(codec: MediaCodec, format: MediaFormat, spec: ExportSpec) {
        runCatching {
            val need = avcLevel(spec.width, spec.height, spec.fps)
            val levels = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels
            if (levels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh && it.level >= need }) {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                format.setInteger(MediaFormat.KEY_LEVEL, need)
            }
        }
    }

    internal fun encoder(spec: ExportSpec, color: Int): MediaCodec {
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } && runCatching {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                color in caps.colorFormats && caps.videoCapabilities.isSizeSupported(spec.width, spec.height)
            }.getOrDefault(false)
        } ?: error("Нет совместимого кодека H.264")
        return MediaCodec.createByCodecName(info.name)
    }

    /** Only the actual context config is safe; an unrelated recordable config may be EGL_BAD_MATCH. */
    internal fun createWindowSurface(display: android.opengl.EGLDisplay, context: android.opengl.EGLContext, surface: android.view.Surface): EGLSurface {
        val id = IntArray(1); val count = IntArray(1); val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0))
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        val recordable = IntArray(1)
        check(EGL14.eglGetConfigAttrib(display, configs[0], EGL_RECORDABLE_ANDROID, recordable, 0) && recordable[0] == 1) { "EGL config is not recordable" }
        val result = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(result != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface: ${EGL14.eglGetError()}" }
        return result
    }

    /** Прямой путь: кадр рисуется в Surface кодека через EGL-поверхность того же контекста. */
    private class SurfaceSink(private val spec: ExportSpec, private val hooks: ExportHooks, private val cancel: AtomicBoolean) : FrameSink {
        private var display = EGL14.EGL_NO_DISPLAY
        private var context = EGL14.EGL_NO_CONTEXT
        private var codec: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var muxerStopped = false
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var inputSurface: android.view.Surface? = null
        private var drain: Drain? = null
        private var begun = false

        /**
         * Выполняет [block] с поверхностью кодека как текущей и возвращает прежние поверхности (экран; на паузе - ничего).
         * false - контекст не удалось сделать текущим (блок не выполнялся).
         */
        private inline fun withCodecSurface(block: () -> Unit): Boolean {
            val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            val oldContext = EGL14.eglGetCurrentContext()
            if (oldContext != EGL14.EGL_NO_CONTEXT && oldContext != context) throw GlContextLost()
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) return false
            try {
                block()
            } finally {
                if (oldContext == EGL14.EGL_NO_CONTEXT) EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                else EGL14.eglMakeCurrent(display, oldDraw, oldRead, context)
            }
            return true
        }

        override fun start() {
            display = EGL14.eglGetCurrentDisplay()
            context = EGL14.eglGetCurrentContext()
            check(display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT) { "Нет активного GL-контекста" }
            spec.file.parentFile?.mkdirs()
            if (spec.file.exists()) spec.file.delete()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = encoder(spec, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            codec = c
            highProfile(c, format, spec)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = c.createInputSurface()
            inputSurface = input
            c.start()
            val m = MediaMuxer(spec.file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            drain = Drain(c, m, cancel)
            eglSurface = createWindowSurface(display, context, input)
            val ok = withCodecSurface {
                hooks.begin(spec.width, spec.height)
                begun = true
            }
            check(ok) { "eglMakeCurrent: ${EGL14.eglGetError()}" }
        }

        override fun frame(index: Int): Boolean {
            check(!cancel.get()) { "Отменено" }
            if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) return false   // приложение свёрнуто: контекст снят
            var drawn = false
            val ok = withCodecSurface {
                if (!hooks.drawFrame(index)) return@withCodecSurface
                EGLExt.eglPresentationTimeANDROID(display, eglSurface, index * 1_000_000_000L / spec.fps)
                if (!EGL14.eglSwapBuffers(display, eglSurface)) error("eglSwapBuffers: ${EGL14.eglGetError()}")
                drawn = true
            }
            check(ok) { "eglMakeCurrent: ${EGL14.eglGetError()}" }
            if (!drawn) return false
            drain!!.run(endOfStream = false)
            return true
        }

        override fun finish() {
            val c = codec ?: error("Кодек не открыт")
            c.signalEndOfInputStream()
            val d = drain!!
            d.run(endOfStream = true)
            if (!d.started) error("Кодек не выдал ни одного кадра")
            muxer?.stop()
            muxerStopped = true
        }

        override fun release(contextAlive: Boolean) {
            // сначала вернуть вид редактора (endExport) в том же контексте, потом освобождать кодек
            if (begun && contextAlive) runCatching { withCodecSurface { hooks.end() } }
            begun = false
            if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            eglSurface = EGL14.EGL_NO_SURFACE
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            if (!muxerStopped) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            codec = null; inputSurface = null; muxer = null
        }
    }

    /** Запасной путь: медленный, но не зависит от драйверов Surface кодека. Строки и шаг пикселей плоскостей учитываются. */
    private class YuvSink(
        private val spec: ExportSpec, private val hooks: ExportHooks, private val cancel: AtomicBoolean, private val interrupted: () -> Boolean
    ) : FrameSink {
        private var codec: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var drain: Drain? = null

        private fun input(): Int {
            val c = codec!!
            val deadline = System.nanoTime() + 5_000_000_000L
            while (true) {
                check(!cancel.get()) { "Отменено" }
                val index = c.dequeueInputBuffer(10_000)
                if (index >= 0) return index
                drain!!.run(false)
                check(System.nanoTime() < deadline) { "Кодек завис при завершении" }
            }
        }

        override fun start() {
            spec.file.parentFile?.mkdirs()
            spec.file.delete()
            val c = encoder(spec, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = c
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            highProfile(c, format, spec)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val m = MediaMuxer(spec.file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            drain = Drain(c, m, cancel)
        }

        override fun frame(index: Int): Boolean {
            check(!cancel.get()) { "Отменено" }
            val rgba = hooks.readFrame(index, spec.width, spec.height)
            if (rgba == null) {
                if (interrupted()) return false   // пауза: снимок не сделан, кадр повторится после возврата
                error("Не удалось снять кадр")
            }
            val c = codec!!
            val yuv = VideoPixels.fromGlRgba(rgba, spec.width, spec.height)
            val bufIndex = input()
            val image = c.getInputImage(bufIndex) ?: error("Кодек не предоставляет YUV Image")
            val arrays = arrayOf(yuv.y, yuv.u, yuv.v)
            image.planes.forEachIndexed { p, plane ->
                val width = if (p == 0) spec.width else spec.width / 2
                val height = if (p == 0) spec.height else spec.height / 2
                val buffer = plane.buffer
                val start = buffer.position()
                for (row in 0 until height) for (x in 0 until width)
                    buffer.put(start + row * plane.rowStride + x * plane.pixelStride, arrays[p][row * width + x])
            }
            c.queueInputBuffer(bufIndex, 0, spec.width * spec.height * 3 / 2, index * 1_000_000L / spec.fps, 0)
            drain!!.run(false)
            return true
        }

        override fun finish() {
            val c = codec ?: error("Кодек не открыт")
            c.queueInputBuffer(input(), 0, 0, spec.frames * 1_000_000L / spec.fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val d = drain!!
            d.run(true)
            check(d.started) { "Кодек не выдал ни одного кадра" }
            muxer?.stop()
        }

        override fun release(contextAlive: Boolean) {
            runCatching { hooks.end() }
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { muxer?.release() }
            codec = null; muxer = null
        }
    }

    /** Забирает готовые буферы кодека и пишет их в MP4. */
    internal class Drain(private val codec: MediaCodec, private val muxer: MediaMuxer, private val cancel: AtomicBoolean) {
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
