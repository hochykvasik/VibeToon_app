package com.vibetoon.domain.anim

/**
 * Спринт 52: удержание кнопок «− / +» (автоповтор с ускорением) и перетаскивание значения влево-вправо, как ползунок.
 * Без Android - проверяется unit-тестом; UI только отмеряет время и пиксели.
 */
object StepGestures {
    /** Пауза перед первым повтором: короткое касание - ровно один шаг. */
    const val FIRST_DELAY_MS = 400L
    const val MIN_DELAY_MS = 40L
    private const val START_DELAY_MS = 170L

    /** Пауза после повтора номер [n] (с 0): 170 мс, дальше быстрее, не чаще раза в 40 мс. */
    fun delayAfter(n: Int): Long = (START_DELAY_MS - n.coerceAtLeast(0) * 10L).coerceAtLeast(MIN_DELAY_MS)

    /** Шаг повтора номер [n]: первые 12 - по 1 кадру, потом по 2, после 30 - по 4 (длину 48 ужать до 2 - пара секунд). */
    fun stepAfter(n: Int): Int = when {
        n < 12 -> 1
        n < 30 -> 2
        else -> 4
    }

    /** Значение при перетаскивании на [dxPx] от [start]: шаг на каждые [stepPx] пикселей, в пределах [min]..[max]. */
    fun dragValue(start: Int, dxPx: Float, stepPx: Float, min: Int, max: Int): Int {
        if (!dxPx.isFinite() || stepPx <= 0f) return start.coerceIn(min, max)
        return (start + (dxPx / stepPx).toInt()).coerceIn(min, max)
    }
}
