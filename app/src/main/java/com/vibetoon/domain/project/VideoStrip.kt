package com.vibetoon.domain.project

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Время источника видео для кадра проекта: начало клипа, скорость, реверс, стоп-кадр и смещение (как при воспроизведении). */
object VideoTime {
    fun sourceMs(clip: Clip, frame: Float, fps: Int): Long {
        val last = (clip.length - 1).coerceAtLeast(0).toFloat()
        val local = (frame - clip.start).coerceIn(0f, last)
        val step = when {
            clip.freeze -> 0f
            clip.reverse -> (last - local) * clip.speed
            else -> local * clip.speed
        }
        return ((clip.offset + step) * 1000f / fps.coerceAtLeast(1)).toLong().coerceAtLeast(0L)
    }
}

/**
 * Плёнка видео на полосе клипа таймлайна (пакет 11): плитки одинаковой ширины от начала клипа, у каждой - момент
 * источника в середине плитки. Моменты округляются ([quantMs]), чтобы прокрутка и мелкий зум не порождали новые
 * запросы кадров; считаются только видимые плитки.
 */
object Filmstrip {
    const val QUANT_MS = 100L

    data class Tile(val x: Float, val width: Float, val sourceMs: Long)

    fun quantize(ms: Long, q: Long = QUANT_MS): Long = if (q <= 1) ms else (ms / q) * q

    fun tiles(
        clip: Clip, fps: Int, frameW: Float, tileW: Float, vis0: Float, vis1: Float,
        durationMs: Long, quantMs: Long = QUANT_MS, maxTiles: Int = 64
    ): List<Tile> {
        if (frameW <= 0f || tileW <= 0f || !tileW.isFinite() || clip.length <= 0) return emptyList()
        val x0 = clip.start * frameW
        val x1 = clip.end * frameW
        val lo = max(x0, vis0)
        val hi = min(x1, vis1)
        if (hi <= lo) return emptyList()
        val out = ArrayList<Tile>()
        var k = floor((lo - x0) / tileW).toInt().coerceAtLeast(0)
        while (out.size < maxTiles) {
            val x = x0 + k * tileW
            if (x >= hi) break
            val w = min(tileW, x1 - x)
            var ms = VideoTime.sourceMs(clip, (x + w / 2f) / frameW, fps)
            if (durationMs > 0) ms = ms.coerceAtMost(durationMs - 1)
            out += Tile(x, w, quantize(ms, quantMs))
            k++
        }
        return out
    }
}
