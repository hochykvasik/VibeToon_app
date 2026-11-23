package com.vibetoon.domain.project

/**
 * Пакет 12: настройка Import → Resolution Limit. Картинка при импорте уменьшается до длинной стороны холста
 * (не меньше [MIN_SIDE] и не больше предела текстуры [MAX_SIDE]); файл в проекте тоже становится меньше -
 * меньше памяти и быстрее загрузка. На холсте картинка занимает то же место (вписывается в холст как раньше).
 */
object ImportLimit {
    const val MIN_SIDE = 512
    const val MAX_SIDE = 2048

    fun maxSide(canvasW: Int, canvasH: Int): Int = maxOf(canvasW, canvasH).coerceIn(MIN_SIDE, MAX_SIDE)

    fun needsDownscale(w: Int, h: Int, limit: Int): Boolean = w > 0 && h > 0 && maxOf(w, h) > limit

    /** Новый размер с сохранением пропорций: длинная сторона = [limit], короткая не меньше 1. */
    fun scaled(w: Int, h: Int, limit: Int): Pair<Int, Int> {
        if (!needsDownscale(w, h, limit)) return w to h
        val k = limit.toDouble() / maxOf(w, h)
        return Math.round(w * k).toInt().coerceAtLeast(1) to Math.round(h * k).toInt().coerceAtLeast(1)
    }
}
