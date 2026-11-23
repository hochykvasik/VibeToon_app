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
    private val executor = Executors.newSingleThreadExecutor()
    private val preparation = com.vibetoon.domain.project.PreparationRegistry<File>(executor)
    private val sessions = HashMap<Int, Session>()
    private val peaks = ConcurrentHashMap<File, FloatArray>()
    /** Вызывается из фонового потока, когда файл декодирован и пики громкости готовы. */
    @Volatile var onDecoded: (() -> Unit)? = null

    /** Пики громкости файла (для волновой формы на таймлайне) или null, пока файл не декодирован. */
    fun peaksFor(f: File): FloatArray? = peaks[f]

    /** Пакет 11: файлы без звуковой дорожки (видео без звука) - больше не декодируются и не звучат. */
    private val noAudio = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    fun hasNoAudio(f: File): Boolean = f in noAudio
    /** Дорожки с выключенным звуком (id). */
    @Volatile var muted: Set<Int> = emptySet()

    private class NoAudioTrack : IllegalStateException("нет аудиодорожки")

    /** Дорожки проекта (id -> файл); новые файлы декодируются в фоне. */
    fun setSources(map: Map<Int, File>) {
        for (id in sessions.keys.toList()) if (id !in map || files[id] != map[id]) sessions.remove(id)?.halt()
        files.clear()
        files.putAll(map)
        for (f in map.values) if (f !in noAudio) ensureDecoded(f)
    }

    /** Завершается только после публикации PCM для scrub/play, а не после чтения кэша волны. */
    suspend fun awaitScrubReady(file: File): Result<Unit> = ensureDecoded(file).await()

    private fun ensureDecoded(f: File) = preparation.request(f, { pcm.containsKey(f) }) {
        try {
            val cacheFile = File(f.path + ".wave")
            if (!peaks.containsKey(f)) {
                runCatching { if (cacheFile.exists()) com.vibetoon.domain.project.WaveCache.decode(cacheFile.readBytes(), f.length(), f.lastModified()) else null }
                    .getOrNull()?.let { peaks[f] = it; onDecoded?.invoke() }
            }
            val d = decode(f)
            check(d.frames > 0) { "Аудио не содержит доступных сэмплов" }
            if (!peaks.containsKey(f)) {
                val pk = com.vibetoon.domain.project.WaveformMath.peaks(d.data, d.channels, d.sampleRate)
                peaks[f] = pk
                runCatching { cacheFile.writeBytes(com.vibetoon.domain.project.WaveCache.encode(pk, f.length(), f.lastModified())) }
            }
            pcm[f] = d
            onDecoded?.invoke()
            Log.i(TAG, "decoded ${f.name}: ${d.frames} frames @ ${d.sampleRate} Hz")
        } catch (t: Throwable) {
            if (t is NoAudioTrack) noAudio += f
            Log.w(TAG, "decode failed for ${f.name}: ${t.message}")
            throw t
        }
    }

    /** Запускает все дорожки с кадра [frame] проекта. [clipOf] - клип дорожки на таймлайне. */
    /** Ключи громкости дорожек (id -> ключи); выставляется из проекта. */
    @Volatile var volumes: Map<Int, List<com.vibetoon.domain.project.VolKey>> = emptyMap()

    fun play(frame: Int, fps: Int, clipOf: (Int) -> Clip) {
        cancelScrub()
        stopAll()
        for ((id, f) in files) {
            if (id in muted || f in noAudio) continue
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
            if (id in muted) continue
            val p = pcm[f] ?: continue
            val clip = clipOf(id)
            if (clip.freeze || frame < clip.start || frame >= clip.end) continue
            src += ScrubSrc(p, clip, volumes[id].orEmpty())
        }
        if (src.isEmpty()) { cancelScrub(); return }
        val s = scrubber ?: Scrubber().also { scrubber = it; it.start() }
        s.post(ScrubRequest(src, frame, fps))
    }

    private class ScrubSrc(val p: Pcm, val clip: Clip, val vol: List<com.vibetoon.domain.project.VolKey> = emptyList())
    private class ScrubRequest(val src: List<ScrubSrc>, val frame: Int, val fps: Int)
    @Volatile private var scrubber: Scrubber? = null

    /** Очистка и отмена ожидающих фрагментов, в том числе при запуске Play. */
    fun cancelScrub() { scrubber?.cancel() }

    /** Постоянный поток: очередь хранит только последнюю позицию. */
    private inner class Scrubber : Thread("vt-scrub") {
        private val lock = Object()
        private var pending: Pair<Long, ScrubRequest>? = null
        private var generation = 0L
        private var track: AudioTrack? = null
        @Volatile private var alive = true

        fun post(r: ScrubRequest) = synchronized(lock) {
            generation++
            pending = generation to r
            lock.notifyAll()
        }
        fun cancel() = synchronized(lock) {
            generation++
            pending = null
            track?.let { t -> runCatching { t.pause(); t.flush() } }
        }
        fun quit() { cancel(); alive = false; synchronized(lock) { lock.notifyAll() } }

        override fun run() {
            val minBuf = AudioTrack.getMinBufferSize(OUT_SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(max(minBuf, OUT_SR * SCRUB_MS / 1000 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Throwable) {
                Log.w(TAG, "scrub AudioTrack failed: ${e.message}")
                return
            }
            try {
                synchronized(lock) { track = t }
                // На Android до 12 стартовый порог потокового AudioTrack равен размеру буфера.
                // Одних 90 мс недостаточно при большом minBuffer: дополняем фрагмент тишиной.
                val bufferSamples = t.bufferSizeInFrames * 2
                if (android.os.Build.VERSION.SDK_INT >= 31) t.setStartThresholdInFrames(1)
                while (alive) {
                    val request = synchronized(lock) {
                        while (alive && pending == null) lock.wait()
                        pending.also { pending = null }
                    } ?: break
                    val data = renderScrub(request.second)
                    val padded = com.vibetoon.domain.project.ScrubAudio.padForStart(data, bufferSamples / 2)
                    synchronized(lock) {
                        if (!alive || request.first != generation) return@synchronized
                        runCatching {
                            t.pause(); t.flush()
                            val written = t.write(padded, 0, padded.size, AudioTrack.WRITE_NON_BLOCKING)
                            if (written > 0) t.play() else Log.w(TAG, "scrub write failed: $written")
                        }.onFailure { Log.w(TAG, "scrub playback failed", it) }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "scrub error: ${e.message}")
            } finally {
                synchronized(lock) { track = null }
                runCatching { t.stop() }
                runCatching { t.release() }
            }
        }
    }

    /**
     * Пакет 12: источники сведения для экспорта (MP4 и WAV) - снимок PCM, клипов и ключей громкости;
     * дорожки «Без звука» и файлы без звуковой дорожки не входят. Пусто - звука нет или он не декодирован.
     */
    fun exportSources(clipOf: (Int) -> Clip, only: Set<Int>? = null): List<com.vibetoon.domain.project.ScrubAudio.Source> =
        files.mapNotNull { (id, f) ->
            if (id in muted || f in noAudio || (only != null && id !in only)) null
            else pcm[f]?.let { p -> com.vibetoon.domain.project.ScrubAudio.Source(p.data, p.sampleRate, p.channels, clipOf(id), volumes[id].orEmpty()) }
        }

    /**
     * Пакет 14: перед экспортом движок сверяется с проектом - дорожки, о которых он не знает (или знает со старым
     * файлом), добавляются и декодируются; иначе окно видело звук, а сведение - нет.
     */
    fun ensureSources(map: Map<Int, File>) {
        val merged = HashMap(files).apply { putAll(map) }   // только добавляем: то, что звучит при Play, не выкидываем
        if (merged != files) setSources(merged)
    }

    /** Дорожки, которые сейчас звучат при Play и скраббинге (id -> файл). */
    fun sourceFiles(): Map<Int, File> = HashMap(files)

    /** Ждёт PCM именно этих файлов (не дольше [timeoutMs]); файлы без звука и ошибки декодирования пропускаются. */
    suspend fun awaitDecoded(list: Collection<File>, timeoutMs: Long = 120_000) {
        val todo = list.filter { it !in noAudio && !pcm.containsKey(it) }
        if (todo.isEmpty()) return
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { for (f in todo) runCatching { awaitScrubReady(f) } }
    }

    /** Длительность декодированного файла (мс) или 0: у части MP3 (VBR без заголовка) метаданные длительности пустые. */
    fun decodedDurationMs(f: File): Long = pcm[f]?.let { it.frames.toLong() * 1000 / it.sampleRate.coerceAtLeast(1) } ?: 0L

    /** PCM файла готов (для сообщения, какой дорожке не хватило декодирования). */
    fun isDecoded(f: File): Boolean = pcm.containsKey(f)

    /** Ждёт декодирования всех звучащих дорожек (не дольше [timeoutMs]); ошибки отдельных файлов не прерывают экспорт. */
    suspend fun awaitAllDecoded(timeoutMs: Long = 60_000) {
        val list = files.filter { (id, f) -> id !in muted && f !in noAudio && !pcm.containsKey(f) }.values.toList()
        if (list.isEmpty()) return
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { for (f in list) runCatching { awaitScrubReady(f) } }
    }

    /** Сведение отрезка проекта для WAV: [frames] кадров проекта при [fps] с кадра [startFrame]. null - звука нет. */
    fun mixdown(frames: Int, fps: Int, clipOf: (Int) -> Clip, startFrame: Int = 0): ShortArray? {
        val src = exportSources(clipOf)
        if (src.isEmpty()) return null
        val n = com.vibetoon.domain.export.AudioMix.sampleCount(frames, fps, OUT_SR)
        return com.vibetoon.domain.export.AudioMix.renderAll(src, startFrame, fps, OUT_SR, n)
    }

    fun mixSampleRate() = OUT_SR

    private fun renderScrub(r: ScrubRequest): ShortArray = com.vibetoon.domain.project.ScrubAudio.render(
        r.src.map { s -> com.vibetoon.domain.project.ScrubAudio.Source(s.p.data, s.p.sampleRate, s.p.channels, s.clip, s.vol) },
        r.frame, r.fps, OUT_SR, SCRUB_MS, FADE_MS
    )

    fun pause() { cancelScrub(); stopAll() }

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
            if (idx < 0) throw NoAudioTrack()
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
