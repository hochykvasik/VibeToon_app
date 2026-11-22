package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.anim.WarpMath
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpTest {
    private val layer = 100001
    private val p5 = WarpMath.pointId(layer, 5)

    @Test fun bindShapeOnly_whenFxOff() {
        val base = mapOf(p5 to LayerTransform(dx = 0.1f, dy = -0.2f))
        val anim = Animation(tracks = mapOf(p5 to AnimationTrack(p5, listOf(Keyframe.of(0, LayerTransform(dx = 0.3f))))))
        val off = WarpMath.offsets(layer, 0, base, anim, fx = false)
        assertEquals(0.1f, off[10], 1e-6f)
        assertEquals(-0.2f, off[11], 1e-6f)
    }

    @Test fun animationAddsOnTopOfBind_whenFxOn() {
        val base = mapOf(p5 to LayerTransform(dx = 0.1f))
        val keys = listOf(Keyframe.of(0, LayerTransform(dx = 0f)), Keyframe.of(10, LayerTransform(dx = 0.4f)))
        val anim = Animation(tracks = mapOf(p5 to AnimationTrack(p5, keys)))
        assertEquals(0.1f, WarpMath.offsets(layer, 0, base, anim, true)[10], 1e-6f)
        assertEquals(0.3f, WarpMath.offsets(layer, 5, base, anim, true)[10], 1e-6f)   // 0.1 + 0.2
        assertEquals(0.5f, WarpMath.offsets(layer, 10, base, anim, true)[10], 1e-6f)
    }

    @Test fun untouchedLayerIsIdentity() {
        assertTrue(WarpMath.isIdentity(WarpMath.offsets(layer, 3, emptyMap(), Animation(), true)))
    }

    @Test fun pointIdsDoNotCollideAcrossLayers() {
        val ids = (0 until 16).map { WarpMath.pointId(1, it) } + (0 until 16).map { WarpMath.pointId(2, it) }
        assertEquals(32, ids.toSet().size)
        assertTrue(ids.all { WarpMath.isPointId(it) })
    }

    @Test fun sceneCarriesWarpData() {
        val data = FloatArray(32) { it * 0.01f }
        val s = SceneBuilder.buildTree(emptyList(), 100, 100, warps = mapOf(layer to data))
        assertEquals(listOf(layer), s.warpIds.toList())
        assertEquals(32, s.warpData.size)
        assertEquals(0.31f, s.warpData[31], 1e-6f)
    }

    @Test fun warpsAndRegroupSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val d = ProjectData(meta, warps = setOf(3, 100001), regroup = listOf(5 to 2, 7 to -1))
        val back = ProjectJson.decode(ProjectJson.encode(d)).getOrThrow()
        assertEquals(setOf(3, 100001), back.warps)
        assertEquals(listOf(5 to 2, 7 to -1), back.regroup)
    }
}
