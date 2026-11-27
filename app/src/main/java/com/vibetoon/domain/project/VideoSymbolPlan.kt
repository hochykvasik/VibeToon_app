package com.vibetoon.domain.project

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Пакет 22: импорт видео как символа. Видео раскладывается в покадровый слой внутри нового символа - один рисунок
 * на кадр проекта (как GIF в пакете 15), экземпляр крутится по кругу. Здесь чистая часть: какие моменты источника
 * берутся и в каком размере декодируются.
 */
object VideoSymbolPlan {
    /** Больше кадров за раз не импортируется (как у GIF): длинное видео обрезается. */
    const val MAX_FRAMES = 300
    /** Длинная сторона кадра без Resolution Limit: 300 PNG во весь 4K заняли бы гигабайты. */
    const val MAX_SIDE = 1280

    /** [timesMs] - момент источника для кадра i; [cut] - видео длиннее [MAX_FRAMES] кадров и обрезано. */
    data class Plan(val timesMs: List<Long>, val cut: Boolean) { val frames get() = timesMs.size }

    fun plan(durationMs: Long, fps: Int, maxFrames: Int = MAX_FRAMES): Plan {
        val f = fps.coerceAtLeast(1)
        val all = max(1, ceil(durationMs.coerceAtLeast(0L) * f / 1000.0 - 1e-9).toInt())
        val n = min(all, maxFrames.coerceAtLeast(1))
        val last = (durationMs - 1).coerceAtLeast(0L)
        return Plan(List(n) { i -> min(i * 1000L / f, last) }, all > n)
    }

    /** Размер декодирования: пропорции источника, длинная сторона не больше [maxSide], стороны не меньше 2. */
    fun decodeSize(srcW: Int, srcH: Int, maxSide: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 2 to 2
        val k = min(1f, maxSide.coerceAtLeast(2).toFloat() / max(srcW, srcH))
        return max(2, (srcW * k).roundToInt()) to max(2, (srcH * k).roundToInt())
    }

    /** Ширина и высота с учётом поворота из метаданных (90/270 - стороны меняются местами). */
    fun oriented(w: Int, h: Int, rotation: Int): Pair<Int, Int> = if (((rotation % 360) + 360) % 180 == 90) h to w else w to h
}
