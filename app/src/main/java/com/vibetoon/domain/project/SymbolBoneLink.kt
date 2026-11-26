package com.vibetoon.domain.project

/**
 * Спринт 54: символы и кости.
 *
 * 1. Привязка участников символа к кости «съедалась»: если слои рта были привязаны к кости головы, а потом из них сделали
 *    символ, форма снималась на ВНУТРЕННЕМ кадре символа (кость - на чужом времени), а сам экземпляр за костью не шёл.
 *    Теперь общий внешний родитель участников (кость или слой вне символа) переезжает на экземпляр: экземпляр двигается,
 *    поворачивается и масштабируется вместе с костью на холсте, в Play и в экспорте; формы снимаются без внешних привязок.
 * 2. Рамка экземпляра символа - весь холст (своей текстуры нет), поэтому лассо и автопривязка не находили рот/голову.
 *    Рамка содержимого считается по альфе готовых форм ([union]).
 * Чистые функции - unit-тест.
 */
object SymbolBoneLink {
    /** Общий родитель вне символа у всех привязанных наружу участников [members]; null - нет или их несколько. */
    fun sharedOuterParent(members: Set<Int>, links: Map<Int, Int>): Int? =
        members.mapNotNullTo(HashSet()) { m -> links[m]?.takeIf { it !in members } }.singleOrNull()

    /** Потомки [id] по привязкам (кто через цепочку родителей следует за [id]). */
    private fun descendants(id: Int, links: Map<Int, Int>): Set<Int> {
        val out = HashSet<Int>()
        do {
            val before = out.size
            for ((c, p) in links) if (p == id || p in out) out += c
        } while (out.size != before)
        return out
    }

    /**
     * Перенос привязок: экземпляры [instances] (экземпляр -> определение) без своей привязки получают общего внешнего
     * родителя участников определения ([defs]: определение -> все участники); привязки участников к нему снимаются.
     * Привязка, дающая цикл, не ставится (тогда участники сохраняют свою). Без изменений - тот же объект.
     */
    fun migrate(links: Map<Int, Int>, defs: Map<Int, Set<Int>>, instances: Map<Int, Int>): Map<Int, Int> {
        var out = links
        for ((def, members) in defs) {
            val p = sharedOuterParent(members, out) ?: continue
            val insts = instances.filterValues { it == def }.keys.sorted()
            if (insts.isEmpty()) continue
            val free = insts.filter { it !in out && it != p && p !in descendants(it, out) && p !in members }
            if (free.size != insts.size && insts.none { out[it] == p }) continue   // часть экземпляров уже привязана иначе - не трогаем
            val next = out.toMutableMap()
            for (i in free) next[i] = p
            for (m in members) if (next[m] == p) next.remove(m)
            out = next
        }
        return out
    }

    /** Привязки для снимка формы символа: участники [members] не следуют за родителями вне символа. */
    fun snapshotLinks(links: Map<Int, Int>, members: Set<Int>): Map<Int, Int> =
        if (links.keys.none { it in members }) links else links.filter { (c, p) -> c !in members || p in members }

    /**
     * Рамка содержимого экземпляра в долях его квадрата [minU, minV, maxU, maxV] - объединение рамок форм.
     * [forms]: (rect формы в квадрате экземпляра x, y, w, h; рамка альфы внутри формы minU, minV, maxU, maxV или пусто).
     * Нет непустых форм - пустой массив.
     */
    fun union(forms: List<Pair<FloatArray, FloatArray>>): FloatArray {
        var u0 = Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
        for ((rect, box) in forms) {
            if (rect.size < 4 || box.size < 4) continue
            val x0 = rect[0] + box[0] * rect[2]; val y0 = rect[1] + box[1] * rect[3]
            val x1 = rect[0] + box[2] * rect[2]; val y1 = rect[1] + box[3] * rect[3]
            if (x0 < u0) u0 = x0; if (y0 < v0) v0 = y0; if (x1 > u1) u1 = x1; if (y1 > v1) v1 = y1
        }
        if (u1 < u0 || v1 < v0) return FloatArray(0)
        return floatArrayOf(u0.coerceIn(0f, 1f), v0.coerceIn(0f, 1f), u1.coerceIn(0f, 1f), v1.coerceIn(0f, 1f))
    }
}
