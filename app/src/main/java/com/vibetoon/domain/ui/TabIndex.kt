package com.vibetoon.domain.ui

/**
 * Спринт 54: безопасный индекс вкладки. Вылет `IndexOutOfBoundsException: Index 5 out of bounds for length 5`
 * в `ScrollableTabRow`: стандартный индикатор - отдельная composable-лямбда, она перекомпоновывается с НОВЫМ
 * индексом раньше, чем строка заново измерит вкладки (позиции ещё СТАРЫЕ). В инспекторе так бывает, когда
 * открыта последняя вкладка («Эффекты», 4 из 5) и выделяется символ или звук: появляется «Время»/«Громкость»,
 * индекс становится 5, а позиций ещё 5. Все строки вкладок берут индекс только через [clamp]/[positionOrNull].
 */
object TabIndex {
    /** Индекс в границах [0, size-1]; пустой список - 0. */
    fun clamp(index: Int, size: Int): Int = if (size <= 0) 0 else index.coerceIn(0, size - 1)

    /** Элемент по индексу или null, если индекс уже/ещё вне списка (позиции из прошлого измерения). */
    fun <T> positionOrNull(positions: List<T>, index: Int): T? = positions.getOrNull(index)

    /**
     * Индекс вкладки по имени в новом списке: имя пропало - ближайшая по позиции (не прыгаем в начало),
     * иначе - индекс имени.
     */
    fun <T> indexOf(tabs: List<T>, current: T, previousIndex: Int): Int {
        val i = tabs.indexOf(current)
        return if (i >= 0) i else clamp(previousIndex, tabs.size)
    }
}
