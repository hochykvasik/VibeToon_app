package com.vibetoon

import com.vibetoon.domain.anim.BoneSeg
import com.vibetoon.domain.anim.Mesh
import com.vibetoon.domain.anim.SkinMath
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinTest {
    @Test
    fun weightsSumToOneAndFavourNearBone() {
        val a = BoneSeg(1, 0f, 0f, 100f, 0f)
        val b = BoneSeg(2, 0f, 300f, 100f, 300f)
        val w = SkinMath.weights(50f, 10f, listOf(a, b))
        assertEquals(1f, w.sumOf { it.second.toDouble() }.toFloat(), 1e-4f)
        val wa = w.first { it.first == 0 }.second
        val wb = w.first { it.first == 1 }.second
        assertTrue(wa > wb * 5)
    }

    @Test
    fun maxFourInfluences() {
        val bones = (0 until 6).map { BoneSeg(it, it * 50f, 0f, it * 50f + 30f, 0f) }
        assertEquals(4, SkinMath.weights(10f, 40f, bones).size)
    }

    @Test
    fun strengthShiftsWeight() {
        val a = BoneSeg(1, 0f, 0f, 100f, 0f, 1f)
        val b = BoneSeg(2, 0f, 100f, 100f, 100f, 3f)
        val w = SkinMath.weights(50f, 50f, listOf(a, b))
        assertTrue(w.first { it.first == 1 }.second > 0.7f)
    }

    @Test
    fun skinnedMeshFollowsAnimatedBoneAcrossFrames() {
        // тот же путь, что у Play и экспорта: pose кости на кадре -> buildTree -> смещения вершин
        val leaf = LeafNode(
            id = 0, name = "l", visible = true, opacity = 1f, blend = "norm", clipped = false,
            x = 0, y = 0, w = 100, h = 100
        )
        val mesh = Mesh(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))
        val bone = 200_000
        val anim = com.vibetoon.domain.anim.Animation(
            tracks = mapOf(bone to com.vibetoon.domain.anim.AnimationTrack(bone, listOf(
                com.vibetoon.domain.anim.Keyframe.of(0, LayerTransform(dx = 0f)),
                com.vibetoon.domain.anim.Keyframe.of(24, LayerTransform(dx = 20f))
            )))
        )
        fun dxAt(frame: Int): Float {
            val t = mapOf(bone to anim.evaluate(bone, frame, null))
            val sc = SceneBuilder.buildTree(
                listOf(leaf), 100, 100, t,
                bones = mapOf(bone to (0f to 0f)), boneTails = mapOf(bone to (100f to 0f)),
                meshes = mapOf(0 to (mesh to FloatArray(8))), skins = mapOf(0 to mapOf(bone to 1f))
            )
            return sc.meshVerts[2] * 100f   // dx первой вершины в пикселях
        }
        assertEquals(0f, dxAt(0), 1e-3f)
        assertEquals(10f, dxAt(12), 0.5f)
        assertEquals(20f, dxAt(24), 1e-3f)
    }

    @Test
    fun json_roundTrip() {
        val d = com.vibetoon.domain.project.ProjectData(
            com.vibetoon.domain.project.ProjectMeta(id = "p", name = "t", width = 10, height = 10),
            skins = mapOf(4 to mapOf(200_000 to 1f, 200_001 to 2.5f))
        )
        val back = com.vibetoon.domain.project.ProjectJson.decode(com.vibetoon.domain.project.ProjectJson.encode(d)).getOrThrow()
        assertEquals(d.skins, back.skins)
    }

    @Test
    fun noBonesKeepsPoint() {
        assertEquals(5f to 7f, SkinMath.skin(5f, 7f, emptyList(), emptyList()))
    }

    @Test
    fun boneRotationMovesSkinnedMeshVertices() {
        // слой 100x100 в (0,0); кость вдоль верха слоя, сустав в (0,0)
        val leaf = LeafNode(
            id = 0, name = "l", visible = true, opacity = 1f, blend = "norm", clipped = false,
            x = 0, y = 0, w = 100, h = 100
        )
        val mesh = Mesh(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))
        val off = FloatArray(8)
        val bone = 200_000
        val moved = mapOf(bone to LayerTransform(dx = 10f))
        val scene = SceneBuilder.buildTree(
            listOf(leaf), 100, 100, moved,
            bones = mapOf(bone to (0f to 0f)), boneTails = mapOf(bone to (100f to 0f)),
            meshes = mapOf(0 to (mesh to off)), skins = mapOf(0 to mapOf(bone to 1f))
        )
        // единственная кость: все вершины сдвинулись на 10 px = 0.1 в единицах слоя
        assertEquals(1, scene.meshIds.size)
        assertEquals(0.1f, scene.meshVerts[2], 1e-3f)
        assertEquals(0f, scene.meshVerts[3], 1e-3f)
        assertEquals(Affine2D.IDENTITY, Affine2D.IDENTITY)
    }
}
