package com.vibetoon

import com.vibetoon.domain.model.*
import com.vibetoon.domain.render.*
import org.junit.Assert.*
import org.junit.Test

class GroupLocalBoundsTest {
    private fun leaf(id: Int, x: Int, y: Int) = LeafNode(id, "child", true, 1f, "norm", false, x, y, 100, 80)
    @Test fun fixedLocalBoundsIncludeNestedOffCanvasGeometryAndIgnoreGroupRotation() {
        val inner = GroupNode(11, "inner", true, 1f, "pass", listOf(leaf(2, 0, 20)), 50f, 60f)
        val group = GroupNode(10, "group", true, 1f, "pass", listOf(leaf(1, -100, -40), inner), 0f, 0f)
        val plain = SceneBuilder.buildTree(listOf(group), 50, 50)
        val b = plain.groupLocalBounds.getValue(10)
        assertEquals(-100f, b.x, .001f); assertEquals(-40f, b.y, .001f)
        assertEquals(200f, b.w, .001f); assertEquals(140f, b.h, .001f)
        val rotated = SceneBuilder.buildTree(listOf(group), 50, 50, mapOf(10 to LayerTransform(dx = 33f, dy = -17f, rotation = .73f, scaleX = 2f, scaleY = .5f)))
        val r = rotated.groupLocalBounds.getValue(10)
        assertEquals(b.x, r.x, .001f); assertEquals(b.y, r.y, .001f)
        assertEquals(b.w, r.w, .001f); assertEquals(b.h, r.h, .001f)
    }
    @Test fun emptyAndInvisibleGroupsHaveNoBounds() {
        val group = GroupNode(10, "empty", true, 1f, "pass", emptyList(), 0f, 0f)
        assertTrue(SceneBuilder.buildTree(listOf(group), 50, 50).groupLocalBounds.isEmpty())
        val hidden = group.copy(visible = false, children = listOf(leaf(1, 0, 0)))
        assertTrue(SceneBuilder.buildTree(listOf(hidden), 50, 50).groupLocalBounds.isEmpty())
    }
}
