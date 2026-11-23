package com.vibetoon.domain.project

/** Один рисунок покадрового слоя: показывается с кадра [start] до начала следующего (экспозиция). [file] - PNG в папке проекта. */
data class FrameCell(val start: Int, val file: String)

/** Чистая логика покадровых слоёв: какой рисунок виден на кадре, соседи для кальки, свободные места. */
object FrameMath {
    /** Рисунок на кадре [frame]: последний с start <= frame; до первого - сам первый (удержание назад). */
    fun active(cells: List<FrameCell>, frame: Int): FrameCell? {
        if (cells.isEmpty()) return null
        return cells.lastOrNull { it.start <= frame } ?: cells.minByOrNull { it.start }
    }

    /** Предыдущие (ближайший первым) и следующие рисунки относительно активного на [frame], не больше [depth] с каждой стороны. */
    fun neighbors(cells: List<FrameCell>, frame: Int, depth: Int): Pair<List<FrameCell>, List<FrameCell>> {
        val sorted = cells.sortedBy { it.start }
        val cur = active(sorted, frame) ?: return emptyList<FrameCell>() to emptyList()
        val i = sorted.indexOf(cur)
        val prev = (1..depth).mapNotNull { sorted.getOrNull(i - it) }
        val next = (1..depth).mapNotNull { sorted.getOrNull(i + it) }
        return prev to next
    }

    /** Первый свободный кадр начиная с [from] (там ещё не начинается рисунок); null - за пределами [duration]. */
    fun freeStart(cells: List<FrameCell>, from: Int, duration: Int): Int? {
        var s = from.coerceAtLeast(0)
        while (cells.any { it.start == s }) s++
        return if (s < duration) s else null
    }

    /** Список с добавленным рисунком, упорядоченный по кадрам. */
    fun with(cells: List<FrameCell>, cell: FrameCell): List<FrameCell> = (cells + cell).sortedBy { it.start }

    /**
     * Пакет 17: рисунок, активный на [frame], заменяется файлом [file] (экспозиция та же). Старый файл не трогается -
     * Undo возвращает прежний список. Пустой список - слой без кадров: его единственный рисунок [baseFile] на кадре 0.
     */
    fun replaceActive(cells: List<FrameCell>, frame: Int, file: String, baseFile: String): List<FrameCell> {
        val list = cells.ifEmpty { listOf(FrameCell(0, baseFile)) }
        val a = active(list, frame) ?: return list
        return list.map { if (it == a) it.copy(file = file) else it }.sortedBy { it.start }
    }

    /**
     * Пакет 17: что показать в текстуре слоя-картинки на [frame]: рисунок кадра или (кадров больше нет, например после
     * Undo «Добавить пустой кадр») сам файл слоя - только если текстура раньше показывала рисунок кадра ([shown] != null).
     */
    fun shownFile(cells: List<FrameCell>, frame: Int, file: String, shown: String?): String? =
        if (cells.isNotEmpty()) active(cells, frame)?.file else if (shown != null) file else null

    /** Список без рисунка, активного на [frame]; последний рисунок удалить нельзя (вернётся тот же список). */
    fun without(cells: List<FrameCell>, frame: Int): List<FrameCell> {
        if (cells.size <= 1) return cells
        val a = active(cells, frame) ?: return cells
        return cells.filter { it != a }
    }

    /** Непрозрачность призрака кальки: ближайший соседний кадр [base], каждый следующий слабее. */
    fun ghostAlpha(base: Float, index: Int): Float = (base * Math.pow(0.6, index.toDouble())).toFloat().coerceIn(0f, 1f)
}
