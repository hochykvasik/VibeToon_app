package com.vibetoon

import com.vibetoon.domain.model.LayerTree
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerTreeTest {
    private fun rec(id: Int, name: String, kind: Int, parent: Int, w: Int = 10) =
        LayerInfo(id, name, 0, 0, w, 10, 1f, true, kind, parent, "norm", false)

    // Снизу вверх: фон; группа A [divider, a1, внутренняя B [divider, b1, B], a2, A]; верхний слой
    private val layers = listOf(
        rec(0, "bg", 0, -1),
        rec(1, "</A>", 3, 6),
        rec(2, "a1", 0, 6),
        rec(3, "</B>", 3, 5),
        rec(4, "b1", 0, 5),
        rec(5, "B", 2, 6),             // группа B свёрнута в файле
        rec(6, "A", 1, -1),
        rec(7, "top", 0, -1),
        rec(8, "empty", 0, -1, w = 0)
    )

    @Test fun nestingAndTopDownOrder() {
        val rows = LayerTree.flatten(layers, collapsed = emptySet())
        assertEquals(listOf("top", "A", "B", "b1", "a1", "bg"), rows.map { it.layer.name })
        assertEquals(listOf(0, 0, 1, 2, 1, 0), rows.map { it.depth })
    }

    @Test fun collapsedGroupHidesChildren() {
        val rows = LayerTree.flatten(layers, collapsed = setOf(6))
        assertEquals(listOf("top", "A", "bg"), rows.map { it.layer.name })
        assertEquals(false, rows[1].expanded)
    }

    @Test fun dividersAndEmptyLayersAreHidden() {
        val names = LayerTree.flatten(layers, emptySet()).map { it.layer.name }
        assertEquals(false, names.contains("</A>"))
        assertEquals(false, names.contains("empty"))
    }

    @Test fun closedGroupsStartCollapsed() = assertEquals(setOf(5), LayerTree.defaultCollapsed(layers))

    @Test fun effectiveVisibilityChecksEveryAncestorAndUserHiddenState() {
        val byId = layers.associateBy { it.id }
        // Свёрнутая в панели группа сама по себе не скрывает содержимое.
        assertEquals(true, LayerTree.isEffectivelyVisible(4, byId))
        assertEquals(false, LayerTree.isEffectivelyVisible(4, byId, setOf(4)))
        assertEquals(false, LayerTree.isEffectivelyVisible(4, byId, setOf(5)))
        assertEquals(false, LayerTree.isEffectivelyVisible(4, byId, setOf(6)))
        assertEquals(true, LayerTree.isEffectivelyVisible(2, byId, setOf(5)))
        val hiddenGroup = layers.map { if (it.id == 6) it.copy(isVisible = false) else it }.associateBy { it.id }
        assertEquals(false, LayerTree.isEffectivelyVisible(4, hiddenGroup))
        val hiddenLayer = layers.map { if (it.id == 4) it.copy(isVisible = false) else it }.associateBy { it.id }
        assertEquals(false, LayerTree.isEffectivelyVisible(4, hiddenLayer))
    }

    @Test fun malformedHierarchyFailsClosed() {
        val byId = layers.associateBy { it.id }
        assertEquals(false, LayerTree.isEffectivelyVisible(99, byId))
        assertEquals(false, LayerTree.isEffectivelyVisible(4, byId - 5))
        assertEquals(false, LayerTree.isEffectivelyVisible(4, byId + (6 to byId.getValue(6).copy(parentId = 5))))
        assertEquals(false, LayerTree.isEffectivelyVisible(4, byId + (5 to byId.getValue(5).copy(parentId = 2))))
    }
}
