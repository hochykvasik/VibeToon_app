package com.vibetoon.domain.model

import com.vibetoon.nativebridge.LayerInfo
import com.vibetoon.nativebridge.LayerKind

/** Строка дерева слоёв: слой или группа, уровень вложенности, состояние группы. */
data class TreeRow(val layer: LayerInfo, val depth: Int, val isGroup: Boolean, val expanded: Boolean)

object LayerTree {
    /**
     * Видимость узла в дереве, включая все родительские группы и пользовательские скрытия.
     * Отсутствующий узел/родитель и циклическая иерархия считаются невидимыми.
     * Свёрнутость группы в панели не влияет на видимость на холсте.
     */
    fun isEffectivelyVisible(id: Int, layersById: Map<Int, LayerInfo>, hiddenIds: Set<Int> = emptySet()): Boolean {
        var current = id
        val visited = HashSet<Int>()
        var first = true
        while (current != -1) {
            if (!visited.add(current)) return false
            val layer = layersById[current] ?: return false
            if (!layer.isVisible || current in hiddenIds) return false
            if (!first && layer.kind != LayerKind.GROUP_OPEN && layer.kind != LayerKind.GROUP_CLOSED) return false
            first = false
            current = layer.parentId
        }
        return !first
    }

    /**
     * Строит плоский список строк для показа дерева (сверху вниз, как в Photoshop).
     * Записи PSD идут снизу вверх: у слоя parentId указывает на папку-группу, divider-ы
     * (конец группы) скрываются. Содержимое свёрнутых групп в список не попадает.
     */
    fun flatten(layers: List<LayerInfo>, collapsed: Set<Int>): List<TreeRow> {
        val children = layers.groupBy { it.parentId }
        val out = ArrayList<TreeRow>()
        fun walk(parent: Int, depth: Int) {
            // записи снизу вверх -> показываем сверху вниз
            for (l in children[parent].orEmpty().asReversed()) {
                when (l.kind) {
                    LayerKind.GROUP_END -> Unit
                    LayerKind.GROUP_OPEN, LayerKind.GROUP_CLOSED -> {
                        val expanded = l.id !in collapsed
                        out += TreeRow(l, depth, isGroup = true, expanded = expanded)
                        if (expanded) walk(l.id, depth + 1)
                    }
                    LayerKind.LAYER -> if (!l.isEmpty) out += TreeRow(l, depth, isGroup = false, expanded = false)
                }
            }
        }
        walk(-1, 0)
        return out
    }

    /** Группы, которые в файле сохранены свёрнутыми (как в Photoshop). */
    fun defaultCollapsed(layers: List<LayerInfo>): Set<Int> =
        layers.filter { it.kind == LayerKind.GROUP_CLOSED }.map { it.id }.toSet()
}
