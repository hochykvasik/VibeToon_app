package com.vibetoon.domain.anim

/** Шаг по кадру либо ближайшему началу экспозиции/ключу выбранного слоя; без цели — остаёмся на месте. */
object TimelineNavigation {
    fun step(frame: Int, direction: Int, duration: Int, byFrame: Boolean, points: Collection<Int>): Int {
        val last = (duration - 1).coerceAtLeast(0)
        val current = frame.coerceIn(0, last)
        if (direction == 0) return current
        if (byFrame) return (current + if (direction > 0) 1 else -1).coerceIn(0, last)
        return if (direction > 0) points.filter { it in (current + 1)..last }.minOrNull() ?: current
        else points.filter { it in 0 until current }.maxOrNull() ?: current
    }
}
