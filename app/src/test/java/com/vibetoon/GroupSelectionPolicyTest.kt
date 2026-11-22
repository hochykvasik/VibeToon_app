package com.vibetoon

import com.vibetoon.domain.raster.GroupSelectionPolicy
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.project.*
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.*
import org.junit.Test

class GroupSelectionPolicyTest {
    private fun layer(id: Int, parent: Int, group: Boolean = false) = LayerInfo(id, "n$id", 10, 20, 40, 60, 1f, true, if (group) 1 else 0, parent, "norm", false)
    private val layers = listOf(layer(100000, -1, true), layer(100001, 100000, true), layer(100002, 100001), layer(100003, 100001))
    private val groups = setOf(100000, 100001)
    @Test fun acceptsNestedMediaGroupsRejectsHiddenLockedMissingAndCyclicParents() {
        assertTrue(GroupSelectionPolicy.supports(100002, layers, groups, emptySet(), emptySet()))
        assertFalse(GroupSelectionPolicy.supports(100002, layers, groups, setOf(100000), emptySet()))
        assertFalse(GroupSelectionPolicy.supports(100002, layers, groups, emptySet(), setOf(100001)))
        assertFalse(GroupSelectionPolicy.supports(100002, layers, setOf(100001), emptySet(), emptySet()))
        assertFalse(GroupSelectionPolicy.supports(100002, layers.filter { it.id != 100001 }, groups, emptySet(), emptySet()))
        val cycle = layers.map { if (it.id == 100000) it.copy(parentId = 100001) else it }
        assertFalse(GroupSelectionPolicy.supports(100002, cycle, groups, emptySet(), emptySet()))
    }
    @Test fun siblingHasSameWorldQuadAndDoesNotMoveTransformedGroup() {
        val transform = mapOf(100000 to LayerTransform(dx = 12f, scaleX = 1.4f, rotation = .3f),
            100001 to LayerTransform(dy = -6f, scaleY = .7f, rotation = -.2f),
            100002 to LayerTransform(dx = 4f, rotation = .5f))
        val before = SceneBuilder.build(layers, 100, 100, transform)
        val inserted = layers.toMutableList().also { it.add(3, layer(100004, 100001)) }
        val after = SceneBuilder.build(inserted, 100, 100, transform + (100004 to transform.getValue(100002)))
        assertArrayEquals(before.selectionQuads.getValue(100002), after.selectionQuads.getValue(100002), .001f)
        assertArrayEquals(after.selectionQuads.getValue(100002), after.selectionQuads.getValue(100004), .001f)
        assertArrayEquals(before.selectionQuads.getValue(100003), after.selectionQuads.getValue(100003), .001f)
        val project = ProjectData(ProjectMeta("p", "test", 100, 100), regroup = listOf(100001 to 100000, 100002 to 100001, 100004 to 100001))
        assertEquals(project.regroup, ProjectJson.decode(ProjectJson.encode(project)).getOrThrow().regroup)
    }
}
