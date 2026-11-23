package com.vibetoon.domain.export

import com.vibetoon.domain.project.ScrubAudio
import com.vibetoon.domain.project.VolumeMath
import kotlin.math.min

/**
 * Пакет 12: сведение звука для экспорта (MP4 и WAV) без Android-зависимостей.
 *
 * Время звука берётся тем же способом, что и картинка видео: сэмпл k готового файла - это момент
 * startFrame + k * projectFps / rate кадров проекта (кадр видео i показывает кадр проекта
 * startFrame + floor(i * projectFps / outFps)), поэтому звук не уплывает от картинки ни при другом FPS
 * экспорта, ни при экспорте отдельной сцены. Правила клипа - как у воспроизведения: начало и конец на
 * таймлайне, скорость (меняет и высоту), реверс, смещение после разрезания, стоп-кадр молчит, ключи
 * громкости линейно. Дорожки «Без звука» в список источников не попадают (их отбрасывает вызывающий).
 */
object AudioMix {
    /** Короткий fade на краях файла (мс): обрезка посреди звука (экспорт сцены) без щелчка. */
    const val EDGE_FADE_MS = 4

    /** Сколько стерео-сэмплов при частоте [rate] занимает видео из [outFrames] кадров при [outFps]. */
    fun sampleCount(outFrames: Int, outFps: Int, rate: Int): Int =
        (outFrames.toLong() * rate / outFps.coerceAtLeast(1)).toInt().coerceAtLeast(0)

    /** Время (мкс) сэмпла [index] при частоте [rate] - метка буфера для кодека. */
    fun ptsUs(index: Long, rate: Int): Long = index * 1_000_000L / rate.coerceAtLeast(1)

    /**
     * Кусок сведения: стерео-сэмплы [from, from + count) файла длиной [total] сэмплов.
     * [startFrame] - кадр проекта, с которого начинается файл (начало сцены или 0), [fps] - частота проекта.
     */
    fun render(sources: List<ScrubAudio.Source>, startFrame: Int, fps: Int, rate: Int, from: Int, count: Int, total: Int): ShortArray {
        require(fps > 0 && rate > 0 && count >= 0)
        val out = ShortArray(count * 2)
        if (sources.isEmpty()) return out
        val fade = (rate * EDGE_FADE_MS / 1000).coerceAtLeast(1)
        for (j in 0 until count) {
            val k = from + j
            val time = startFrame + k.toDouble() * fps / rate
            var left = 0f; var right = 0f
            for (s in sources) {
                if (s.channels <= 0 || s.sampleRate <= 0) continue
                val c = s.clip
                val rel = time - c.start
                if (c.freeze || rel < 0 || rel >= c.length) continue
                val srcFrame = if (!c.reverse) c.offset + rel * c.speed else c.offset + c.length * c.speed - rel * c.speed
                val pos = srcFrame / fps * s.sampleRate
                val i = kotlin.math.floor(pos).toInt()
                if (pos < 0 || i + 1 >= s.data.size / s.channels) continue
                val frac = (pos - i).toFloat()
                val gain = if (s.volume.isEmpty()) 1f else VolumeMath.at(s.volume, time)
                if (gain <= 0f) continue
                fun sample(ch: Int): Float {
                    val a = s.data[i * s.channels + ch].toFloat()
                    return a + (s.data[(i + 1) * s.channels + ch] - a) * frac
                }
                val l = sample(0) * gain
                left += l
                right += if (s.channels > 1) sample(1) * gain else l
            }
            val g = if (total <= 0) 1f else min(1f, min((k + 1f) / fade, (total - k).toFloat() / fade)).coerceAtLeast(0f)
            out[j * 2] = (left * g).toInt().coerceIn(-32768, 32767).toShort()
            out[j * 2 + 1] = (right * g).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Всё сведение одним массивом (WAV). */
    fun renderAll(sources: List<ScrubAudio.Source>, startFrame: Int, fps: Int, rate: Int, total: Int): ShortArray =
        render(sources, startFrame, fps, rate, 0, total, total)

    /** Звучит ли хоть одна дорожка в отрезке [startFrame, startFrame + frames): иначе дорожку в MP4 не пишем. */
    fun audibleIn(sources: List<ScrubAudio.Source>, startFrame: Int, frames: Int): Boolean = sources.any { s ->
        val c = s.clip
        !c.freeze && c.end > startFrame && c.start < startFrame + frames && (s.volume.isEmpty() || s.volume.any { it.value > 0f })
    }

    /** Пары (время, источник) в порядке времени: как переплетать сэмплы видео и звука при сборке MP4. */
    fun interleave(videoUs: List<Long>, audioUs: List<Long>): List<Pair<Boolean, Int>> {
        val out = ArrayList<Pair<Boolean, Int>>(videoUs.size + audioUs.size)
        var a = 0
        for ((v, t) in videoUs.withIndex()) {
            while (a < audioUs.size && audioUs[a] <= t) out += false to a++
            out += true to v
        }
        while (a < audioUs.size) out += false to a++
        return out
    }
}
