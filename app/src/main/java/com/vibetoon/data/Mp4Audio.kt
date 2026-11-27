package com.vibetoon.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.vibetoon.domain.export.AudioMix
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Пакет 12: звуковая дорожка AAC в готовый MP4.
 *
 * Видео пишется GL-экспортёром как раньше (без изменений в его пути); затем сведение кодируется в AAC-LC
 * (стерео 44,1 кГц, 192 кбит/с) кусками - весь PCM в памяти не держится - и вместе с уже сжатыми кадрами
 * видео (копируются без перекодирования) собирается в новый MP4, который заменяет исходный.
 * Любая ошибка оставляет исходное видео без звука нетронутым.
 */
object Mp4Audio {
    private const val TAG = "VT/ExportAudio"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    const val BITRATE = 192_000
    private const val CHUNK = 1024   // стерео-сэмплов за раз: ровно один кадр AAC

    private class Packet(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /**
     * Кодирует [total] стерео-сэмплов, которые выдаёт [chunk] (from, count), и вписывает их в [video].
     * [onProgress] - доля 0..1.
     */
    fun addTrack(video: File, rate: Int, total: Int, chunk: (Int, Int) -> ShortArray, cancel: AtomicBoolean, onProgress: (Float) -> Unit = {}): Result<Unit> {
        if (total <= 0) return Result.failure(IllegalArgumentException("Пустой звук"))
        val tmp = File(video.path + ".audio.tmp")
        return try {
            val (format, packets) = encode(rate, total, chunk, cancel, onProgress)
            check(packets.isNotEmpty()) { "Кодек звука не выдал данных" }
            mux(video, tmp, format, packets, cancel)
            check(tmp.length() > 0) { "Пустой файл после сборки" }
            if (!video.delete() || !tmp.renameTo(video)) {
                tmp.copyTo(video, overwrite = true); tmp.delete()
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "audio track failed; video stays silent", t)
            tmp.delete()
            Result.failure(t)
        }
    }

    private fun encode(rate: Int, total: Int, chunk: (Int, Int) -> ShortArray, cancel: AtomicBoolean, onProgress: (Float) -> Unit): Pair<MediaFormat, List<Packet>> {
        val fmt = MediaFormat.createAudioFormat(MIME, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2 * 2 * 8)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        val packets = ArrayList<Packet>()
        var outFormat: MediaFormat? = null
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var next = 0
            var inputDone = false
            var idleMs = 0
            while (true) {
                check(!cancel.get()) { "Отменено" }
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear()
                        val cap = (ib.capacity() / 4).coerceAtLeast(1)
                        val n = minOf(CHUNK, cap, total - next)
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, AudioMix.ptsUs(total.toLong(), rate), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pcm = chunk(next, n)
                            ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, n * 2)
                            codec.queueInputBuffer(ii, 0, n * 4, AudioMix.ptsUs(next.toLong(), rate), 0)
                            next += n
                            if (next % (CHUNK * 64) < CHUNK) onProgress(next.toFloat() / total)
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) { idleMs += 10; check(idleMs < 10_000) { "Кодек звука завис" } }
                    }
                    oi >= 0 -> {
                        idleMs = 0
                        val ob = codec.getOutputBuffer(oi)
                        if (ob != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); ob.get(bytes)
                            packets += Packet(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        onProgress(1f)
        return (outFormat ?: error("Кодек звука не сообщил формат")) to packets
    }

    private fun mux(video: File, out: File, audioFormat: MediaFormat, packets: List<Packet>, cancel: AtomicBoolean) {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(video.absolutePath)
            val vIdx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("В видео нет дорожки картинки")
            val vFmt = ex.getTrackFormat(vIdx)
            ex.selectTrack(vIdx)
            out.delete()
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val vt = m.addTrack(vFmt)
            val at = m.addTrack(audioFormat)
            m.start(); started = true
            val w = runCatching { vFmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(1920)
            val h = runCatching { vFmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(1080)
            val maxIn = runCatching { vFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
            val buf = ByteBuffer.allocateDirect(maxOf(maxIn, w * h * 3 / 2, 1 shl 20))
            val info = MediaCodec.BufferInfo()
            var a = 0
            fun writeAudioUntil(us: Long) {
                while (a < packets.size && packets[a].ptsUs <= us) {
                    val p = packets[a++]
                    info.set(0, p.data.size, p.ptsUs, p.flags)
                    m.writeSampleData(at, ByteBuffer.wrap(p.data), info)
                }
            }
            while (true) {
                check(!cancel.get()) { "Отменено" }
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTime
                writeAudioUntil(t)
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, t, flags)
                m.writeSampleData(vt, buf, info)
                ex.advance()
            }
            writeAudioUntil(Long.MAX_VALUE)
            m.stop(); started = false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            ex.release()
        }
    }
}
