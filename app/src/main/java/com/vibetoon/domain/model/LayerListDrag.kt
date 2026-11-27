package com.vibetoon.domain.model

/**
 * Спринт 40: перетаскивание слоёв в виде «Список» (ручка ≡ рядом со стрелками «Выше»/«Ниже»). Чистые функции - Package40LayerListTest.
 *
 * - Строки разной высоты (слой 32 dp, кость 30 dp): цель - строка, над которой окажется центр перетаскиваемой строки.
 * - Бросок на строку кости означает слой костей, которому она принадлежит (кости не переставляются).
 * - Во время поиска/фильтра и в мультивыборе ручки нет: видна только часть списка, и бросок «рядом» был бы непредсказуем.
 * - Сам бросок выполняет MainViewModel.dropLayer - тот же, что в виде «Миниатюры» (на группу - внутрь, на слой - на его место),
 *   с Undo/Redo и сохранением порядка в проект.
 */
object LayerListDrag {
    /** Ручка перетаскивания у строки: только у слоёв, без поиска/фильтра и вне мультивыбора. */
    fun canDrag(entry: LayerListView.Entry, searching: Boolean, multi: Boolean): Boolean = !entry.bone && !searching && !multi

    /** Индекс строки, над которой окажется центр строки [start] после сдвига [dy] (px); [heights] - высоты строк. */
    fun targetIndex(heights: List<Float>, start: Int, dy: Float): Int {
        val n = heights.size
        if (n == 0) return -1
        if (start !in 0 until n || !dy.isFinite()) return start.coerceIn(0, n - 1)
        var top = 0f
        for (i in 0 until start) top += heights[i].coerceAtLeast(0f)
        val center = top + heights[start].coerceAtLeast(0f) / 2f + dy
        if (center < 0f) return 0
        var acc = 0f
        for (i in 0 until n) {
            acc += heights[i].coerceAtLeast(0f)
            if (center < acc) return i
        }
        return n - 1
    }

    /** Слой-цель для строки [index]: строка кости - её слой костей (ближайшая строка слоя выше); null - нет цели. */
    fun layerAt(entries: List<LayerListView.Entry>, index: Int): Int? {
        var i = index
        while (i >= 0 && i < entries.size) {
            if (!entries[i].bone) return entries[i].id
            i--
        }
        return null
    }

    /** Цель броска слоя [dragId] со строки [start] после сдвига [dy]; null - бросок на себя или некуда (ничего не делаем). */
    fun dropTarget(entries: List<LayerListView.Entry>, heights: List<Float>, start: Int, dy: Float, dragId: Int): Int? {
        if (entries.size != heights.size || start !in entries.indices) return null
        val t = targetIndex(heights, start, dy)
        if (t == start) return null
        val target = layerAt(entries, t) ?: return null
        return target.takeIf { it != dragId }
    }
}
