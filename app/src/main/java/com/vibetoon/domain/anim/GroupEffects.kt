package com.vibetoon.domain.anim

import com.vibetoon.nativebridge.LayerInfo
import com.vibetoon.nativebridge.LayerKind

/** Статическое пакетное редактирование одинаковых цепочек, без общей новой сущности проекта. */
object GroupEffects {
    enum class Rejection { NOT_GROUP, EMPTY, INVALID_TREE, UNSUPPORTED, LOCKED, CLIPPED, ANIMATED, DIFFERENT }
    data class Plan(val ids: List<Int>, val effects: List<Effect>, val rejection: Rejection? = null)

    fun plan(group: Int, layers: List<LayerInfo>, fx: Map<Int, List<Effect>>, tracks: Set<Int>,
             unsupported: Set<Int>, locked: Set<Int>, clipped: Set<Int>, allowAnimated: Boolean = false): Plan {
        fun reject(r: Rejection) = Plan(emptyList(), emptyList(), r)
        val byId = layers.associateBy { it.id }
        val root = byId[group] ?: return reject(Rejection.NOT_GROUP)
        if (root.kind != LayerKind.GROUP_OPEN && root.kind != LayerKind.GROUP_CLOSED) return reject(Rejection.NOT_GROUP)
        val byParent = layers.groupBy { it.parentId }
        val visited = HashSet<Int>()
        val leaves = ArrayList<Int>()
        fun walk(id: Int): Rejection? {
            if (!visited.add(id)) return Rejection.INVALID_TREE
            if (id in locked) return Rejection.LOCKED
            for (child in byParent[id].orEmpty()) {
                when (child.kind) {
                    LayerKind.GROUP_OPEN, LayerKind.GROUP_CLOSED -> walk(child.id)?.let { return it }
                    LayerKind.LAYER -> {
                        if (!visited.add(child.id)) return Rejection.INVALID_TREE
                        if (child.id in unsupported || child.id !in 0..EffectTracks.MAX_LAYER) return Rejection.UNSUPPORTED
                        if (child.id in locked) return Rejection.LOCKED
                        if (child.isClipped || child.id in clipped) return Rejection.CLIPPED
                        if (!allowAnimated && tracks.any { EffectTracks.belongs(it, child.id) }) return Rejection.ANIMATED
                        leaves.add(child.id)
                    }
                    else -> Unit
                }
            }
            return null
        }
        walk(group)?.let { return reject(it) }
        if (leaves.isEmpty()) return reject(Rejection.EMPTY)
        val common = fx[leaves.first()].orEmpty()
        if (leaves.any { fx[it].orEmpty() != common }) return reject(Rejection.DIFFERENT)
        return Plan(leaves, common)
    }

    // ---- Пакет 7: собственные эффекты-трансформации группы (Вращение, Качание, Ось растяжения).
    // Они хранятся под id САМОЙ группы и меняют её локальную матрицу вокруг пивота группы (SceneBuilder):
    // дети наследуют поворот/растяжение целиком, рисунок не разрывается на куски. Шейдерные эффекты группы
    // по-прежнему раздаются вложенным слоям (plan/apply выше). В инспекторе группы список = свои
    // трансформации (сверху) + общая цепочка вложенных слоёв.

    /** Собственные эффекты группы (туда добавляются только трансформации, см. [addsToGroupItself]). */
    fun own(fx: Map<Int, List<Effect>>, group: Int): List<Effect> = fx[group].orEmpty()

    /**
     * Куда идёт новый эффект группы. Спринт 50: любой эффект - на саму группу: трансформации меняют её матрицу, шейдерные
     * эффекты рендерер применяет к буферу группы целиком (холст, Play и экспорт), независимо от состава группы
     * (слои PSD, картинки, ключи, трансформации, обтравка). Прежняя общая цепочка вложенных слоёв остаётся редактируемой.
     */
    fun addsToGroupItself(type: Int): Boolean = EffectMath.isTransform(type) || EffectMath.def(type) != null

    /** Строка инспектора группы [index] -> (свой эффект?, индекс внутри своей части списка). */
    fun route(index: Int, ownCount: Int): Pair<Boolean, Int> =
        if (index < ownCount) true to index else false to (index - ownCount)

    /** Перестановка вверх/вниз возможна только внутри одной части списка (свои / вложенные). */
    fun canMove(index: Int, dir: Int, ownCount: Int, total: Int): Boolean {
        val j = index + dir
        if (index !in 0 until total || j !in 0 until total) return false
        return (index < ownCount) == (j < ownCount)
    }

    fun apply(fx: Map<Int, List<Effect>>, ids: List<Int>, effects: List<Effect>): Map<Int, List<Effect>> {
        val out = fx.toMutableMap()
        ids.forEach { if (effects.isEmpty()) out.remove(it) else out[it] = effects.toList() }
        return out
    }
}
