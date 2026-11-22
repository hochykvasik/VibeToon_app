package com.vibetoon.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.vibetoon.domain.project.Clip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Звук проекта. Файлы один раз декодируются в PCM (в фоне), воспроизведение идёт потоком через
 * AudioTrack: каждая дорожка играет свой клип с учётом начала на таймлайне, скорости, реверса и
 * смещения (после разрезания). Скорость меняет и высоту звука (ресемплинг), реверс играет задом наперёд.
 * Вызывать play/pause с главного потока.
 */
class AudioEngine {
    class Pcm(val data: ShortArray, val sampleRate: Int, val channels: Int) {
        val frames get() = data.size / channels
    }

    private val files = HashMap<Int, File>()
    private val pcm = ConcurrentHashMap<File, Pcm>()
    private val decoding = ConcurrentHashMap.newKeySet<File>()
    private val executor = Executors.newSingleThreadExecutor()
    private val sessions = HashMap<Int, Session>()
    private val peaks = ConcurrentHashMap<File, FloatArray>()
    /** Вызывается из фонового потока, когда файл декодирован и пики громкости готовы. */
    @Volatile var onDecoded: (() -> Unit)? = null

    /** Пики громкости файла (для волновой формы на таймлайне) или null, пока файл не декодирован. */
    fun peaksFor(f: File): FloatArray? = peaks[f]

    /** Дорожки проекта (id -> файл); новые файлы декодируются в фоне. */
    fun setSources(map: Map<Int, File>) {
        for (id in sessions.keys.toList()) if (id !in map || files[id] != map[id]) sessions.remove(id)?.halt()
        files.clear()
        files.putAll(map)
        for (f in map.values) ensureDecoded(f)
    }

    private fun ensureDecoded(f: File) {
        if (pcm.containsKey(f) || !decoding.add(f)) return
        executor.execute {
            try {
                // волна из кэша .wave появляется сразу, пока файл ещё декодируется для воспроизведения
                val cacheFile = File(f.path + ".wave")
                if (!peaks.containsKey(f)) {
                    runCatching { if (cacheFile.exists()) com.vibetoon.domain.project.WaveCache.decode(cacheFile.readBytes(), f.length(), f.lastModified()) else null }
                        .getOrNull()?.let { peaks[f] = it; onDecoded?.invoke() }
                }
                val d = decode(f)
                if (!peaks.containsKey(f)) {
                    val pk = com.vibetoon.domain.project.WaveformMath.peaks(d.data, d.channels, d.sampleRate)
                    peaks[f] = pk
                    runCatching { cacheFile.writeBytes(com.vibetoon.domain.project.WaveCache.encode(pk, f.length(), f.lastModified())) }
                }
                pcm[f] = d
                onDecoded?.invoke()
                Log.i(TAG, "decoded ${f.name}: ${pcm[f]!!.frames} frames @ ${pcm[f]!!.sampleRate} Hz")
            } catch (t: Throwable) {
                Log.w(TAG, "decode failed for ${f.name}: ${t.message}")
            } finally {
                decoding.remove(f)
            }
        }
    }

    /** Запускает все дорожки с кадра [frame] проекта. [clipOf] - клип дорожки на таймлайне. */
    /** Ключи громкости дорожек (id -> ключи); выставляется из проекта. */
    @Volatile var volumes: Map<Int, List<com.vibetoon.domain.project.VolKey>> = emptyMap()

    fun play(frame: Int, fps: Int, clipOf: (Int) -> Clip) {
        stopAll()
        for ((id, f) in files) {
            val p = pcm[f]
            if (p == null) { ensureDecoded(f); Log.w(TAG, "track $id not decoded yet"); continue }
            val clip = clipOf(id)
            if (clip.freeze || frame >= clip.end) continue
            sessions[id] = Session(id, p, clip, frame, fps, vol = volumes[id].orEmpty()).also { it.start() }
        }
    }

    /** Скраббинг: короткий фрагмент (~90 мс) каждой дорожки на кадре [frame]; играет всегда вперёд от позиции плейхеда, в любом направлении протяжки. */
    fun scrub(frame: Int, fps: Int, clipOf: (Int) -> Clip) {
        stopAll()
        val src = ArrayList<ScrubSrc>()
        for ((id, f) in files) {
            val p = pcm[f] ?: continue
            val clip = clipOf(id)
            if (clip.freeze || frame < clip.start || frame >= clip.end) continue
            src += ScrubSrc(p, clip, volumes[id].orEmpty())
        }
        if (src.isEmpty()) return
        val s = scrubber ?: Scrubber().also { scrubber = it; it.start() }
        s.post(ScrubRequest(src, frame, fps))
    }

    private class ScrubSrc(val p: Pcm, val clip: Clip, val vol: List<com.vibetoon.domain.project.VolKey> = emptyList())
    private class ScrubRequest(val src: List<ScrubSrc>, val frame: Int, val fps: Int)
    @Volatile private var scrubber: Scrubber? = null

    /** Один постоянный поток и один AudioTrack для скраббинга: новый фрагмент заменяет прежний без создания потоков и щелчков (fade на краях). */
    private inner class Scrubber : Thread("vt-scrub") {
        private val lock = Object()
        private var pending: ScrubRequest? = null
        @Volatile private var alive = true

        fun post(r: ScrubRequest) = synchronized(lock) { pending = r; lock.notifyAll() }
        fun quit() { alive = false; synchronized(lock) { lock.notifyAll() } }

        override fun run() {
            val minBuf = AudioTrack.getMinBufferSize(OUT_SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(max(minBuf, OUT_SR * 4 / 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Throwable) {
                Log.w(TAG, "scrub AudioTrack failed: ${e.message}")
                return
            }
            try {
                t.play()
                while (alive) {
                    val r = synchronized(lock) {
                        while (alive && pending == null) lock.wait()
                        pending.also { pending = null }
                    } ?: break
                    val data = renderScrub(r)
                    runCatching { t.pause(); t.flush(); t.play() }
                    t.write(data, 0, data.size)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "scrub error: ${e.message}")
            } finally {
                runCatching { t.stop() }
                runCatching { t.release() }
            }
        }
    }

    /** Стерео 44.1 кГц: сумма дорожек, ресемплинг по клипу (скорость, реверс, смещение), fade-in/out по [FADE_MS]. */
    /**
     * Сведение всех дорожек проекта в стерео 44.1 кГц на всю длительность ([frames] кадров при [fps]): клипы, скорость, реверс и смещения как при воспроизведении.
     * null - дорожек нет или они ещё декодируются.
     */
    fun mixdown(frames: Int, fps: Int, clipOf: (Int) -> Clip): ShortArray? {
        val src = files.mapNotNull { (id, f) -> pcm[f]?.let { ScrubSrc(it, clipOf(id), volumes[id].orEmpty()) } }
        if (src.isEmpty()) return null
        val n = (frames.toLong() * OUT_SR / fps).toInt()
        val out = ShortArray(n * 2)
        for (k in 0 until n) {
            var l = 0; var rr = 0
            val t = k.toDouble() * fps / OUT_SR
            for (s in src) {
                val p = s.p; val clip = s.clip; val ch = p.channels
                if (t < clip.start || t >= clip.end) continue
                val rel = t - clip.start
                val srcFrame = if (!clip.reverse) clip.offset + rel * clip.speed else clip.offset + clip.length * clip.speed - rel * clip.speed
                val pos = srcFrame / fps * p.sampleRate
                val i0 = Math.floor(pos).toInt()
                if (pos < 0 || i0 + 1 >= p.frames) continue
                val frac = (pos - i0).toFloat()
                fun at(c: Int): Int { val a = p.data[i0 * ch + c].toInt(); val b = p.data[(i0 + 1) * ch + c].toInt(); return (a + (b - a) * frac).toInt() }
                val gv = if (s.vol.isEmpty()) 1f else com.vibetoon.domain.project.VolumeMath.at(s.vol, t)
                val left = (at(0) * gv).toInt()
                l += left
                rr += if (ch > 1) (at(1) * gv).toInt() else left
            }
            out[k * 2] = l.coerceIn(-32768, 32767).toShort()
            out[k * 2 + 1] = rr.coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun mixSampleRate() = OUT_SR

    private fun renderScrub(r: ScrubRequest): ShortArray {
        val n = OUT_SR * SCRUB_MS / 1000
        val fadeN = max(1, OUT_SR * FADE_MS / 1000)
        val out = ShortArray(n * 2)
        for (k in 0 until n) {
            var l = 0; var rr = 0
            for (s in r.src) {
                val p = s.p; val clip = s.clip; val ch = p.channels
                val rel = k.toDouble() * r.fps / OUT_SR + (r.frame - clip.start)
                val srcFrame = if (!clip.reverse) clip.offset + rel * clip.speed else clip.offset + clip.length * clip.speed - rel * clip.speed
                val pos = srcFrame / r.fps * p.sampleRate
                val i0 = Math.floor(pos).toInt()
                if (pos < 0 || i0 + 1 >= p.frames) continue
                val frac = (pos - i0).toFloat()
                fun at(c: Int): Int { val a = p.data[i0 * ch + c].toInt(); val b = p.data[(i0 + 1) * ch + c].toInt(); return (a + (b - a) * frac).toInt() }
                val gv = if (s.vol.isEmpty()) 1f else com.vibetoon.domain.project.VolumeMath.at(s.vol, r.frame + k.toDouble() * r.fps / OUT_SR)
                val left = (at(0) * gv).toInt()
                l += left
                rr += if (ch > 1) (at(1) * gv).toInt() else left
            }
            val g = min(1f, min((k + 1f) / fadeN, (n - k).toFloat() / fadeN))
            out[k * 2] = (l * g).toInt().coerceIn(-32768, 32767).toShort()
            out[k * 2 + 1] = (rr * g).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun pause() = stopAll()

    private fun stopAll() {
        for (s in sessions.values) s.halt()
        sessions.clear()
    }

    fun release() {
        stopAll()
        scrubber?.quit(); scrubber = null
        files.clear()
        executor.shutdownNow()
    }

    /** Один поток воспроизведения одной дорожки. */
    private class Session(val trackId: Int, val p: Pcm, val clip: Clip, val startFrame: Int, val fps: Int, val limitMs: Int = 0, val vol: List<com.vibetoon.domain.project.VolKey> = emptyList()) : Thread("vt-audio-$trackId") {
        @Volatile private var stopped = false
        @Volatile private var track: AudioTrack? = null

        fun halt() {
            stopped = true
            track?.let { runCatching { it.pause(); it.flush() } }
        }

        override fun run() {
            val ch = p.channels
            val sr = p.sampleRate
            val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_16BIT)
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
                    .setBufferSizeInBytes(max(minBuf, sr / 2 * ch * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Throwable) {
                Log.w(TAG, "AudioTrack failed: ${e.message}")
                return
            }
            track = t
            try {
                val tStart = max(startFrame, clip.start)
                var total = (clip.end - tStart).toLong() * sr / fps          // выходных кадров до конца клипа
                if (limitMs > 0) total = min(total, sr.toLong() * limitMs / 1000)   // скраббинг: только короткий фрагмент
                val chunk = sr / 4
                val out = ShortArray(chunk * ch)
                if (stopped) return
                t.play()
                Log.i(TAG, "track $trackId start frame=$startFrame clip=${clip.start}..${clip.end} speed=${clip.speed} rev=${clip.reverse}")
                // клип начинается позже playhead: сначала тишина
                var lead = (clip.start - startFrame).coerceAtLeast(0).toLong() * sr / fps
                java.util.Arrays.fill(out, 0)
                while (!stopped && lead > 0) {
                    val m = min(chunk.toLong(), lead).toInt()
                    t.write(out, 0, m * ch)
                    lead -= m
                }
                var i = 0L
                var gain = 1f
                val speed = clip.speed.toDouble()
                val srcTotal = p.frames.toDouble()
                while (!stopped && i < total) {
                    val m = min(chunk.toLong(), total - i).toInt()
                    for (k in 0 until m) {
                        if (vol.isNotEmpty() && k % 64 == 0) gain = com.vibetoon.domain.project.VolumeMath.at(vol, tStart + (i + k).toDouble() * fps / sr)
                        val rel = (i + k).toDouble() * fps / sr + (tStart - clip.start)      // кадры от начала клипа
                        val srcFrame = if (!clip.reverse) clip.offset + rel * speed else clip.offset + clip.length * speed - rel * speed
                        val pos = srcFrame / fps * sr
                        val i0 = Math.floor(pos).toInt()
                        val frac = (pos - i0).toFloat()
                        for (c in 0 until ch) {
                            out[k * ch + c] = if (pos < 0 || pos >= srcTotal - 1) 0 else {
                                val a = p.data[i0 * ch + c].toInt()
                                val b = p.data[(i0 + 1) * ch + c].toInt()
                                ((a + (b - a) * frac) * gain).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        }
                    }
                    t.write(out, 0, m * ch)
                    i += m
                }
            } catch (e: Throwable) {
                Log.w(TAG, "playback error: ${e.message}")
            } finally {
                runCatching { t.stop() }
                runCatching { t.release() }
            }
        }
    }

    // ---------------------------------------------------------------- декодирование в PCM

    private fun decode(file: File): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        try {
            var idx = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; break }
            }
            if (idx < 0) error("нет аудиодорожки")
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val maxShorts = MAX_SECONDS.toLong() * sr * ch
            val chunks = ArrayList<ShortArray>()
            var count = 0L

            fun addBytes(buf: ByteBuffer, size: Int) {
                val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val arr = ShortArray(min(size / 2, sb.remaining()))
                sb.get(arr)
                chunks += arr
                count += arr.size
            }

            if (mime == "audio/raw") {
                // WAV: данные уже PCM
                val buf = ByteBuffer.allocate(1 shl 16)
                while (count < maxShorts) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    buf.position(0); buf.limit(n)
                    addBytes(buf.slice(), n)
                    buf.clear()
                    ex.advance()
                }
            } else {
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()
                try {
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    while (!outputDone && count < maxShorts) {
                        if (!inputDone) {
                            val ii = codec.dequeueInputBuffer(10_000)
                            if (ii >= 0) {
                                val ib = codec.getInputBuffer(ii)!!
                                val n = ex.readSampleData(ib, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            }
                        }
                        val oi = codec.dequeueOutputBuffer(info, 10_000)
                        when {
                            oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val of = codec.outputFormat
                                sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            oi >= 0 -> {
                                val ob = codec.getOutputBuffer(oi)
                                if (ob != null && info.size > 0) {
                                    ob.position(info.offset); ob.limit(info.offset + info.size)
                                    addBytes(ob.slice(), info.size)
                                }
                                codec.releaseOutputBuffer(oi, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            }
                        }
                    }
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            }
            val all = ShortArray(count.toInt())
            var pos = 0
            for (c in chunks) { System.arraycopy(c, 0, all, pos, c.size); pos += c.size }
            // нужен стерео или моно: больше двух каналов не поддерживаем
            if (ch > 2) error("каналов больше двух")
            return Pcm(all, sr, ch)
        } finally {
            ex.release()
        }
    }

    private companion object {
        const val TAG = "VT/Audio"
        const val SCRUB_MS = 90
        const val FADE_MS = 8
        const val OUT_SR = 44100
        const val MAX_SECONDS = 300   // дольше 5 минут не декодируем (память)
    }
}
