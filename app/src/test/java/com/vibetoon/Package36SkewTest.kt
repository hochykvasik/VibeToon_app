package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.LayerSkew
import com.vibetoon.domain.anim.PerspectiveMath
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.raster.IRect
import com.vibetoon.domain.raster.TransformBox
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorBezier
import com.vibetoon.domain.vector.VectorBool
import com.vibetoon.domain.vector.VectorShape
import com.vibetoon.domain.vector.VectorWarp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/** Спринт 36: наклон и искажение векторных фигур (VectorWarp) и целых слоёв (LayerSkew через углы перспективы). */
class Package36SkewTest {
    private fun near(e: Double, a: Double, d: Double = 1e-3, m: String = "") = assertTrue("$m: expected $e got $a", abs(e - a) <= d)

    private val rect = VectorShape(VecKind.RECT, listOf(10f, 10f, 110f, 60f), 0xFF000000L, 0xFFFF0000L, 4f)
    private val ellipse = VectorShape(VecKind.ELLIPSE, listOf(0f, 0f, 100f, 50f), 0xFF000000L, 0L, 2f)
    private val preset = VectorShape(VecKind.PRESET, listOf(0f, 0f, 100f, 100f), 0xFF000000L, 0L, 3f, preset = "STAR")

    @Test fun editableTurnsPrimitivesIntoPenContours() {
        val r = VectorWarp.editable(rect)
        assertEquals(VecKind.POLYGON, r.kind)
        assertEquals(listOf(10f, 10f, 110f, 10f, 110f, 60f, 10f, 60f), r.pts)
        assertEquals(rect.fill, r.fill); assertEquals(rect.stroke, r.stroke); assertEquals(rect.width, r.width)
        val e = VectorWarp.editable(ellipse)
        assertEquals(VecKind.POLYGON, e.kind)
        assertTrue(VectorBezier.hasHandles(e))
        assertEquals(listOf(50f, 0f, 100f, 25f, 50f, 50f, 0f, 25f), e.pts)
        // середина первого сегмента лежит на эллипсе (каппа)
        val c = VectorBezier.cubics(e)[0]
        val mx = (c[0] + 3 * c[2] + 3 * c[4] + c[6]) / 8.0; val my = (c[1] + 3 * c[3] + 3 * c[5] + c[7]) / 8.0
        near(1.0, ((mx - 50) / 50).let { it * it } + ((my - 25) / 25).let { it * it }, 1e-3, "на эллипсе")
        val p = VectorWarp.editable(preset)
        assertTrue(p.kind == VecKind.POLYGON || p.kind == VecKind.BOOL)
        val line = VectorWarp.editable(VectorShape(VecKind.LINE, listOf(0f, 0f, 5f, 5f)))
        assertEquals(VecKind.POLY, line.kind)
        val path = VectorShape(VecKind.PATH, listOf(0f, 0f, 1f, 1f, 2f, 0f))
        assertEquals(path, VectorWarp.editable(path))
    }

    @Test fun skewMapsShearAroundCenter() {
        val f = VectorWarp.skewMap(45.0, 0.0, 0.0, 50.0)!!
        val out = VectorWarp.map(rect, f)!!
        // верх (y=10) сдвинут на -40, низ (y=60) на +10
        assertEquals(listOf(-30f, 10f, 70f, 10f, 120f, 60f, 20f, 60f), out.pts)
        assertEquals(4f, out.width)
        assertNull(VectorWarp.skewMap(85.0, 0.0, 0.0, 0.0))
        assertNull(VectorWarp.skewMap(Double.NaN, 0.0, 0.0, 0.0))
        val y = VectorWarp.skewMap(0.0, 30.0, 0.0, 0.0)!!.map(10.0, 0.0)
        near(10.0, y[0]); near(10 * tan(Math.toRadians(30.0)), y[1])
    }

    @Test fun handlesFollowAffineMapsExactly() {
        val e = VectorWarp.editable(ellipse)
        val f = VectorWarp.affine(1.0, 0.0, 0.5, 1.0, 0.0, 0.0)   // наклон X
        val out = VectorWarp.map(e, f)!!
        for (k in 0 until e.pts.size / 2) for (w in 0..1) {
            val hx = e.handles[k * 4 + w * 2]; val hy = e.handles[k * 4 + w * 2 + 1]
            near(hx + 0.5 * hy, out.handles[k * 4 + w * 2].toDouble(), 1e-4)
            near(hy.toDouble(), out.handles[k * 4 + w * 2 + 1].toDouble(), 1e-4)
        }
    }

    @Test fun booleanPartsAreWarpedTogether() {
        val outer = VectorShape(VecKind.RECT, listOf(0f, 0f, 100f, 100f), 0L, 0xFF000000L, 1f)
        val hole = VectorShape(VecKind.ELLIPSE, listOf(25f, 25f, 75f, 75f), 0L, 0xFF000000L, 1f)
        val b = VectorBool.combine(listOf(outer, hole), 1, VectorBool.SUBTRACT).shapes[0]
        val out = VectorWarp.map(b, VectorWarp.affine(1.0, 0.0, 0.0, 1.0, 10.0, 0.0))!!
        assertEquals(VecKind.BOOL, out.kind)
        val parts = VectorBool.parts(out)
        assertEquals(2, parts.size)
        assertEquals(VecKind.POLYGON, parts[0].kind); assertEquals(VecKind.POLYGON, parts[1].kind)
        assertEquals(10f, parts[0].pts[0])
        assertEquals(out.pts, parts.flatMap { it.pts })
        assertTrue(VectorBezier.hasHandles(parts[1]))
    }

    @Test fun transformBoxDistortAndNumericSkewMapShapes() {
        val shapes = listOf(rect, ellipse)
        val b = VectorWarp.bounds(shapes, setOf(0))!!
        assertEquals(10.0, b[0]); assertEquals(10.0, b[1]); assertEquals(110.0, b[2]); assertEquals(60.0, b[3])
        val box0 = TransformBox.initial(IRect(10, 10, 100, 50))
        // искажение: правый нижний угол уехал на (+20, +10)
        val dist = TransformBox.drag(box0, TransformBox.Handle.C2, TransformBox.Mode.DISTORT, TransformBox.P(110.0, 60.0), TransformBox.P(130.0, 70.0), false)
        val f = VectorWarp.boxMap(dist)!!
        val out = VectorWarp.apply(shapes, setOf(0), f)!!
        assertEquals(ellipse, out[1])   // другая фигура не тронута
        val p = out[0].pts
        near(10.0, p[0].toDouble()); near(10.0, p[1].toDouble())
        near(130.0, p[4].toDouble()); near(70.0, p[5].toDouble())
        near(1.0, VectorWarp.widthScaleFor(box0))
        // числовой наклон X 30° вокруг центра рамки: верх влево, низ вправо, центр на месте
        val sk = TransformBox.numeric(box0, TransformBox.Numeric(1.0, 1.0, 0.0, 30.0, 0.0, 0.0, 0.0, false, false))!!
        val g = VectorWarp.boxMap(sk)!!
        val c = g.map(60.0, 35.0); near(60.0, c[0]); near(35.0, c[1])
        val top = g.map(10.0, 10.0); near(10.0 - 25 * tan(Math.toRadians(30.0)), top[0], 1e-3)
        near(1.0, VectorWarp.widthScaleFor(sk), 1e-6, "наклон не меняет толщину")
        val big = TransformBox.numeric(box0, TransformBox.Numeric(2.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, false))!!
        near(2.0, VectorWarp.widthScaleFor(big), 1e-6)
        assertEquals(8f, VectorWarp.apply(listOf(rect), emptySet(), VectorWarp.boxMap(big)!!, VectorWarp.widthScaleFor(big))!![0].width)
    }

    @Test fun badMapsAreRejectedWholesale() {
        val bad = VectorWarp.PointMap { x, _ -> if (x > 50) doubleArrayOf(Double.NaN, 0.0) else doubleArrayOf(x, 0.0) }
        assertNull(VectorWarp.apply(listOf(rect), emptySet(), bad))
        val ok = VectorWarp.apply(listOf(VectorShape(VecKind.POLY, listOf(0f, 0f, 10f, 0f))), emptySet(), bad)
        assertNotNull(ok)
    }

    @Test fun warpedShapesSurviveProjectJson() {
        val warped = VectorWarp.apply(listOf(rect, ellipse, preset), emptySet(), VectorWarp.skewMap(20.0, 10.0, 50.0, 50.0)!!)!!
        val m = MediaItem(100_001, MediaKind.IMAGE, "Вектор 1", "assets/v.png", 100, 100, 0f, 0f, 100f, 100f, vector = true, vectors = warped)
        val d = ProjectData(ProjectMeta("p", "P", 100, 100), media = listOf(m))
        val back = ProjectJson.decode(ProjectJson.encode(d)).getOrThrow()
        assertEquals(warped, back.media[0].vectors)
    }

    // ---------------------------------------------------------------- наклон целого слоя

    @Test fun layerSkewMeasuresAndSetsParallelogram() {
        val zero = FloatArray(8)
        assertEquals(0f to 0f, LayerSkew.measure(zero, 200f, 100f))
        val off = LayerSkew.withSkew(zero, 200f, 100f, 30f, null)!!
        val (kx, ky) = LayerSkew.measure(off, 200f, 100f)!!
        near(30.0, kx.toDouble(), 1e-3); near(0.0, ky.toDouble(), 1e-3)
        // параллелограмм: верх влево, низ вправо на одинаковую величину, по вертикали без изменений
        near(-off[4].toDouble(), off[0].toDouble(), 1e-6)
        assertEquals(off[0], off[2]); assertEquals(off[4], off[6])
        assertTrue(off.filterIndexed { i, _ -> i % 2 == 1 }.all { it == 0f })
        near(-0.5 * tan(Math.toRadians(30.0)) * 100 / 200, off[0].toDouble(), 1e-5)
        val both = LayerSkew.withSkew(off, 200f, 100f, null, -15f)!!
        val m2 = LayerSkew.measure(both, 200f, 100f)!!
        near(-15.0, m2.second.toDouble(), 1e-3)
        val back = LayerSkew.straighten(both, 200f, 100f)!!
        val m3 = LayerSkew.measure(back, 200f, 100f)!!
        near(0.0, m3.first.toDouble(), 1e-3); near(0.0, m3.second.toDouble(), 1e-3)
    }

    @Test fun layerSkewKeepsOtherCornerDistortion() {
        // трапеция (перспектива): верх сужен
        val trap = floatArrayOf(0.1f, 0f, -0.1f, 0f, 0f, 0f, 0f, 0f)
        val sk = LayerSkew.withSkew(trap, 100f, 100f, 20f, null)!!
        // ширина верха и низа не изменилась
        near(((1 - 0.1) - 0.1).toDouble(), ((1f + sk[2]) - sk[0]).toDouble(), 1e-5)
        near(1.0, ((1f + sk[4]) - sk[6]).toDouble(), 1e-5)
        near(20.0, LayerSkew.measure(sk, 100f, 100f)!!.first.toDouble(), 1e-3)
    }

    @Test fun layerSkewRejectsInvalidInput() {
        val zero = FloatArray(8)
        assertNull(LayerSkew.withSkew(zero, 100f, 100f, 80f, null))
        assertNull(LayerSkew.withSkew(zero, 100f, 100f, Float.NaN, null))
        assertNull(LayerSkew.withSkew(zero, 0f, 100f, 10f, null))
        assertNull(LayerSkew.withSkew(FloatArray(3), 100f, 100f, 10f, null))
        assertNull(LayerSkew.measure(FloatArray(8) { Float.NaN }, 100f, 100f))
        // очень вытянутый слой: 75° по X у узкого высокого слоя выходит за предел смещения угла
        assertNull(LayerSkew.withSkew(zero, 10f, 1000f, 75f, null))
        assertEquals(zero.toList(), LayerSkew.withSkew(zero, 100f, 100f, null, null)!!.toList())
    }

    @Test fun layerSkewOffsetsDriveTheSamePerspectiveMatrixAndKeys() {
        val off = LayerSkew.withSkew(FloatArray(8), 100f, 100f, 45f, null)!!
        val m = PerspectiveMath.matrix(off)
        // гомография параллелограмма - аффинная: (0, 1) -> (0.5 + -0.5..., 1)
        val (x, y) = PerspectiveMath.apply(m, 0f, 1f)
        near((0f + off[6]).toDouble(), x.toDouble(), 1e-5); near(1.0, y.toDouble(), 1e-5)
        // в форму слоя: base[pointId] = dx, dy - так же, как пишет перетаскивание углов
        val layer = 100_002
        val base = HashMap<Int, LayerTransform>()
        for (k in 0 until 4) base[PerspectiveMath.pointId(layer, k)] = LayerTransform(dx = off[k * 2], dy = off[k * 2 + 1])
        assertEquals(off.toList(), PerspectiveMath.offsets(layer, 0, base, Animation(), false).toList())
        // ключ на кадре 10 поверх формы: смещение относительно формы
        var anim = Animation()
        val more = LayerSkew.withSkew(off, 100f, 100f, 10f, null)!!
        for (k in 0 until 4) {
            val id = PerspectiveMath.pointId(layer, k)
            anim = anim.withKey(id, 0, LayerTransform())
            anim = anim.withKey(id, 10, LayerTransform(dx = more[k * 2] - off[k * 2], dy = more[k * 2 + 1] - off[k * 2 + 1]))
        }
        val at10 = PerspectiveMath.offsets(layer, 10, base, anim, true)
        near(10.0, LayerSkew.measure(at10, 100f, 100f)!!.first.toDouble(), 1e-3)
        val at0 = PerspectiveMath.offsets(layer, 0, base, anim, true)
        near(45.0, LayerSkew.measure(at0, 100f, 100f)!!.first.toDouble(), 1e-3)
    }
}
