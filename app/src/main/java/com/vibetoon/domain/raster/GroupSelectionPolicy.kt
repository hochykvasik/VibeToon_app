package com.vibetoon.domain.raster

import com.vibetoon.nativebridge.LayerInfo
import com.vibetoon.nativebridge.LayerKind

/** Безопасная цепочка родителей для отделения: новый PNG становится соседом исходного слоя. */
object GroupSelectionPolicy {
    fun supports(id: Int, layers: List<LayerInfo>, mediaGroupIds: Set<Int>, hidden: Set<Int>, locked: Set<Int>): Boolean {
        val byId = layers.associateBy { it.id }
        val source = byId[id] ?: return false
        if (!source.isVisible || id in hidden || id in locked) return false
        val visited = hashSetOf(id)
        var parent = source.parentId
        while (parent != -1) {
            if (!visited.add(parent)) return false
            val group = byId[parent] ?: return false
            if (parent !in mediaGroupIds || group.kind !in setOf(LayerKind.GROUP_OPEN, LayerKind.GROUP_CLOSED) ||
                !group.isVisible || parent in hidden || parent in locked) return false
            parent = group.parentId
        }
        return true
    }
}
