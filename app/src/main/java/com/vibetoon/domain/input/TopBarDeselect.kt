package com.vibetoon.domain.input

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Спринт 59: тап по свободному месту верхней панели правее «Объект / Кости / Сетка» снимает выделение - как тап в
 * пустоту холста, только искать пустое место не нужно (объект приближен на весь экран).
 * Срабатывает только осознанный тап: один палец, без сдвига дальше «touch slop» и без долгого удержания. Протяжка,
 * второй палец, долгое нажатие и касание во время штриха или перетаскивания на холсте выделение не трогают.
 */
object TopBarDeselect {
    /** Зона не уже этого, dp: на узкой строке имя проекта сокращается, а не съедает зону. */
    const val MIN_ZONE_DP = 48f

    /** Было ли касание тапом: сдвиг не дальше [slop], короче [longPressMs], всё время один палец, не перехвачено. */
    fun isTap(dx: Float, dy: Float, durationMs: Long, slop: Float, longPressMs: Long, maxPointers: Int, consumed: Boolean): Boolean =
        !consumed && maxPointers == 1 && durationMs in 0 until longPressMs && hypot(dx, dy) <= slop

    /** Снимать ли выделение: оно есть и на холсте не идёт жест (штрих, перенос кости, рамка, перо у экрана). */
    fun shouldDeselect(hasSelection: Boolean, canvasBusy: Boolean): Boolean = hasSelection && !canvasBusy

    /**
     * Строка «имя проекта - режимы - зона» внутри доступной ширины [available]: сколько можно отдать имени.
     * Режимы ([chips]) не сжимаются; зоне остаётся не меньше [minZone] (но не больше трети свободного места).
     */
    fun titleMax(available: Int, chips: Int, minZone: Int): Int {
        val rest = max(0, available - chips)
        return max(0, rest - min(minZone, rest / 3))
    }
}
