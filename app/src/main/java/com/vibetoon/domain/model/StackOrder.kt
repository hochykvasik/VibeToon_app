package com.vibetoon.domain.model

import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.nativebridge.LayerInfo
import com.vibetoon.nativebridge.LayerKind

/**
 * Пакет 25: смешанный порядок слоёв PSD и медиа в одной группе. Порядок записей PSD в файле и порядок списка медиа
 * задают порядок по умолчанию (медиа над слоями PSD своей группы). Явный порядок детей группы ([order]: id родителя,
 * -1 - корень -> дети снизу вверх) перекрывает его: слой PSD можно поставить над картинкой, картинку - между слоями
 * PSD, фон - под всеми слоями PSD. Дети, которых нет в явном списке (новые слои), идут над перечисленными в порядке
 * по умолчанию. Устаревшие id в списках ни на что не влияют. Чистые функции - unit-тест.
 */
object StackOrder {
    const val ROOT = -1

    private fun isChild(l: LayerInfo) = l.kind != LayerKind.GROUP_END

    /**
     * Список [layers] с переставленными детьми групп, у которых есть явный порядок. Меняются только позиции братьев
     * между собой (каждый на месте, где в списке стоял один из братьев) - остальная структура списка прежняя.
     */
    fun apply(layers: List<LayerInfo>, order: Map<Int, List<Int>>): List<LayerInfo> {
        if (order.isEmpty()) return layers
        val out = layers.toMutableList()
        val slots = HashMap<Int, MutableList<Int>>()   // родитель -> позиции его детей в списке
        layers.forEachIndexed { i, l -> if (isChild(l) && order.containsKey(l.parentId)) slots.getOrPut(l.parentId) { ArrayList() } += i }
        for ((parent, pos) in slots) {
            val rank = HashMap<Int, Int>()
            order[parent]!!.forEachIndexed { i, id -> rank.putIfAbsent(id, i) }
            val kids = pos.map { layers[it] }
            val sorted = kids.withIndex().sortedWith(compareBy({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
            pos.forEachIndexed { k, p -> out[p] = sorted[k] }
        }
        return out
    }

    /** Дети [parent] снизу вверх в текущем списке (уже с применённым порядком). */
    fun children(layers: List<LayerInfo>, parent: Int): List<Int> = layers.filter { it.parentId == parent && isChild(it) }.map { it.id }

    /** [id] ставится над ([above]) или под [target]; оба - дети одного родителя. null - ничего не меняется. */
    fun place(layers: List<LayerInfo>, order: Map<Int, List<Int>>, id: Int, target: Int, above: Boolean): Map<Int, List<Int>>? {
        if (id == target) return null
        val byId = layers.associateBy { it.id }
        val parent = byId[target]?.parentId ?: return null
        if (byId[id]?.parentId != parent) return null
        val kids = children(layers, parent)
        val rest = kids.filter { it != id }.toMutableList()
        val t = rest.indexOf(target)
        if (t < 0) return null
        rest.add(if (above) t + 1 else t, id)
        if (rest == kids) return null
        return order + (parent to rest)
    }

    /** Правило перетаскивания в списке: слой, тянутый снизу, встаёт над целью, тянутый сверху - под неё. */
    fun drop(layers: List<LayerInfo>, order: Map<Int, List<Int>>, id: Int, target: Int): Map<Int, List<Int>>? {
        val byId = layers.associateBy { it.id }
        val parent = byId[target]?.parentId ?: return null
        val kids = children(layers, parent)
        val from = kids.indexOf(id)
        val to = kids.indexOf(target)
        if (from < 0 || to < 0) return null
        return place(layers, order, id, target, above = from < to)
    }

    /** «На самый верх / самый низ группы». */
    fun toEdge(layers: List<LayerInfo>, order: Map<Int, List<Int>>, id: Int, top: Boolean): Map<Int, List<Int>>? {
        val parent = layers.firstOrNull { it.id == id }?.parentId ?: return null
        val kids = children(layers, parent)
        if (id !in kids) return null
        val rest = kids.filter { it != id }
        val next = if (top) rest + id else listOf(id) + rest
        if (next == kids) return null
        return order + (parent to next)
    }

    /**
     * Новая группа [gid] из [ids] (могут быть и слои PSD, и медиа): внутри - в прежнем видимом порядке, сама группа
     * встаёт на место самого верхнего из них в родителе [parent]. [layersAfter] - список после переноса в группу.
     */
    fun group(layersBefore: List<LayerInfo>, layersAfter: List<LayerInfo>, order: Map<Int, List<Int>>, ids: Set<Int>, gid: Int, parent: Int): Map<Int, List<Int>> {
        val before = children(layersBefore, parent)
        // видимый порядок выбранных: снизу вверх по строкам дерева
        val inner = visual(layersBefore).filter { it in ids }
        val out = order.toMutableMap()
        out[gid] = inner
        val top = before.lastOrNull { it in ids }
        if (top != null) {
            val list = before.filter { it !in ids || it == top }.map { if (it == top) gid else it }
            val after = children(layersAfter, parent)
            out[parent] = list + after.filter { it !in list }   // прочие (если были) - сверху, как по умолчанию
        }
        return out
    }

    /** Все узлы снизу вверх (обход дерева: группа, затем её дети снизу вверх). */
    fun visual(layers: List<LayerInfo>): List<Int> {
        val byParent = layers.filter(::isChild).groupBy { it.parentId }
        val out = ArrayList<Int>()
        val seen = HashSet<Int>()
        fun walk(p: Int) { for (l in byParent[p].orEmpty()) if (seen.add(l.id)) { out += l.id; walk(l.id) } }
        walk(ROOT)
        return out
    }

    /** Новый слой [newId] встаёт на место [at] среди [childrenBefore] (снизу вверх), слои [removed] уходят из списка. */
    fun substitute(childrenBefore: List<Int>, removed: Set<Int>, at: Int, newId: Int): List<Int> =
        childrenBefore.filter { it !in removed || it == at }.map { if (it == at) newId else it }

    /** Слой [id] - в самый низ родителя [parent] (фон под всеми слоями PSD). */
    fun bottom(layers: List<LayerInfo>, order: Map<Int, List<Int>>, id: Int, parent: Int = ROOT): Map<Int, List<Int>> =
        order + (parent to (listOf(id) + children(layers, parent).filter { it != id }))

    /** Убирает удалённые узлы: из списков и пустые/исчезнувшие группы. */
    fun prune(order: Map<Int, List<Int>>, existing: Set<Int>): Map<Int, List<Int>> {
        val out = LinkedHashMap<Int, List<Int>>()
        for ((p, list) in order) {
            if (p != ROOT && p !in existing) continue
            val l = list.filter { it in existing }.distinct()
            if (l.isNotEmpty()) out[p] = l
        }
        return out
    }

    /** Новый PSD проекта: id записей PSD теперь значат другие слои - они уходят из порядка (кроме групп-медиа [keep]). */
    fun dropPsd(order: Map<Int, List<Int>>, keep: Set<Int>): Map<Int, List<Int>> {
        fun ok(id: Int) = id >= MEDIA_ID_BASE || id in keep
        val out = LinkedHashMap<Int, List<Int>>()
        for ((p, list) in order) {
            if (p != ROOT && !ok(p)) continue
            val l = list.filter(::ok)
            if (l.isNotEmpty()) out[p] = l
        }
        return out
    }

    /** Смешан ли порядок: в одном родителе есть слой PSD над медиа-слоем (для сообщений и тестов). */
    fun mixed(layers: List<LayerInfo>, parent: Int): Boolean {
        val kids = children(layers, parent)
        val firstMedia = kids.indexOfFirst { it >= MEDIA_ID_BASE }
        return firstMedia >= 0 && kids.drop(firstMedia).any { it < MEDIA_ID_BASE }
    }
}
