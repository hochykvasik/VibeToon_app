package com.vibetoon.domain.anim

import com.vibetoon.domain.model.TreeRow

/**
 * Пакет 10: «Режим ключей» таймлайна. В режиме видны только слои с ключами (и группы, в которых они лежат),
 * переход «◀ Ключ / Ключ ▶» идёт по ключам всех слоёв и костей, выделение - всех ключей кадра или всех ключей.
 * Чистые функции - unit-тест.
 */
object KeyMode {
    /** Строки дерева, которые видны в режиме ключей: слой с ключами и все его группы-предки (порядок сохраняется). */
    fun rows(rows: List<TreeRow>, keyed: Set<Int>): List<TreeRow> {
        if (keyed.isEmpty()) return emptyList()
        val parentOf = rows.associate { it.layer.id to it.layer.parentId }
        val keep = HashSet<Int>()
        for (id in keyed) {
            if (id !in parentOf) continue
            keep += id
            var p = parentOf[id]
            val seen = HashSet<Int>()
            while (p != null && p >= 0 && seen.add(p)) { keep += p; p = parentOf[p] }
        }
        return rows.filter { it.layer.id in keep }
    }

    /** Слои из [ids] с ключами: дорожки слоя (трансформация, деформации, эффекты) или ключи времени символа. */
    fun keyed(ids: Collection<Int>, tracks: Map<Int, AnimationTrack>, timeKeys: Map<Int, List<*>> = emptyMap()): Set<Int> =
        ids.filterTo(HashSet()) { id -> timeKeys[id]?.isNotEmpty() == true || TrackGroups.frames(id, tracks).isNotEmpty() }

    /** Все кадры с ключами по набору слоёв/костей. */
    fun frames(ids: Collection<Int>, tracks: Map<Int, AnimationTrack>, timeKeyFrames: Map<Int, Collection<Int>> = emptyMap(),
               boneIds: Collection<Int> = emptyList()): Set<Int> {
        val out = HashSet<Int>()
        for (id in ids) { out += TrackGroups.frames(id, tracks); timeKeyFrames[id]?.let { out += it } }
        for (b in boneIds) tracks[b]?.keys?.mapTo(out) { it.frame }
        return out
    }

    /** Следующий/предыдущий кадр с ключом в пределах [lo, hi] или null. */
    fun step(frames: Collection<Int>, current: Int, forward: Boolean, lo: Int = 0, hi: Int = Int.MAX_VALUE): Int? =
        if (forward) frames.filter { it > current && it in lo..hi }.minOrNull()
        else frames.filter { it < current && it in lo..hi }.maxOrNull()
}

/**
 * Пакет 10: «Иерархия трансформаций» - дерево привязок (Parenting) «ребёнок -> родитель». Ребёнок повторяет сдвиг,
 * поворот и масштаб родителя поверх своей трансформации; группы дополнительно передают трансформацию вложенным слоям.
 */
object TransformHierarchy {
    data class Row(val id: Int, val depth: Int, val parent: Int?)

    /**
     * Дерево привязок обходом в глубину: корни - родители, которые сами ни к кому не привязаны (порядок [order]),
     * дети в порядке [order]. Узлы в цикле (повреждённые данные) показываются корнями один раз.
     */
    fun tree(links: Map<Int, Int>, order: List<Int>): List<Row> {
        if (links.isEmpty()) return emptyList()
        val rank = HashMap<Int, Int>().also { m -> order.forEachIndexed { i, id -> m.putIfAbsent(id, i) } }
        fun sorted(ids: Collection<Int>) = ids.sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { it }))
        val children = links.entries.groupBy({ it.value }, { it.key })
        val all = (links.keys + links.values).toSet()
        val out = ArrayList<Row>()
        val seen = HashSet<Int>()
        fun visit(id: Int, depth: Int, parent: Int?) {
            if (!seen.add(id)) return
            out += Row(id, depth, parent)
            for (c in sorted(children[id].orEmpty())) visit(c, depth + 1, id)
        }
        for (r in sorted(all.filter { it !in links })) visit(r, 0, null)
        for (r in sorted(all)) if (r !in seen) visit(r, 0, null)   // циклы
        return out
    }

    /** Цепочка привязок узла снизу вверх (без самого узла), с защитой от циклов. */
    fun chain(id: Int, links: Map<Int, Int>): List<Int> {
        val out = ArrayList<Int>()
        val seen = hashSetOf(id)
        var p = links[id]
        while (p != null && seen.add(p)) { out += p; p = links[p] }
        return out
    }

    /** Все потомки узла по привязкам. */
    fun descendants(id: Int, links: Map<Int, Int>): Set<Int> {
        val out = HashSet<Int>()
        do {
            val before = out.size
            for ((c, p) in links) if (p == id || p in out) out += c
        } while (out.size != before)
        out -= id
        return out
    }
}

/** Пакет 10: «Расширить клип» - до конца активной сцены (или проекта); начало сдвигается только у простых клипов. */
object ClipExtend {
    fun extend(start: Int, end: Int, rangeStart: Int, rangeEnd: Int, plain: Boolean): Pair<Int, Int> {
        val s = if (plain && start > rangeStart) rangeStart else start
        val e = maxOf(end, rangeEnd)
        return s to e
    }
}
