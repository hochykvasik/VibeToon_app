package com.vibetoon.domain.project

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Волновая форма аудио для таймлайна: пики громкости по корзинам [PER_SECOND] в секунду и выборка под клип. */
object WaveformMath {
    const val PER_SECOND = 100

    /** Пики громкости (0..1, нормировано по самому громкому месту) по корзинам в 1/[perSecond] секунды; каналы сводятся по максимуму. */
    fun peaks(data: ShortArray, channels: Int, sampleRate: Int, perSecond: Int = PER_SECOND): FloatArray {
        val ch = max(1, channels)
        val frames = data.size / ch
        if (frames == 0 || sampleRate <= 0) return FloatArray(0)
        val bucket = max(1, sampleRate / perSecond)
        val n = (frames + bucket - 1) / bucket
        val out = FloatArray(n)
        var top = 0f
        for (b in 0 until n) {
            var m = 0
            val from = b * bucket
            val to = min(frames, from + bucket)
            for (i in from until to) for (c in 0 until ch) m = max(m, abs(data[i * ch + c].toInt()))
            val v = m / 32768f
            out[b] = v
            if (v > top) top = v
        }
        if (top > 0f) for (i in out.indices) out[i] = (out[i] / top).coerceIn(0f, 1f)
        return out
    }

    /** Секунда источника для кадра таймлайна [frame] (дробный) с учётом смещения, скорости и реверса клипа (как в AudioEngine). */
    fun sourceSecond(clip: Clip, fps: Int, frame: Float): Double {
        val rel = frame - clip.start
        val src = if (!clip.reverse) clip.offset + rel * clip.speed.toDouble() else clip.offset + clip.length * clip.speed.toDouble() - rel * clip.speed.toDouble()
        return src / fps.coerceAtLeast(1)
    }

    /** Наибольшая амплитуда на отрезке кадров [f0, f1] таймлайна; 0 вне записи. */
    fun amplitude(peaks: FloatArray, clip: Clip, fps: Int, f0: Float, f1: Float, perSecond: Int = PER_SECOND): Float {
        if (peaks.isEmpty()) return 0f
        val a = sourceSecond(clip, fps, f0) * perSecond
        val b = sourceSecond(clip, fps, f1) * perSecond
        val lo = Math.floor(min(a, b)).toInt()
        val hi = Math.floor(max(a, b)).toInt()
        if (hi < 0 || lo >= peaks.size) return 0f
        var m = 0f
        for (i in max(0, lo)..min(peaks.size - 1, hi)) if (peaks[i] > m) m = peaks[i]
        return m
    }
}
