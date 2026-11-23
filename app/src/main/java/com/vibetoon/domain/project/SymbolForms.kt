package com.vibetoon.domain.project

/**
 * Маркеры символа - единственный источник правды о формах.
 *
 * Форма маркера - содержимое, которое начинается на его кадре: клипы участников режима «Слои как кадры»
 * (клип начинается на кадре маркера и не заходит за следующий маркер) и покадровые рисунки, начинающиеся на
 * этом кадре. Когда маркер сдвигают или отражают, вместе с ним переезжают:
 *  - его форма (клипы и рисунки) - рот не «теряется» на пустом кадре;
 *  - ключи времени всех экземпляров, которые показывали эту форму, - выбранная форма держится там, где её поставили;
 *  - маркеры-ссылки (копии) на эту форму.
 * Удаление маркера убирает только подпись: форма и ключи остаются (символ показывает то же, что и раньше).
 * Копия/вставка/дубликат создают маркеры-ссылки ([FrameMarker.form]) на ту же форму.
 *
 * Чистые функции: проверяются JVM-тестами.
 */
object SymbolForms {
    /** Перенос форм: старый кадр формы -> новый (только реальные маркеры, у которых кадр изменился). */
    fun moves(old: List<FrameMarker>, new: List<FrameMarker>, mapping: Map<Int, Int>): Map<Int, Int> {
        val realOld = old.filter { !it.isAlias }.mapTo(HashSet()) { it.frame }
        val realNew = new.filter { !it.isAlias }.mapTo(HashSet()) { it.frame }
        return mapping.filter { (from, to) -> from != to && from in realOld && to in realNew }
    }

    /** Конец отрезка формы, начинающейся на [f]: следующий реальный маркер или [dur]. */
    fun spanEnd(realFrames: List<Int>, f: Int, dur: Int): Int = realFrames.filter { it > f }.minOrNull() ?: maxOf(dur, f + 1)

    /**
     * Клипы участников [members] после переноса форм [moves]. Клип принадлежит форме, если начинается на кадре
     * реального маркера и не заходит за следующий маркер (общая подложка на несколько форм не двигается). Новая
     * длина обрезается следующим маркером в новом порядке - формы не накладываются друг на друга (нет «двух ртов»).
     */
    fun moveClips(clips: Map<Int, Clip>, members: Set<Int>, old: List<FrameMarker>, new: List<FrameMarker>, moves: Map<Int, Int>, dur: Int): Map<Int, Clip> {
        if (moves.isEmpty()) return clips
        val oldReal = old.filter { !it.isAlias }.map { it.frame }
        val newReal = new.filter { !it.isAlias }.map { it.frame }
        val out = LinkedHashMap(clips)
        for ((id, c) in clips) {
            if (id !in members || c.loop) continue
            val to = moves[c.start] ?: continue
            if (c.end > spanEnd(oldReal, c.start, dur)) continue
            val len = (c.end - c.start).coerceAtLeast(1)
            val end = minOf(to + len, spanEnd(newReal, to, to + len))
            out[id] = c.copy(start = to, end = maxOf(to + 1, end))
        }
        return out
    }

    /** Рисунки покадрового слоя после переноса форм. null - перенос столкнулся бы с неподвижным рисунком (рисунок не теряем). */
    fun moveCells(cells: List<FrameCell>, moves: Map<Int, Int>): List<FrameCell>? {
        if (moves.isEmpty() || cells.none { it.start in moves }) return cells
        val moved = cells.map { c -> moves[c.start]?.let { c.copy(start = it) } ?: c }
        if (moved.map { it.start }.distinct().size != moved.size) return null
        return moved.sortedBy { it.start }
    }

    /** Ключи времени экземпляра: значения, указывавшие на перенесённые формы, едут вместе с ними. */
    fun remapKeys(keys: List<TimeKey>, moves: Map<Int, Int>): List<TimeKey> =
        if (moves.isEmpty()) keys else keys.map { k -> moves[k.value]?.let { k.copy(value = it) } ?: k }

    /** Маркеры-ссылки на перенесённые формы. */
    fun remapAliases(markers: List<FrameMarker>, moves: Map<Int, Int>): List<FrameMarker> =
        if (moves.isEmpty()) markers else markers.map { m -> if (m.isAlias) (moves[m.form]?.let { m.copy(form = it) } ?: m).normalized() else m }

    /** Буфер копирования маркеров: каждая копия - ссылка на форму исходника (кадры буфера относительные). */
    fun asAliases(copied: List<FrameMarker>, sources: List<FrameMarker>): List<FrameMarker> {
        val base = sources.minOfOrNull { it.frame } ?: return copied
        return copied.map { c -> val src = sources.firstOrNull { it.frame == c.frame + base }; if (src == null) c else c.copy(form = src.target, thumb = "") }
    }

    /** После вставки: ссылки на себя становятся обычными маркерами. */
    fun normalize(markers: List<FrameMarker>): List<FrameMarker> = markers.map { it.normalized() }

    /** Подпись формы [value] для экземпляра: обычный маркер этой формы, иначе первая ссылка на неё. */
    fun labelFor(markers: List<FrameMarker>, value: Int): FrameMarker? =
        markers.firstOrNull { !it.isAlias && it.frame == value } ?: markers.firstOrNull { it.target == value }

    /**
     * Уникальное имя для обычного маркера на кадре [frame]: одинаковые имена у разных форм путали выбор («Обычный» x3).
     * Ссылки на ту же форму имя разделяют - это та же форма.
     */
    fun uniqueName(markers: List<FrameMarker>, name: String, frame: Int): String {
        val taken = markers.filter { !it.isAlias && it.frame != frame }.mapTo(HashSet()) { it.name }
        if (name !in taken) return name
        var k = 2
        while ("$name $k" in taken) k++
        return "$name $k"
    }

    /** Последний кадр, занятый формами символа (клипы участников, рисунки, маркеры и их ссылки): для длительности. */
    fun lastFrame(markers: List<FrameMarker>, memberClips: Collection<Clip>, cells: List<Int>): Int {
        var last = 0
        for (m in markers) last = maxOf(last, m.frame, m.target)
        for (c in memberClips) if (!c.loop) last = maxOf(last, c.end - 1)
        for (s in cells) last = maxOf(last, s)
        return last
    }
}
