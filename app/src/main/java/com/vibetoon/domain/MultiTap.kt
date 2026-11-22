package com.vibetoon.domain

/** Касание несколькими пальцами: 2 пальца = Undo, 3 пальца = Redo. Решается по итогам жеста, поэтому щипок и сдвиг камеры не срабатывают как касание. */
object MultiTap {
    const val MAX_MS = 260L

    /** 2 или 3 - сколько пальцев коснулось; 0 - это не касание (слишком долго, пальцы сдвинулись или всего один/четыре и больше). */
    fun decide(maxPointers: Int, durationMs: Long, maxMovePx: Float, slopPx: Float): Int =
        if (maxPointers in 2..3 && durationMs <= MAX_MS && maxMovePx <= slopPx) maxPointers else 0
}
