package com.vibetoon.domain.anim

import com.vibetoon.domain.project.Bone

/**
 * Спринт 38: слой костей (MediaKind.BONES) - хозяин скелета в списке слоёв и на таймлайне. Кость помнит свой слой
 * ([Bone.layer]); корневые кости слоя привязаны к нему (links: кость -> слой), поэтому трансформация слоя костей двигает
 * весь скелет, а с ним и привязанные к костям слои. Глаз слоя прячет его кости на холсте (деформация остаётся),
 * удаление слоя удаляет его кости. Свободные кости (layer = 0) работают как раньше.
 */
object BoneLayers {
    fun owned(bones: List<Bone>, layer: Int): List<Bone> = bones.filter { it.layer == layer && layer != 0 }

    /** Слой для новой кости: выбранный/активный слой костей, иначе единственный в проекте, иначе 0 (свободная кость). */
    fun targetLayer(active: Int?, boneLayers: List<Int>): Int = when {
        active != null && active in boneLayers -> active
        boneLayers.size == 1 -> boneLayers[0]
        else -> 0
    }

    /** Корневые кости слоя (родитель - не кость) привязываются к слою костей; остальные привязки не меняются. */
    fun bindRoots(bones: List<Bone>, links: Map<Int, Int>, layer: Int): Map<Int, Int> {
        if (layer == 0) return links
        val ids = bones.mapTo(HashSet()) { it.id }
        val out = HashMap(links)
        for (b in owned(bones, layer)) {
            val p = links[b.id]
            if (p == null || p !in ids) out[b.id] = layer
        }
        return out
    }

    /** «Собрать свободные кости»: кости без слоя переходят в [layer], их корни привязываются к нему. */
    fun adopt(bones: List<Bone>, links: Map<Int, Int>, layer: Int): Pair<List<Bone>, Map<Int, Int>> {
        if (layer == 0) return bones to links
        val next = bones.map { if (it.layer == 0) it.copy(layer = layer) else it }
        return next to bindRoots(next, links, layer)
    }

    /** Удаление слоёв костей [layers]: кости этих слоёв и их привязки уходят. Возвращает кости, привязки и id удалённых костей. */
    fun removeLayers(bones: List<Bone>, links: Map<Int, Int>, layers: Set<Int>): Triple<List<Bone>, Map<Int, Int>, Set<Int>> {
        if (layers.isEmpty()) return Triple(bones, links, emptySet())
        val gone = bones.filter { it.layer in layers }.mapTo(HashSet()) { it.id }
        if (gone.isEmpty()) return Triple(bones, links.filter { (_, p) -> p !in layers }, gone)
        return Triple(bones.filter { it.id !in gone }, links.filter { (c, p) -> c !in gone && p !in gone && p !in layers }, gone)
    }

    /** Кости, видимые на холсте: кости скрытых слоёв костей не рисуются и не ловятся касанием. */
    fun visible(bones: List<Bone>, hiddenLayers: Set<Int>): List<Bone> =
        if (hiddenLayers.isEmpty()) bones else bones.filter { it.layer == 0 || it.layer !in hiddenLayers }

    /** Слой кости устарел (слой удалён): кость становится свободной. */
    fun orphaned(bones: List<Bone>, existingLayers: Set<Int>): List<Bone> =
        bones.map { if (it.layer != 0 && it.layer !in existingLayers) it.copy(layer = 0) else it }
}
