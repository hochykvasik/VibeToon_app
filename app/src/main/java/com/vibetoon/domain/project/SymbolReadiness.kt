package com.vibetoon.domain.project

/**
 * Спринт 48: можно ли уже снимать форму символа. Причина пропадания первой формы (закрытого рта) после выхода из
 * проекта и повторного открытия: проект публиковался на холст раньше, чем разбирался PSD, а участники символа - слои
 * PSD, перенесённые в группу символа (`regroup`). В этот момент их ещё нет в списке слоёв, снимок формы текущего кадра
 * (кадр 0 - первая форма) получался ПУСТЫМ, но «строгий» снимок считал его готовым (не хватало текстур - ноль, ведь
 * слоёв нет), и пустая форма оставалась в кэше до конца сеанса. Остальные формы снимались позже - уже с участниками.
 */
object SymbolReadiness {
    /**
     * Участники символа [def], которых ещё нет среди слоёв [present]: дети определения по `regroup` (и дети его
     * подгрупп), кроме удалённых слоёв PSD [deleted]. [known] - id, которые вообще существуют (медиа + разобранный PSD;
     * null - все): устаревшие записи `regroup` об удалённых слоях снимок не блокируют. Не пусто - снимок откладывается.
     */
    fun missingMembers(def: Int, regroup: List<Pair<Int, Int>>, present: Set<Int>, deleted: Set<Int>, known: Set<Int>? = null): Set<Int> {
        if (regroup.isEmpty()) return emptySet()
        val children = HashMap<Int, MutableList<Int>>()
        for ((child, parent) in regroup) if (child != parent) children.getOrPut(parent) { ArrayList() } += child
        val missing = LinkedHashSet<Int>()
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Int>().also { it.addLast(def) }
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            if (!seen.add(p)) continue
            for (c in children[p].orEmpty()) {
                if (c in deleted || (known != null && c !in known)) continue
                if (c !in present) missing += c
                stack.addLast(c)   // подгруппа: её дети тоже участники
            }
        }
        return missing
    }

    /**
     * Можно ли снимать формы: PSD проекта уже разобран ([psdPending] = false) и у символа нет отсутствующих участников.
     */
    fun canSnapshot(psdPending: Boolean, missing: Set<Int>): Boolean = !psdPending && missing.isEmpty()

    /**
     * Пустой снимок формы маркера подозрителен: на кадре маркера по замыслу есть рисунок. Такой снимок не считается
     * окончательным - переснимается позже (текстуры участников могли ещё не загрузиться или быть вытеснены).
     */
    fun suspiciousBlank(blank: Boolean, local: Int, markers: List<FrameMarker>): Boolean =
        blank && markers.any { it.target == local }

    /**
     * Ожидание снимка не идёт в счёт лимита (после которого форма снимается «как есть»), пока проект открывается,
     * разбирается PSD или очередь загрузки текстур не пуста.
     */
    fun waitCounts(pendingUploadBytes: Long, opening: Boolean, psdPending: Boolean): Boolean =
        pendingUploadBytes == 0L && !opening && !psdPending
}
