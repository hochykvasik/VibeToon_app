package com.vibetoon

import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.PivotMath
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Опорная точка слоя (Layer Pivot Editing): перенос без скачка картинки, поворот вокруг новой точки, сохранение. */
class PivotTest {
    private val leaf = LeafNode(id = 100_001, name = "a", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 0, y = 0, w = 100, h = 50)

    private fun quad(t: LayerTransform, off: Pair<Float, Float>? = null): FloatArray =
        SceneBuilder.buildTree(listOf(leaf), 400, 400, mapOf(leaf.id to t), pivotOffsets = off?.let { mapOf(leaf.id to it) } ?: emptyMap())
            .selectionQuads.getValue(leaf.id)

    private fun assertQuad(a: FloatArray, b: FloatArray) { for (i in a.indices) assertEquals("i=$i", a[i], b[i], 1e-2f) }

    @Test fun compensationKeepsLayerInPlace() {
        val t = LayerTransform(dx = 20f, dy = -5f, scaleX = 1.5f, scaleY = 0.8f, rotation = 0.6f)
        val center = 50f to 25f
        val np = 0f to 0f   // в левый верхний угол
        val before = quad(t)
        val t2 = PivotMath.compensate(t, center, np)
        val after = quad(t2, np.first - center.first to np.second - center.second)
        assertQuad(before, after)
    }

    @Test fun rotationGoesAroundNewPivot() {
        // опорная точка в левом верхнем углу: при повороте угол (0, 0) остаётся на месте
        val q = quad(LayerTransform(rotation = 1.2f), -50f to -25f)
        assertEquals(0f, q[0], 1e-3f); assertEquals(0f, q[1], 1e-3f)
        // по умолчанию (центр) угол уезжает
        val q0 = quad(LayerTransform(rotation = 1.2f))
        assertTrue(kotlin.math.abs(q0[0]) > 1f)
    }

    @Test fun pivotForPointLandsUnderFinger() {
        val t = LayerTransform(dx = 10f, dy = 7f, scaleX = 2f, scaleY = 1.5f, rotation = -0.4f)
        val p = 50f to 25f
        val np = PivotMath.pivotForPoint(t, p, 130f, 60f)!!
        // после компенсации отображаемая точка опоры стоит там, куда показал палец
        val t2 = PivotMath.compensate(t, p, np)
        val (x, y) = PivotMath.displayed(t2, np)
        assertEquals(130f, x, 1e-3f); assertEquals(60f, y, 1e-3f)
        assertEquals(null, PivotMath.pivotForPoint(t.copy(scaleX = 0f), p, 0f, 0f))
    }

    @Test fun trackKeysAreCompensated() {
        val tr = AnimationTrack(1, listOf(Keyframe(0, 0f, 0f, 1f, 1f, 0f), Keyframe(10, 30f, 0f, 2f, 2f, 1f)))
        val out = PivotMath.compensateTrack(tr, 50f to 25f, 0f to 0f)
        // без поворота и масштаба сдвиг не меняется
        assertEquals(0f, out.keys[0].dx, 1e-4f)
        for (k in tr.keys.indices) {
            val a = quad(tr.keys[k].toTransform())
            val b = quad(out.keys[k].toTransform(), -50f to -25f)
            assertQuad(a, b)
        }
        assertEquals(10, out.keys[1].frame)
    }

    @Test fun temporaryPivotKeepsGesturePoint() {
        val old = LayerTransform(dx = 5f, rotation = 0.2f)
        val new = old.copy(rotation = 0.9f, scaleX = 1.4f, scaleY = 1.4f)
        val p = 50f to 25f
        val g = 90f to 40f
        val t = PivotMath.aroundPoint(old, new, p, g.first, g.second)
        // точка слоя, бывшая под пальцами, остаётся под пальцами
        fun local(tt: LayerTransform, x: Float, y: Float): Pair<Float, Float> {
            val m = PivotMath.linear(tt)
            val rx = x - p.first; val ry = y - p.second
            return (p.first + tt.dx + m[0] * rx + m[2] * ry) to (p.second + tt.dy + m[1] * rx + m[3] * ry)
        }
        // какая точка слоя была в g до жеста
        val mo = PivotMath.linear(old); val det = mo[0] * mo[3] - mo[1] * mo[2]
        val ux = g.first - p.first - old.dx; val uy = g.second - p.second - old.dy
        val lx = p.first + (mo[3] * ux - mo[2] * uy) / det; val ly = p.second + (-mo[1] * ux + mo[0] * uy) / det
        val (sx, sy) = local(t, lx, ly)
        assertEquals(g.first, sx, 1e-3f); assertEquals(g.second, sy, 1e-3f)
        assertEquals(new.rotation, t.rotation, 1e-6f)
    }

    @Test fun snapToBoxPicksNearestAnchor() {
        val box = floatArrayOf(0f, 0f, 100f, 50f)
        assertEquals(100f to 0f, PivotMath.snapToBox(97f, 3f, box, 10f))
        assertEquals(50f to 25f, PivotMath.snapToBox(52f, 24f, box, 10f))
        assertEquals(30f to 30f, PivotMath.snapToBox(30f, 30f, box, 5f))
        assertEquals(1, PivotMath.nearest(floatArrayOf(0f, 0f, 10f, 10f), 9f, 9f, 5f))
    }

    @Test fun propsNormalizeAndFlag() {
        assertFalse(LayerProps().hasPivot)
        assertTrue(LayerProps(pivotX = 3f).hasPivot)
        assertEquals(0f, LayerProps(pivotX = Float.NaN).normalized().pivotX, 0f)
    }
}
