package com.vibetoon

import com.vibetoon.domain.model.*
import com.vibetoon.domain.render.*
import com.vibetoon.domain.anim.PerspectiveMath
import org.junit.Assert.*
import org.junit.Test

class GroupDeformationSceneTest {
    private fun leaf(id: Int, x: Int) = LeafNode(id, "leaf", true, 1f, "norm", false, x, -50, 100, 100)
    private val a = leaf(1, -100); private val b = leaf(2, 0)
    private val inner = GroupNode(11, "inner", true, 1f, "pass", listOf(b), 50f, 0f)
    private val group = GroupNode(10, "group", true, 1f, "pass", listOf(a, inner), 0f, 0f)
    private val bounds = GroupDeformationMesh.Bounds(-100f, -50f, 200f, 100f)
    private fun definition() = SceneBuilder.GroupDeformation(bounds, FloatArray(32) { if (it % 2 == 0) .1f else -.1f }, cells = 2)
    private fun meshDisplacement(scene: SceneData, id: Int): Pair<Float, Float> {
        val meshIndex = scene.meshIds.indexOf(id)
        assertTrue(meshIndex >= 0)
        val vertex = (0 until meshIndex).sumOf { scene.meshCounts[it * 2] } * 4
        val op = (scene.ops.indices step 4).first { scene.ops[it] == SceneBuilder.OP_LAYER && scene.ops[it + 1] == id } / 4
        val p = op * SceneData.PARAM_STRIDE
        val dx = scene.meshVerts[vertex + 2]; val dy = scene.meshVerts[vertex + 3]
        return (scene.params[p] * dx + scene.params[p + 2] * dy) to (scene.params[p + 1] * dx + scene.params[p + 3] * dy)
    }
    @Test fun fixedLocalFrameDeformsNestedLeavesTogetherOutsideCanvasAndFollowsGroupTransform() {
        val roots = listOf(group)
        val groups = mapOf(10 to definition())
        val scene = SceneBuilder.buildTree(roots, 50, 50, groupDeformations = groups)
        assertEquals(setOf(1, 2), scene.meshIds.toSet())
        for (id in listOf(1, 2)) {
            val (dx, dy) = meshDisplacement(scene, id)
            assertEquals(20f, dx, .001f); assertEquals(-10f, dy, .001f)
        }
        val rotated = SceneBuilder.buildTree(roots, 50, 50, mapOf(10 to LayerTransform(rotation = (Math.PI / 2).toFloat())), groupDeformations = groups)
        for (id in listOf(1, 2)) {
            val (dx, dy) = meshDisplacement(rotated, id)
            assertEquals(10f, dx, .001f); assertEquals(20f, dy, .001f)
        }
        assertTrue(SceneBuilder.buildTree(roots, 50, 50).meshIds.isEmpty())
    }
    @Test fun perspectiveAndUnsafeCases() {
        val perspective = SceneBuilder.GroupDeformation(bounds, perspective = PerspectiveMath.matrix(floatArrayOf(0f,0f,.2f,0f,.2f,.1f,0f,0f)), cells = 2)
        val scene = SceneBuilder.buildTree(listOf(group), 50, 50, groupDeformations = mapOf(10 to perspective))
        assertEquals(2, scene.meshIds.size)
        fun refused(action: () -> Unit) { try { action(); fail("expected rejection") } catch (_: IllegalArgumentException) {} }
        refused { SceneBuilder.buildTree(listOf(group), 50, 50, warps = mapOf(1 to FloatArray(32)), groupDeformations = mapOf(10 to definition())) }
        val orphanClip = group.copy(children = listOf(a.copy(clipped = true)))
        refused { SceneBuilder.buildTree(listOf(orphanClip), 50, 50, groupDeformations = mapOf(10 to definition())) }
        refused { SceneBuilder.buildTree(listOf(group), 50, 50, groupDeformations = mapOf(10 to definition(), 11 to definition())) }
        refused { SceneBuilder.buildTree(listOf(group), 50, 50, groupDeformations = mapOf(100 to definition())) }
        refused { SceneBuilder.buildTree(listOf(group), 50, 50, groupDeformations = mapOf(10 to definition().copy(bounds = GroupDeformationMesh.Bounds(0f,0f,1f,1f)))) }
    }
}
