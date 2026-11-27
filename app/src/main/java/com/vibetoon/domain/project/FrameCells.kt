package com.vibetoon.domain.project

/**
 * Один рисунок покадрового слоя: показывается с кадра [start] до начала следующего (экспозиция). [file] - PNG в папке проекта.
 * Спринт 51: [hold] > 0 - своя длина экспозиции в кадрах (рисунок виден [start, start + hold) и не тянется до конца
 * таймлайна; после него - пустой промежуток до следующего рисунка). 0 - как раньше: до следующего рисунка или конца.
 */
data class FrameCell(val start: Int, val file: String, val hold: Int = 0)

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
     * Undo «+ Кадр») сам файл слоя - только если текстура раньше показывала рисунок кадра ([shown] != null).
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

    // ---- спринт 51: длина экспозиции, вставка следующего рисунка, навигация ----

    /** Конец экспозиции рисунка [cell] (не включая): начало следующего, ограниченное своей длиной [FrameCell.hold], иначе [duration]. */
    fun end(cells: List<FrameCell>, cell: FrameCell, duration: Int): Int {
        val next = cells.filter { it.start > cell.start }.minOfOrNull { it.start } ?: maxOf(duration, cell.start + 1)
        return if (cell.hold > 0) minOf(next, cell.start + cell.hold) else next
    }

    /** Рисунок, ВИДИМЫЙ на кадре: активный, если кадр не попал в пустой промежуток после его длины; иначе null. */
    fun visible(cells: List<FrameCell>, frame: Int, duration: Int): FrameCell? {
        val a = active(cells, frame) ?: return null
        return if (frame < a.start || frame < end(cells, a, duration)) a else null
    }

    /** Отрезки экспозиций [start, end) по порядку - для полосок рисунков на таймлайне. */
    fun spans(cells: List<FrameCell>, duration: Int): List<IntArray> {
        val sorted = cells.sortedBy { it.start }
        return sorted.map { intArrayOf(it.start, end(sorted, it, duration)) }
    }

    /** Длина экспозиции рисунка в кадрах. */
    fun lengthOf(cells: List<FrameCell>, cell: FrameCell, duration: Int): Int = (end(cells, cell, duration) - cell.start).coerceAtLeast(1)

    /**
     * Кадр для нового рисунка («+ Кадр», «Дубль кадра»): сразу после экспозиции рисунка на [frame] (конец его длины),
     * в пустом промежутке - сам [frame]; занято - ближайший свободный дальше. null - нет места до [duration].
     * У рисунка без своей длины (тянется до следующего) новый встаёт на следующий кадр после плейхеда.
     */
    fun insertAt(cells: List<FrameCell>, frame: Int, duration: Int): Int? {
        val a = active(cells, frame)
        val target = when {
            a == null -> frame
            frame < a.start -> frame
            visible(cells, frame, duration) == null -> frame
            a.hold > 0 -> end(cells, a, duration)
            else -> frame + 1
        }
        return freeStart(cells, target, duration)
    }

    /**
     * Список с новым рисунком [cell]: рисунок перед ним без своей длины, который тянулся бы через новое место, теперь
     * заканчивается там, где начинается новый (так и было по правилу «до следующего»), а длина нового не заходит на
     * следующий рисунок.
     */
    fun insert(cells: List<FrameCell>, cell: FrameCell): List<FrameCell> {
        val next = cells.filter { it.start > cell.start }.minOfOrNull { it.start }
        val c = if (next != null && cell.hold > 0 && cell.start + cell.hold > next) cell.copy(hold = next - cell.start) else cell
        return with(cells.filter { it.start != c.start }, c)
    }

    /** Новая длина [length] (кадры, >= 1) рисунка, активного на [frame]; 0 - снять длину (до следующего рисунка). */
    fun withLength(cells: List<FrameCell>, frame: Int, length: Int): List<FrameCell> {
        val a = active(cells, frame) ?: return cells
        val next = cells.filter { it.start > a.start }.minOfOrNull { it.start }
        val l = if (length <= 0) 0 else if (next != null) length.coerceAtMost(next - a.start) else length
        return cells.map { if (it == a) it.copy(hold = l) else it }
    }

    /** Начало соседнего рисунка: [forward] - ближайшее после [frame], иначе ближайшее до него (из середины экспозиции - её начало). */
    fun neighborStart(cells: List<FrameCell>, frame: Int, forward: Boolean): Int? {
        val starts = cells.map { it.start }.distinct().sorted()
        return if (forward) starts.firstOrNull { it > frame } else starts.lastOrNull { it < frame }
    }

    /**
     * Режим «+ Кадр»: true - отдельные слои-рисунки (у слоя нет своих кадров, но есть своя плашка на таймлайне -
     * раскладка лесенкой Рисунок 1 / Рисунок 2), false - рисунки на одной дорожке слоя.
     */
    fun layerMode(cellCount: Int, explicitClip: Boolean): Boolean = cellCount == 0 && explicitClip
}
