package com.vibetoon.domain.input

/**
 * Спринт 49: выбор слоёв для ручной привязки к кости. «Лассо» - обвести область (несколько слоёв сразу),
 * «Точечный выбор» - касание конкретного слоя (только ладонь, без соседних): короткое касание переключает слой
 * в наборе, удержание только добавляет (случайно снять выбор удержанием нельзя). Чистые функции - unit-тест.
 */
enum class BoneSelectMode { LASSO, POINT }

object BonePointPick {
    /** Удержание дольше этого (мс) считается «добавить», а не «переключить». */
    const val HOLD_MS = 400L

    enum class Result { ADDED, REMOVED, SKIPPED }

    /**
     * Слой под пальцем, который можно выбрать: есть попадание, это не призрак (onion skin), он не закрыт и входит в
     * привязываемые (видимый слой на кадре, не группа, не кость). null - выбирать нечего.
     */
    fun eligible(hit: Int, ghostBase: Int, locked: (Int) -> Boolean, bindable: Set<Int>): Int? =
        hit.takeIf { it >= 0 && it < ghostBase && !locked(it) && it in bindable }

    /** Новый набор после касания слоя [id]: короткое касание переключает, удержание [held] - только добавляет. */
    fun apply(current: Set<Int>, id: Int, held: Boolean): Pair<Set<Int>, Result> = when {
        id !in current -> (current + id) to Result.ADDED
        held -> current to Result.SKIPPED
        else -> (current - id) to Result.REMOVED
    }

    fun isHold(downAt: Long, upAt: Long): Boolean = downAt > 0L && upAt - downAt >= HOLD_MS
}
