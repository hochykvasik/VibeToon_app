package com.vibetoon

import com.vibetoon.domain.anim.PerspectiveMath
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.render.GroupDeformationMesh
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.*
import org.junit.Test

class GroupDeformationMeshTest {
    @Test fun commonWarpIsProjectedIntoEachChildAndSceneMeshBuffers() {
        val bounds = GroupDeformationMesh.Bounds(-100f, -50f, 400f, 200f)
        val warp = FloatArray(32) { i -> if (i % 2 == 0) .125f else -.1f }
        val a = Affine2D.translate(-100f, -50f) * Affine2D.scale(200f, 200f)
        val b = Affine2D.translate(100f, -50f) * Affine2D.scale(200f, 200f)
        val meshA = GroupDeformationMesh.build(bounds, a, warp, null, 4)!!
        val meshB = GroupDeformationMesh.build(bounds, b, warp, null, 4)!!
        for ((m, mat) in listOf(meshA to a, meshB to b)) for (i in 0 until m.mesh.count) {
            val u = m.mesh.u(i); val v = m.mesh.v(i)
            assertEquals(mat.applyX(u, v) + 50f, mat.applyX(u + m.offsets[i * 2], v + m.offsets[i * 2 + 1]), .001f)
            assertEquals(mat.applyY(u, v) - 20f, mat.applyY(u + m.offsets[i * 2], v + m.offsets[i * 2 + 1]), .001f)
        }
        val leaves = listOf(LeafNode(1, "a", true, 1f, "norm", false, -100, -50, 200, 200), LeafNode(2, "b", true, 1f, "norm", false, 100, -50, 200, 200))
        val scene = SceneBuilder.buildTree(leaves, 100, 100, preparedGroupMeshes = mapOf(1 to meshA, 2 to meshB))
        assertEquals(setOf(1, 2), scene.meshIds.toSet())
        assertEquals(25, scene.meshCounts[0]); assertEquals(32, scene.meshCounts[1])
        assertEquals(200, scene.meshVerts.size)
        val untouched = SceneBuilder.buildTree(leaves, 100, 100)
        assertTrue(untouched.meshIds.isEmpty())
    }

    @Test fun perspectiveMovesVerticesAndRejectsInvalidOrConflictingGeometry() {
        val bounds = GroupDeformationMesh.Bounds(10f, 20f, 200f, 100f)
        val affine = Affine2D.translate(10f, 20f) * Affine2D.scale(200f, 100f)
        val off = floatArrayOf(0f, 0f, .2f, 0f, .2f, .1f, 0f, 0f)
        val m = GroupDeformationMesh.build(bounds, affine, null, PerspectiveMath.matrix(off), 2)!!
        val last = m.mesh.count - 1
        assertEquals(1.2f, m.mesh.u(last) + m.offsets[last * 2], .001f)
        assertEquals(1.1f, m.mesh.v(last) + m.offsets[last * 2 + 1], .001f)
        assertNull(GroupDeformationMesh.build(bounds, Affine2D.scale(0f, 1f), null, null))
        assertNull(GroupDeformationMesh.build(bounds, Affine2D.translate(1000f, 0f), null, null))
        val n = LeafNode(1, "a", true, 1f, "norm", false, 10, 20, 200, 100)
        // спринт 43: готовая групповая сетка и Warp самого слоя складываются, а не отклоняют сцену
        val sc = SceneBuilder.buildTree(listOf(n), 100, 100, warps = mapOf(1 to FloatArray(32)), preparedGroupMeshes = mapOf(1 to m))
        assertArrayEquals(intArrayOf(1), sc.meshIds)
        assertEquals(0, sc.warpIds.size)
        assertEquals(m.mesh.count, sc.meshCounts[0])
    }
    @Test fun projectivePoleBetweenSampledVerticesIsRejected() {
        val bounds = GroupDeformationMesh.Bounds(0f, 0f, 100f, 100f)
        val world = Affine2D.scale(100f, 100f)
        // z = u - .25; neither corner sample is singular, but the interior is.
        val pole = floatArrayOf(1f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, -.25f)
        assertNull(GroupDeformationMesh.build(bounds, world, null, pole, 1))
    }

    @Test fun nonlinearWarpPoleIsRejectedEvenWhenAllMeshCornersAreSafe() {
        val bounds = GroupDeformationMesh.Bounds(0f, 0f, 100f, 100f)
        val world = Affine2D.scale(100f, 100f)
        val warp = FloatArray(32)
        for (y in 0..3) { warp[(y * 4 + 1) * 2] = -3f; warp[(y * 4 + 2) * 2] = -3f }
        // z = 1 + u + warp.x. Both u=0 and u=1 samples have z>0,
        // while the middle is negative: poles lie between the mesh vertices.
        val perspective = floatArrayOf(1f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertNull(GroupDeformationMesh.build(bounds, world, warp, perspective, 1))
        assertNotNull(GroupDeformationMesh.build(bounds, world, null, perspective, 1))
        val negativeZ = floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        assertNotNull(GroupDeformationMesh.build(bounds, world, null, negativeZ, 2))
    }
    @Test fun coefficientProofCanConservativelyRejectPoleFreeSurface() {
        val bounds = GroupDeformationMesh.Bounds(0f, 0f, 100f, 100f)
        val world = Affine2D.scale(100f, 100f)
        val warp = FloatArray(32)
        for (y in 0..3) { warp[(y * 4 + 1) * 2] = -1.4f }
        // z coefficients have different signs; that does not prove a pole exists.
        val matrix = floatArrayOf(1f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertNull(GroupDeformationMesh.build(bounds, world, warp, matrix, 8))
    }

}
