package com.vibetoon

import com.vibetoon.domain.anim.Effect
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTest {
    private val img = LeafNode(id = 100_001, name = "i", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 0, y = 0, w = 100, h = 100)
    private val cam = LeafNode(id = 100_002, name = "cam", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 0, y = 0, w = 200, h = 200)

    @Test fun cameraIsNotDrawnAndZoomScalesContent() {
        val t = mapOf(100_002 to LayerTransform(scaleX = 2f, scaleY = 2f))
        val sc = SceneBuilder.buildTree(listOf(img, cam), 200, 200, t, cameraId = 100_002)
        assertEquals(listOf(100_001), sc.textureIds.toList())
        // слой 100x100 в левом верхнем углу холста 200x200 при зуме 2 вокруг центра: размер 200, начало (-100,-100)
        val q = sc.selectionQuads.getValue(100_001)
        assertEquals(-100f, q[0], 1e-3f); assertEquals(-100f, q[1], 1e-3f)
        assertEquals(100f, q[2], 1e-3f)
    }

    @Test fun cameraPanMovesContentOpposite() {
        val t = mapOf(100_002 to LayerTransform(dx = 30f))
        val sc = SceneBuilder.buildTree(listOf(img, cam), 200, 200, t, cameraId = 100_002)
        assertEquals(-30f, sc.selectionQuads.getValue(100_001)[0], 1e-3f)
    }

    @Test fun effectRecordsSkipSpinAndSpinAngle() {
        val list = listOf(EffectMath.create(EffectMath.BLUR)!!, EffectMath.create(EffectMath.SPIN)!!)
        assertEquals(1, EffectMath.records(list, 0f).size)
        // 90 °/с, кадр 24 при 24 fps = 90°
        assertEquals(Math.PI.toFloat() / 2f, EffectMath.spinAngle(list, 24, 24), 1e-3f)
        assertTrue(EffectMath.clamp(EffectMath.BLUR, 0, 9999f) <= 60f)
        assertEquals(Effect(EffectMath.BLUR, List(8) { if (it == 0) 12f else 0f }), EffectMath.create(EffectMath.BLUR))
    }
}
