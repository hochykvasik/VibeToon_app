package com.vibetoon

import com.vibetoon.domain.model.GroupNode
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.nativebridge.LayerInfo
import com.vibetoon.nativebridge.LayerKind
import org.junit.Assert.*
import org.junit.Test

class PivotProjectTest {
    private val meta = ProjectMeta("p", "test", 100, 100)

    @Test fun pivotsRoundTripAsV5() {
        val pivots = mapOf(17 to (12.5f to 30f), 20 to (-5f to 0f))
        val text = ProjectJson.encode(ProjectData(meta, pivots = pivots))
        assertTrue(text.contains("\"version\": 5"))
        assertEquals(pivots, ProjectJson.decode(text).getOrThrow().pivots)
        assertEquals(pivots, ProjectJson.decode(ProjectJson.encode(ProjectJson.decode(text).getOrThrow())).getOrThrow().pivots)
    }

    @Test fun legacyFilesHaveNoPivots() {
        val old = ProjectJson.encode(ProjectData(meta))
        assertTrue(old.contains("\"version\": 1"))
        assertTrue(ProjectJson.decode(old).getOrThrow().pivots.isEmpty())
    }

    @Test fun corruptPivotsAreRejected() {
        for (body in listOf("{\"17\":[0]}", "{\"17\":[0,0,0]}", "{\"17\":[0,\"x\"]}", "{\"17\":[1e300,0]}", "[]")) {
            assertTrue(ProjectJson.decode("{\"version\":5,\"id\":\"p\",\"pivots\":$body}").isFailure)
        }
        assertTrue(ProjectJson.decode("{\"version\":1,\"id\":\"p\",\"pivots\":{\"17\":[0,0]}}").isFailure)
        try { ProjectJson.encode(ProjectData(meta, pivots = mapOf(1 to (Float.NaN to 0f)))); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun groupPivotOverrideMovesRotationCenter() {
        val a = LeafNode(1, "a", true, 1f, "norm", false, 0, 0, 40, 40)
        val b = LeafNode(2, "b", true, 1f, "norm", false, 60, 0, 40, 40)
        val group = GroupNode(10, "g", true, 1f, "pass", listOf(a, b), 50f, 20f)
        val layers = listOf(
            LayerInfo(1, "a", 0, 0, 40, 40, 1f, true, 0, 10, "norm", false),
            LayerInfo(2, "b", 60, 0, 40, 40, 1f, true, 0, 10, "norm", false),
            LayerInfo(10, "g", 0, 0, 0, 0, 1f, true, 2, -1, "pass", false)
        )
        val roots = com.vibetoon.domain.model.SceneTree.build(layers, mapOf(10 to (0f to 0f)))
        val g = roots.single() as GroupNode
        assertEquals(0f, g.pivotX, 1e-3f); assertEquals(0f, g.pivotY, 1e-3f)
        val sc = SceneBuilder.buildTree(roots, 200, 200, mapOf(10 to com.vibetoon.domain.render.LayerTransform(rotation = Math.PI.toFloat() / 2f)))
        // поворот на 90° вокруг (0,0): центр группы (50,20) -> (-20,50); рамка группы = границы детей
        val q = sc.selectionQuads.getValue(10)
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        assertEquals(-20f, cx, 1.5f); assertEquals(50f, cy, 1.5f)
    }

    @Test fun leafPivotOverrideChangesTransformCenter() {
        val leaf = LeafNode(1, "a", true, 1f, "norm", false, 0, 0, 100, 100)
        val scDefault = SceneBuilder.buildTree(listOf(leaf), 200, 200, mapOf(1 to com.vibetoon.domain.render.LayerTransform(scaleX = 2f, scaleY = 2f)))
        val scPivot = SceneBuilder.buildTree(listOf(leaf), 200, 200, mapOf(1 to com.vibetoon.domain.render.LayerTransform(scaleX = 2f, scaleY = 2f)), leafPivots = mapOf(1 to (0f to 0f)))
        // вокруг центра (50,50): (-50,-50)..(150,150); вокруг (0,0): (0,0)..(200,200)
        val qd = scDefault.selectionQuads.getValue(1)
        val qp = scPivot.selectionQuads.getValue(1)
        assertEquals(-50f, qd[0], 1e-2f)
        assertEquals(0f, qp[0], 1e-2f); assertEquals(200f, qp[2], 1e-2f)
    }

    @Test fun movingPivotDoesNotJumpImage() {
        val pm = com.vibetoon.domain.render.PivotMath
        val t = com.vibetoon.domain.render.LayerTransform(dx = 7f, dy = -3f, scaleX = 1.5f, scaleY = 0.5f, rotation = 0.7f)
        val before = pm.local(t, 50f, 50f)
        val t2 = pm.compensate(t, 50f, 50f, 10f, 90f)
        val after = pm.local(t2, 10f, 90f)
        for ((x, y) in listOf(0f to 0f, 100f to 0f, 33f to 77f)) {
            assertEquals(before.applyX(x, y), after.applyX(x, y), 1e-3f)
            assertEquals(before.applyY(x, y), after.applyY(x, y), 1e-3f)
        }
        // точка холста -> локальный пивот -> обратно на холст
        val loc = pm.toLocal(com.vibetoon.domain.model.Affine2D.IDENTITY, t, 50f, 50f, 120f, 40f)!!
        val t3 = pm.compensate(t, 50f, 50f, loc.first, loc.second)
        val w = pm.world(com.vibetoon.domain.model.Affine2D.IDENTITY, t3, loc.first, loc.second)
        assertEquals(120f, w.first, 1e-2f); assertEquals(40f, w.second, 1e-2f)
    }

    @Test fun keyframeCompensationMatchesTransform() {
        val pm = com.vibetoon.domain.render.PivotMath
        val k = com.vibetoon.domain.anim.Keyframe(4, 2f, 3f, 2f, 2f, 1.1f)
        val kk = pm.compensate(k, 0f, 0f, 30f, -10f)
        val tt = pm.compensate(k.toTransform(), 0f, 0f, 30f, -10f)
        assertEquals(tt.dx, kk.dx, 1e-4f); assertEquals(tt.dy, kk.dy, 1e-4f); assertEquals(4, kk.frame)
    }
}
