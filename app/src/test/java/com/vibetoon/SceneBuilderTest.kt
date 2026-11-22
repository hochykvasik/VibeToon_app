package com.vibetoon

import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneBuilderTest {
    // Слой 100x50 в точке (200, 300)
    private val layer = LayerInfo(
        id = 0, name = "l", x = 200, y = 300, width = 100, height = 50, opacity = 1f,
        isVisible = true, kindCode = 0, parentId = -1, blendMode = "norm", isClipped = false
    )

    private fun matrixOf(p: FloatArray, op: Int = 0) =
        Affine2D(p[op * 8], p[op * 8 + 1], p[op * 8 + 2], p[op * 8 + 3], p[op * 8 + 4], p[op * 8 + 5])

    @Test fun identityIsRectMatrix() {
        val s = SceneBuilder.build(listOf(layer), 1000, 1000)
        // единичный квадрат -> прямоугольник слоя: масштаб (100, 50), сдвиг (200, 300)
        assertEquals(listOf(100f, 0f, 0f, 50f, 200f, 300f, 1f, 0f), s.params.toList())
    }

    @Test fun scaleAndRotationAreAroundLayerCenter() {
        val t = LayerTransform(dx = 10f, dy = -20f, scaleX = 2f, scaleY = 2f, rotation = 1.5f)
        val s = SceneBuilder.build(listOf(layer), 1000, 1000, mapOf(0 to t))
        val m = matrixOf(s.params)
        // центр квадрата (0.5, 0.5) оказывается в исходном центре + перенос
        assertEquals(250f + 10f, m.applyX(0.5f, 0.5f), 0.01f)
        assertEquals(325f - 20f, m.applyY(0.5f, 0.5f), 0.01f)
        // длина ребра по X = ширина * масштаб
        val ex = m.applyX(1f, 0f) - m.applyX(0f, 0f)
        val ey = m.applyY(1f, 0f) - m.applyY(0f, 0f)
        assertEquals(200f, Math.hypot(ex.toDouble(), ey.toDouble()).toFloat(), 0.01f)
    }

    @Test fun nonUniformScaleChangesWidthAndHeightIndependently() {
        val t = LayerTransform(scaleX = 3f, scaleY = 0.5f)
        val m = matrixOf(SceneBuilder.build(listOf(layer), 1000, 1000, mapOf(0 to t)).params)
        assertEquals(300f, m.a, 0.001f)                  // 100 * 3
        assertEquals(25f, m.d, 0.001f)                   // 50 * 0.5
        assertEquals(250f, m.applyX(0.5f, 0.5f), 0.001f) // центр не смещается
        assertEquals(325f, m.applyY(0.5f, 0.5f), 0.001f)
    }

    @Test fun resetTransformEqualsIdentity() {
        val a = SceneBuilder.build(listOf(layer), 1000, 1000, mapOf(0 to LayerTransform()))
        val b = SceneBuilder.build(listOf(layer), 1000, 1000)
        assertEquals(b.params.toList(), a.params.toList())
    }

    @Test fun selectionQuadOfLeafIsItsRectCorners() {
        val q = SceneBuilder.build(listOf(layer), 1000, 1000).selectionQuads[0]!!
        assertEquals(listOf(200f, 300f, 300f, 300f, 300f, 350f, 200f, 350f), q.toList())
    }
}
