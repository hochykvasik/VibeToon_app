package com.vibetoon

import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.project.BONE_ID_BASE
import com.vibetoon.domain.project.Bone
import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class BoneTest {
    private val upper = BONE_ID_BASE + 1
    private val lower = BONE_ID_BASE + 2
    private val arm = MEDIA_ID_BASE + 1
    // плечо в (0,0), локоть в (100,0), кисть в (200,0); слой руки привязан к нижней кости
    private val heads = mapOf(upper to (0f to 0f), lower to (100f to 0f))
    private val links = mapOf(lower to upper, arm to lower)
    private val roots = listOf(LeafNode(arm, "arm", true, 1f, "norm", false, 100, -10, 100, 20))

    private fun build(t: Map<Int, LayerTransform>) = SceneBuilder.buildTree(roots, 1000, 1000, t, links = links, bones = heads)

    @Test fun restPoseChangesNothing() {
        val s = build(emptyMap())
        assertEquals(0f, s.boneDeltas.getValue(lower).tx, 0.001f)
        assertEquals(0f, s.parentWorlds.getValue(arm).tx, 0.001f)
    }

    @Test fun elbowRotationMovesOnlyForearmPoints() {
        val s = build(mapOf(lower to LayerTransform(rotation = (Math.PI / 2).toFloat())))
        val d = s.boneDeltas.getValue(lower)
        assertEquals(100f, d.applyX(100f, 0f), 0.01f)   // локоть на месте
        assertEquals(0f, d.applyY(100f, 0f), 0.01f)
        assertEquals(100f, d.applyX(200f, 0f), 0.01f)   // кисть повернулась вверх на 90 градусов
        assertEquals(100f, d.applyY(200f, 0f), 0.01f)
    }

    @Test fun shoulderRotationCarriesWholeArm() {
        val s = build(mapOf(upper to LayerTransform(rotation = (Math.PI / 2).toFloat())))
        val d = s.boneDeltas.getValue(lower)
        assertEquals(0f, d.applyX(100f, 0f), 0.01f)     // локоть уехал под плечо
        assertEquals(100f, d.applyY(100f, 0f), 0.01f)
        assertEquals(200f, s.parentWorlds.getValue(arm).applyY(200f, 0f), 0.01f) // привязанный слой повёрнут вместе с рукой
    }

    @Test fun bonesSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val bones = listOf(Bone(upper, "Плечо", 0f, 0f, 100f, 0f), Bone(lower, "Локоть", 100f, 0f, 200f, 5f))
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, bones = bones, links = links))).getOrThrow()
        assertEquals(bones, back.bones)
    }
}

class IndependentRotationTest {
    private val upper = BONE_ID_BASE + 1
    private val lower = BONE_ID_BASE + 2
    private val heads = mapOf(upper to (0f to 0f), lower to (100f to 0f))
    private val links = mapOf(lower to upper)
    private val t = mapOf(upper to LayerTransform(rotation = (Math.PI / 2).toFloat()))

    private fun tail(independent: Set<Int>): Pair<Float, Float> {
        val d = SceneBuilder.buildTree(emptyList(), 1000, 1000, t, links = links, bones = heads, independentBones = independent)
            .boneDeltas.getValue(lower)
        return d.applyX(200f, 0f) to d.applyY(200f, 0f)
    }

    @Test fun inheritsRotationByDefault() {
        val (x, y) = tail(emptySet())
        assertEquals(0f, x, 0.01f); assertEquals(200f, y, 0.01f)
    }

    @Test fun independentKeepsAngleButFollowsPosition() {
        val (x, y) = tail(setOf(lower))
        assertEquals(100f, x, 0.01f); assertEquals(100f, y, 0.01f)   // сустав уехал в (0,100), направление осталось по X
    }
}
