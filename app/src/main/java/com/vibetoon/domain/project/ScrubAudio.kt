package com.vibetoon.domain.project

import kotlin.math.max
import kotlin.math.min

/** Сведение короткого фрагмента без Android/AudioTrack; fade ограничивает щелчки на концах. */
object ScrubAudio {
    data class Source(val data: ShortArray, val sampleRate: Int, val channels: Int, val clip: Clip, val volume: List<VolKey> = emptyList())

    fun render(sources: List<Source>, frame: Int, fps: Int, outputRate: Int, durationMs: Int = 90, fadeMs: Int = 8): ShortArray {
        require(fps > 0 && outputRate > 0 && durationMs > 0)
        val n = outputRate * durationMs / 1000
        val fade = max(1, outputRate * fadeMs / 1000)
        val out = ShortArray(n * 2)
        for (k in 0 until n) {
            var left = 0; var right = 0
            val time = frame + k.toDouble() * fps / outputRate
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
                fun sample(ch: Int): Float {
                    val a = s.data[i * s.channels + ch].toFloat()
                    return a + (s.data[(i + 1) * s.channels + ch] - a) * frac
                }
                val gain = if (s.volume.isEmpty()) 1f else VolumeMath.at(s.volume, time)
                left += (sample(0) * gain).toInt()
                right += (sample(if (s.channels > 1) 1 else 0) * gain).toInt()
            }
            val g = min(1f, min((k + 1f) / fade, (n - k).toFloat() / fade))
            out[k * 2] = (left * g).toInt().coerceIn(-32768, 32767).toShort()
            out[k * 2 + 1] = (right * g).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun padForStart(data: ShortArray, bufferFrames: Int): ShortArray =
        if (bufferFrames > data.size / 2) data.copyOf(bufferFrames * 2) else data
}
