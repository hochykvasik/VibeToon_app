package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.model.*
import com.vibetoon.domain.project.*
import com.vibetoon.domain.render.*
import org.junit.Assert.*
import org.junit.Test

class DenseWarpGridTest {
    @Test fun maxGridCreatesActualMeshAndSupportsSavedProject() {
        val id = 100001; val p = MutableList(33 * 33 * 2) { 0f }
        val vertex = 16 * 33 + 16; p[vertex * 2] = .25f; p[vertex * 2 + 1] = -.2f
        val grid = DenseWarpGrid(33, 33, p.toList())
        val leaf = LeafNode(id, "a", true, 1f, "norm", false, -100, -80, 100, 100)
        val meta = ProjectMeta("test", "test", 100, 100)
        val oldBase = mapOf(WarpMath.pointId(id, 0) to LayerTransform(dx = .1f))
        val data = ProjectData(meta, base = oldBase, warps = setOf(id), denseWarps = mapOf(id to grid))
        val encoded = ProjectJson.encode(data); assertTrue(encoded.contains("\"version\": 4"))
        val restored = ProjectJson.decode(encoded).getOrThrow(); assertEquals(grid, restored.denseWarps[id])
        fun scene(d: ProjectData): SceneData {
            val inputs = GroupSceneInputs.build(d.groupDeformationAnchors, d.warps, d.persps, 0, d.base, d.animation(), true, d.warpGridSizes)
            return SceneBuilder.buildTree(listOf(leaf), 100, 100, warps = inputs.warps, denseWarps = d.denseWarps)
        }
        val editor = scene(data); val exported = scene(restored)
        assertTrue(editor.warpIds.isEmpty()); assertArrayEquals(intArrayOf(id), editor.meshIds)
        assertEquals(1089, editor.meshCounts[0]); assertArrayEquals(editor.meshVerts, exported.meshVerts, 0f)
        val baseAtMid = WarpGridMath.evaluate(WarpMath.offsets(id, 0, oldBase, Animation(), true), 4, .5f, .5f)!!
        assertEquals(baseAtMid.first + .25f, editor.meshVerts[vertex * 4 + 2], .00001f)
        assertEquals(baseAtMid.second - .2f, editor.meshVerts[vertex * 4 + 3], .00001f)
        assertTrue(SceneBuilder.buildTree(listOf(leaf), 100, 100, warps = mapOf(id to FloatArray(32))).meshIds.isEmpty())
    }
    @Test fun resizesOnlyWhenDetailSurfaceCanBePreserved() {
        val initial = DenseWarpGrid.zero(5, 4)
        assertNotNull(initial.resized(33, 33))
        val p = initial.offsets.toMutableList(); p[(1 * 5 + 1) * 2] = .8f
        val edited = initial.copy(offsets = p)
        assertNull(edited.resized(6, 4))
        assertNotNull(edited.resized(9, 7))
        assertEquals(.8f, edited.offsets[(1 * 5 + 1) * 2], 0f)
    }
    @Test fun effectsCombineAndOldVersionsDoNotReadDenseFormat() {
        val id = 100001; val leaf = LeafNode(id, "a", true, 1f, "norm", false, 0, 0, 100, 100)
        val grid = DenseWarpGrid.zero(5, 4)
        // Спринт 43: плотный Warp вместе с эффектами больше не отклоняется - сетка и эффект остаются оба
        val withFx = SceneBuilder.buildTree(listOf(leaf), 100, 100, warps = mapOf(id to FloatArray(32)),
            denseWarps = mapOf(id to grid), fx = mapOf(id to listOf(FloatArray(10))))
        assertEquals(listOf(id), withFx.meshIds.toList()); assertEquals(listOf(id), withFx.fxIds.toList())
        val body = "{\"version\":3,\"id\":\"p\",\"warps\":[$id],\"denseWarps\":{\"$id\":{\"w\":5,\"h\":4,\"p\":[]}}}"
        assertTrue(ProjectJson.decode(body).isFailure)
    }
}
