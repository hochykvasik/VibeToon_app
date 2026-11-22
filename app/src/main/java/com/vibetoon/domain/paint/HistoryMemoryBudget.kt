package com.vibetoon.domain.paint

/** Обе очереди перечислены от дна к вершине. Удаляются только префиксы, чтобы не разорвать цепочку истории. */
object HistoryMemoryBudget {
    data class Trim(val undo: Int, val redo: Int)

    fun trim(undo: List<Long>, redo: List<Long>, limit: Long = PaintBudget.LIMIT_BYTES): Trim {
        require(limit >= 0 && undo.all { it >= 0 } && redo.all { it >= 0 })
        val sizes = undo + redo
        // Не допускаем переполнения при подсчёте даже для ошибочных входных данных.
        var total = sizes.fold(0L) { sum, n -> if (Long.MAX_VALUE - sum < n) Long.MAX_VALUE else sum + n }
        if (total <= limit) return Trim(0, 0)
        // Пересчёт после каждого удаления также корректен для насыщенной суммы.
        var u = 0; var r = 0
        fun remaining(): Long = (undo.drop(u) + redo.drop(r)).fold(0L) { sum, n ->
            if (Long.MAX_VALUE - sum < n) Long.MAX_VALUE else sum + n
        }
        while (total > limit && (u < undo.size || r < redo.size)) {
            if (u < undo.size) u++ else r++
            total = remaining()
        }
        return Trim(u, r)
    }
}
