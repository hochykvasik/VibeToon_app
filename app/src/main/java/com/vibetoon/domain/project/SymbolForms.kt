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

    /** Начало формы, в которую попадает внутренний кадр [v]: последний реальный маркер с кадром <= v (null - до первого маркера). */
    fun formStart(markers: List<FrameMarker>, v: Int): Int? = markers.filter { !it.isAlias && it.frame <= v }.maxOfOrNull { it.frame }

    /** Спринт 47: шагов растворения между формами (смесь считается с точностью 1/16 - формы кэшируются по шагам). */
    const val MIX_STEPS = 16

    /**
     * Спринт 47: длительность символа для ключей и маркеров - не короче последней формы. В старых проектах длительность
     * могла быть посчитана раньше, чем появилась последняя форма: ключ на неё упирался в границу и показывал соседнюю.
     */
    fun effectiveDuration(symDur: Int, markers: List<FrameMarker>): Int =
        maxOf(symDur, (markers.maxOfOrNull { maxOf(it.frame, it.target) } ?: -1) + 1, 1)

    /**
     * Внутренний кадр экземпляра на кадре сцены [f] для символа с формами (маркерами) - доминирующая форма [frameRef]
     * (для подсветки маркера, снимков и мест, где нужна одна форма).
     */
    fun frameAt(keys: List<TimeKey>, f: Int, duration: Int, animated: Boolean, loop: Boolean, markers: List<FrameMarker>): Int =
        frameAt(keys, f, duration, animated, if (loop) SymbolPlayback.LOOP else SymbolPlayback.HOLD, 0, markers)

    /** Спринт 37: то же с режимом проигрывания без ключей ([SymbolPlayback]: Hold / Loop / Play Once от кадра [start]). */
    fun frameAt(keys: List<TimeKey>, f: Int, duration: Int, animated: Boolean, mode: Int, start: Int, markers: List<FrameMarker>): Int =
        frameRef(keys, f, duration, animated, mode, start, markers).dominant

    /**
     * Спринт 47: что показывает экземпляр на кадре сцены [f] (одно правило для холста, Play, экспорта и вложенных символов).
     *  - Hold: форма ключа до кадра следующего ключа, там - резкая смена;
     *  - переход (Linear, Smooth, Ease, своя кривая) между ключами на РАЗНЫЕ формы: растворение формы ключа в форму
     *    следующего ключа по кривой; промежуточные внутренние кадры (чужие формы) не показываются никогда;
     *  - переход внутри одной формы (анимированная форма): внутренние кадры по кривой, не выходя за отрезок формы;
     *  - символ без маркеров: Time Remapping по кривой, как у любого ключа.
     */
    fun frameRef(keys: List<TimeKey>, f: Int, duration: Int, animated: Boolean, mode: Int, start: Int, markers: List<FrameMarker>): FormRef {
        val dur = effectiveDuration(duration, markers)
        val real = markers.filter { !it.isAlias }.map { it.frame }.distinct().sorted()
        if (keys.size >= 2 && real.isNotEmpty()) {
            val ks = if (keys.zipWithNext().all { (a, b) -> a.frame <= b.frame }) keys else keys.sortedBy { it.frame }
            val i = ks.indexOfLast { it.frame <= f }
            if (i >= 0 && i + 1 < ks.size) {
                val a = ks[i]; val b = ks[i + 1]
                if (a.ease != TimeEase.HOLD && TimeEase.valid(a.ease) && a.value != b.value && b.frame > a.frame) {
                    val av = a.value.coerceIn(0, dur - 1); val bv = b.value.coerceIn(0, dur - 1)
                    val t = (f - a.frame).toFloat() / (b.frame - a.frame)
                    val e = TimeEase.value(a, t)
                    val fa = formStart(markers, av); val fb = formStart(markers, bv)
                    if (fa != fb) {
                        val mix = Math.round(e.coerceIn(0f, 1f) * MIX_STEPS)
                        return when {
                            mix <= 0 -> FormRef(av)
                            mix >= MIX_STEPS -> FormRef(bv)
                            else -> FormRef(av, bv, mix)
                        }
                    }
                    // одна форма: кадры формы по кривой; перелёт своей кривой не выводит в соседнюю форму
                    val lo = fa ?: 0
                    val hi = (if (fa == null) real.first() else spanEnd(real, fa, dur)) - 1
                    val v = Math.round(av + (bv - av) * e)
                    return FormRef(v.coerceIn(lo, maxOf(lo, hi)).coerceIn(0, dur - 1))
                }
            }
        }
        return FormRef(SymbolPlayback.instanceFrame(keys, f, dur, animated, mode, start))
    }

    /**
     * Спринт 47: все смеси форм, которые экземпляр покажет на кадрах [frames] (для заблаговременного снимка и экспорта).
     */
    fun blendsOn(frames: IntRange, at: (Int) -> FormRef): Set<FormRef> {
        val out = LinkedHashSet<FormRef>()
        for (f in frames) { val r = at(f); if (r.blended) out += r }
        return out
    }

    /**
     * Маркер формы, которую символ показывает на внутреннем кадре [shown] (для подсветки в панели «Выбрать маркер»):
     * обычный маркер, в чей отрезок попадает кадр. Панель и холст считают форму одной функцией - рамка не «убегает».
     */
    fun shownMarker(markers: List<FrameMarker>, shown: Int): FrameMarker? {
        val start = formStart(markers, shown) ?: return null
        return markers.firstOrNull { !it.isAlias && it.frame == start }
    }

    /**
     * Короткие подписи форм для таймлайна и панели: общий для всех имён префикс из целых слов убирается
     * («Обычный слой 30», «Обычный слой 31» -> «30», «31»). Если после этого имя пустое - остаётся исходное.
     */
    fun compactLabels(names: Collection<String>): Map<String, String> {
        val distinct = names.distinct()
        if (distinct.size < 2) return distinct.associateWith { it }
        val split = distinct.map { it.trim().split(Regex("\\s+")) }
        var common = 0
        // у каждого имени остаётся хотя бы одно слово
        while (split.all { it.size > common + 1 } && split.all { it[common] == split[0][common] }) common++
        return distinct.zip(split).associate { (n, w) -> n to w.drop(common).joinToString(" ").ifBlank { n } }
    }

    /**
     * Рамка непрозрачного содержимого (ARGB, строка 0 - верх) с запасом [pad] пикселей: форма символа хранится обрезанной
     * (рот - это несколько процентов холста, а снимок - весь холст в 2x). null - полностью прозрачно.
     */
    fun opaqueBounds(pixels: IntArray, w: Int, h: Int, pad: Int = 2): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) if ((pixels[row + x] ushr 24) != 0) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < 0) return null
        return intArrayOf(maxOf(0, l - pad), maxOf(0, t - pad), minOf(w, r + 1 + pad), minOf(h, b + 1 + pad))
    }

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
