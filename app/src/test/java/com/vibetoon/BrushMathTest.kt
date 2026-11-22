package com.vibetoon

import com.vibetoon.domain.paint.BrushMath
import com.vibetoon.domain.paint.BrushParams
import com.vibetoon.domain.paint.BrushShape
import com.vibetoon.domain.paint.DabRotation
import com.vibetoon.domain.paint.DabStroker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class BrushMathTest {
    private val p = BrushParams(size = 20f, spacing = 0.25f, minSizePercent = 0.2f)

    private fun run(points: List<Triple<Float, Float, Float>>, params: BrushParams = p): List<FloatArray> {
        val s = DabStroker(params)
        val all = ArrayList<FloatArray>()
        fun addAll(a: FloatArray) { for (i in 0 until a.size / DabStroker.STRIDE) all += a.copyOfRange(i * DabStroker.STRIDE, (i + 1) * DabStroker.STRIDE) }
        addAll(s.begin(points[0].first, points[0].second, points[0].third))
        for (i in 1 until points.size) addAll(s.add(points[i].first, points[i].second, points[i].third))
        addAll(s.end())
        return all
    }

    private fun line(step: Float, len: Float = 1000f) = (0..(len / step).toInt()).map { Triple(it * step, 0f, 1f) } + Triple(len, 0f, 1f)

    @Test fun smartSpacingGrowsWithRadiusAndIsCapped() {
        assertEquals(0.25f * (1f + 10f / 300f), BrushMath.effectiveSpacing(0.25f, 10f), 1e-5f)
        assertEquals(0.4f, BrushMath.effectiveSpacing(0.25f, 1500f), 1e-6f)          // 0.25 * 6 = 1.5 -> предел 0.4
        assertEquals(0.5f, BrushMath.effectiveSpacing(0.5f, 100f), 1e-6f)            // базовый шаг больше предела остаётся как есть
        assertTrue(BrushMath.effectiveSpacing(0.1f, 600f) in 0.1f..0.4f)
    }

    @Test fun logSliderCoversWholeRangeAndRoundTrips() {
        assertEquals(1f, BrushMath.sizeFromSlider(0f), 1e-4f)
        assertEquals(1500f, BrushMath.sizeFromSlider(1f), 0.5f)
        assertEquals(Math.sqrt(1500.0).toFloat(), BrushMath.sizeFromSlider(0.5f), 0.1f)
        var prev = 0f
        for (i in 0..20) { val v = BrushMath.sizeFromSlider(i / 20f); assertTrue(v > prev); prev = v }
        for (s in listOf(1f, 3f, 24f, 300f, 1500f)) assertEquals(s, BrushMath.sizeFromSlider(BrushMath.sliderFromSize(s)), s * 1e-3f)
        assertTrue("3 px ловится в первой четверти слайдера", BrushMath.sliderFromSize(3f) < 0.25f)
    }

    @Test fun dabsAreEvenlySpacedAcrossEventBoundaries() {
        val r = BrushMath.radiusAt(p, 1f)                                 // 10 px
        val step = 2f * r * BrushMath.effectiveSpacing(p.spacing, r)     // около 5.17
        val d = run(line(7f))
        for (i in 1 until d.size) {
            val gap = hypot(d[i][0] - d[i - 1][0], d[i][1] - d[i - 1][1])
            assertEquals("зазор $i", step, gap, 0.25f)
        }
        assertEquals(1000f / step, d.size.toFloat(), 4f)
    }

    @Test fun spacingDoesNotDependOnHowEventsAreChunked() {
        val a = run(line(3f)); val b = run(line(11f)); val c = run(line(40f))
        assertTrue("${a.size} vs ${b.size} vs ${c.size}", abs(a.size - b.size) <= 2 && abs(a.size - c.size) <= 2)
        for (d in a + b + c) assertEquals(0f, d[1], 1e-3f)
    }

    @Test fun splinePassesThroughControlPointsOnACurve() {
        // дуга окружности радиуса 200, контрольные точки через 15 градусов
        val pts = (0..6).map { val t = Math.toRadians(it * 15.0); Triple((200 * cos(t)).toFloat(), (200 * sin(t)).toFloat(), 1f) }
        val d = run(pts)
        for (a in d) assertEquals("штамп на дуге", 200f, hypot(a[0], a[1]), 1.5f)
        for (c in pts) assertTrue("рядом с контрольной точкой есть штамп", d.any { hypot(it[0] - c.first, it[1] - c.second) < 5.5f })
    }

    @Test fun pressureScalesRadius() {
        val lo = BrushMath.radiusAt(p, 0f); val hi = BrushMath.radiusAt(p, 1f)
        assertEquals(10f * 0.2f, lo, 1e-4f)
        assertEquals(10f, hi, 1e-4f)
        val d = run(listOf(Triple(0f, 0f, 0.1f), Triple(100f, 0f, 0.1f), Triple(200f, 0f, 1f), Triple(300f, 0f, 1f)))
        assertTrue(d.first()[2] < d.last()[2])
    }

    @Test fun tapGivesOneDabAndFlowIsKept() {
        val d = run(listOf(Triple(50f, 60f, 1f)), p.copy(flow = 0.4f))
        assertEquals(1, d.size)
        assertEquals(0.4f, d[0][3], 1e-6f)
        assertEquals(50f, d[0][0], 1e-6f)
    }

    @Test fun hugeBrushUsesFewDabs() {
        val big = p.copy(size = 1500f)
        val d = run(line(20f, 3000f), big)
        // шаг <= 0.4 диаметра = 600 px: на 3000 px не больше ~8 штампов, а не сотни
        assertTrue("штампов ${d.size}", d.size <= 10)
    }

    @Test fun stampHasRotationAttribute() {
        // квадратный маркер идёт по направлению движения: вдоль +Y угол = 90 градусов
        val sq = BrushParams(size = 20f, spacing = 0.25f, rotation = DabRotation.TRAJECTORY)
        val d = run((0..40).map { Triple(0f, it * 5f, 1f) }, sq)
        assertTrue(d.size > 5)
        for (k in 3 until d.size - 2) assertEquals(Math.PI.toFloat() / 2f, d[k][4], 1e-3f)
        // обычная кисть не вращается
        assertTrue(run(line(5f)).all { it[4] == 0f })
        // случайный поворот: углы разные и в пределах 0..2pi
        val rnd = run(line(5f), BrushParams(size = 20f, rotation = DabRotation.RANDOM))
        assertTrue(rnd.map { it[4] }.toSet().size > 5)
        assertTrue(rnd.all { it[4] in 0f..6.2832f })
    }

    @Test fun inkPenTapersInAndPixelBrushSnapsToGrid() {
        val ink = run(line(4f), BrushParams(size = 20f, spacing = 0.1f, minSizePercent = 1f, taperPx = 40f))
        assertTrue("начало тоньше конца: ${ink.first()[2]} < ${ink[ink.size / 2][2]}", ink.first()[2] < ink[ink.size / 2][2] * 0.5f)
        val px = run(line(3.3f, 60f), BrushParams(size = 3f, spacing = 0.5f, minSizePercent = 1f, shape = BrushShape.SQUARE, pixelSnap = true))
        for (d in px) { assertEquals(1.5f, d[2], 1e-6f); assertEquals(0.5f, d[0] - kotlin.math.floor(d[0]), 1e-6f) }
    }
}
