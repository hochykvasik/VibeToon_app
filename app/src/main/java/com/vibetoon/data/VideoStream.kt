package com.vibetoon.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.vibetoon.domain.perf.VideoYuv
import java.io.Closeable
import java.io.File

/**
 * Спринт 46: последовательный декодер видео-слоя (MediaExtractor + MediaCodec). Кадры идут по файлу подряд: следующий
 * кадр проекта - это 1-2 новых кадра декодера, а не поиск от опорного кадра и декодирование всей группы кадров
 * (как у MediaMetadataRetriever.getScaledFrameAtTime на каждый кадр - отсюда было «слайд-шоу» при Play). Назад и
 * далеко вперёд - поиск ближайшего опорного кадра и декодирование до нужного момента.
 *
 * Не потокобезопасен: у каждого слоя свой экземпляр, вызовы - под замком слоя (VideoFrames) или экспорта.
 */
class VideoStream private constructor(private val file: File) : Closeable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var inputDone = false
    private var outputDone = false
    /** Момент (мкс) последнего кадра, вышедшего из декодера; MIN - после поиска кадров ещё не было. */
    private var lastPtsUs = Long.MIN_VALUE
    private var frameUs = 33_333L
    private var rotation = 0
    private var standard = 0
    private var range = 0
    private var matrix: VideoYuv.Matrix? = null
    private val info = MediaCodec.BufferInfo()
    private var yBuf = ByteArray(0); private var uBuf = ByteArray(0); private var vBuf = ByteArray(0)

    /** Последний декодированный момент (мкс) или MIN. */
    val positionUs: Long get() = lastPtsUs

    private fun open(): Boolean {
        extractor.setDataSource(file.absolutePath)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; fmt = f; break }
        }
        if (track < 0 || fmt == null) return false
        extractor.selectTrack(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
        if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
            ?.takeIf { it in 1..240 }?.let { frameUs = 1_000_000L / it }
        if (Build.VERSION.SDK_INT >= 23 && fmt.containsKey(MediaFormat.KEY_ROTATION)) rotation = runCatching { fmt.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
        else if (fmt.containsKey("rotation-degrees")) rotation = runCatching { fmt.getInteger("rotation-degrees") }.getOrDefault(0)
        readColor(fmt)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val c = MediaCodec.createDecoderByType(mime)
        try { c.configure(fmt, null, null, 0); c.start() } catch (e: Exception) { runCatching { c.release() }; throw e }
        codec = c
        return true
    }

    private fun readColor(f: MediaFormat) {
        if (Build.VERSION.SDK_INT < 24) return
        if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD)) standard = runCatching { f.getInteger(MediaFormat.KEY_COLOR_STANDARD) }.getOrDefault(standard)
        if (f.containsKey(MediaFormat.KEY_COLOR_RANGE)) range = runCatching { f.getInteger(MediaFormat.KEY_COLOR_RANGE) }.getOrDefault(range)
        matrix = null
    }

    /** Момент последнего поиска (мкс): позиция потока, пока после поиска не вышло ни одного кадра. */
    private var seekUs = 0L

    private fun seek(us: Long) {
        val c = codec ?: return
        seekUs = us
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        c.flush()
        inputDone = false; outputDone = false
        lastPtsUs = Long.MIN_VALUE
    }

    /**
     * Кадр на моменте [us] в [out] (ARGB [w]×[h], экранная ориентация). true - кадр записан; false - кадра нет (конец
     * файла, ошибка или не уложились в [budgetMs]) - слой остаётся с прежним кадром.
     */
    fun frame(us: Long, w: Int, h: Int, out: IntArray, smooth: Boolean, budgetMs: Long): Boolean {
        val c = codec ?: return false
        val tol = frameUs / 2
        val target = us.coerceAtLeast(0L)
        // назад или далеко вперёд (больше 2 с) - поиск опорного кадра; рядом вперёд - просто декодируем дальше
        // (после поиска, пока кадров ещё нет, позиция - момент поиска: недоделанный кадр не запускает поиск заново)
        val pos = if (lastPtsUs == Long.MIN_VALUE) seekUs else lastPtsUs
        if (target + tol < pos || target > pos + 2_000_000L) seek(target)
        val deadline = SystemClock.uptimeMillis() + budgetMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!inputDone) {
                val ii = c.dequeueInputBuffer(2_000)
                if (ii >= 0) {
                    val buf = c.getInputBuffer(ii)
                    val n = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                    if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { c.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            if (outputDone) return false
            val oi = c.dequeueOutputBuffer(info, 4_000)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readColor(c.outputFormat)
                oi >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos) outputDone = true
                    val empty = info.size <= 0 && eos
                    if (!empty) lastPtsUs = info.presentationTimeUs
                    if (!empty && (info.presentationTimeUs + tol >= target || eos)) {
                        val ok = convert(c, oi, w, h, out, smooth)
                        c.releaseOutputBuffer(oi, false)
                        return ok
                    }
                    c.releaseOutputBuffer(oi, false)   // промежуточный кадр (60 fps источник в проекте 30 fps)
                }
            }
        }
        return false
    }

    private fun convert(c: MediaCodec, index: Int, w: Int, h: Int, out: IntArray, smooth: Boolean): Boolean {
        val img = c.getOutputImage(index) ?: return false
        try {
            val planes = img.planes
            if (planes.size < 3) return false
            val crop = img.cropRect
            val sw = crop.width().coerceAtMost(img.width); val sh = crop.height().coerceAtMost(img.height)
            if (sw <= 0 || sh <= 0) return false
            fun copy(p: android.media.Image.Plane, cur: ByteArray): ByteArray {
                val b = p.buffer; b.rewind()
                val n = b.remaining()
                val dst = if (cur.size >= n) cur else ByteArray(n)
                b.get(dst, 0, n)
                return dst
            }
            yBuf = copy(planes[0], yBuf); uBuf = copy(planes[1], uBuf); vBuf = copy(planes[2], vBuf)
            val m = matrix ?: VideoYuv.matrix(standard, range, minOf(sw, sh)).also { matrix = it }
            VideoYuv.convert(yBuf, planes[0].rowStride, planes[0].pixelStride, uBuf, vBuf, planes[1].rowStride, planes[1].pixelStride,
                crop.left, crop.top, sw, sh, rotation, out, w, h, m, smooth)
            return true
        } catch (e: Exception) {
            Log.w("VT/Video", "frame convert failed: ${e.message}")
            return false
        } finally { img.close() }
    }

    override fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        runCatching { extractor.release() }
    }

    companion object {
        /** Открывает поток видео [file] или null (нет видеодорожки, декодер не поддерживает формат). */
        fun open(file: File): VideoStream? {
            val s = VideoStream(file)
            return try { if (s.open()) s else { s.close(); null } } catch (e: Exception) {
                Log.w("VT/Video", "stream ${file.name} unavailable: ${e.message}")
                s.close(); null
            }
        }
    }
}
