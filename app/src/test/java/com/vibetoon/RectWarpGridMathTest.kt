package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.*
import org.junit.Test

class RectWarpGridMathTest {
    @Test fun independentAxesPreserveShapeAndRoundTrip() {
        val grid = FloatArray(32) { kotlin.math.sin(it * .43).toFloat() }
        for ((w, h) in listOf(4 to 4, 6 to 4, 4 to 7, 6 to 7)) {
            val raised = RectWarpGridMath.convert(grid, 4, 4, w, h)!!
            for (u in listOf(0f, .17f, .51f, 1f)) for (v in listOf(0f, .23f, .89f, 1f)) {
                val a = RectWarpGridMath.evaluate(grid, 4, 4, u, v)!!
                val b = RectWarpGridMath.evaluate(raised, w, h, u, v)!!
                assertEquals(a.first, b.first, .00001f); assertEquals(a.second, b.second, .00001f)
            }
            assertArrayEquals(grid, RectWarpGridMath.convert(raised, w, h, 4, 4)!!, .00001f)
        }
    }
    @Test fun refusesLossOrInvalidDimensionsWithoutChangingInput() {
        val shape = FloatArray(5 * 4 * 2).also { it[4] = .8f }
        val copy = shape.copyOf()
        assertNull(RectWarpGridMath.convert(shape, 5, 4, 4, 4))
        assertArrayEquals(copy, shape, 0f)
        assertNull(RectWarpGridMath.convert(shape, 5, 4, 10, 4))
        assertNull(RectWarpGridMath.convert(shape, 5, 4, 5, 4, Float.NaN))
        assertNull(RectWarpGridMath.evaluate(shape, 5, 4, Float.NaN, 0f))
    }
    @Test fun rectangularKeysPreserveBetweenFramesAndRejectDesynchronization() {
        val points = List(16) { i -> WarpGridMigration.PointKeys(if (i % 3 == 0) emptyList() else listOf(
            Keyframe.of(0, LayerTransform(dx = i * .01f, dy = i * -.02f), EasingType.LINEAR),
            Keyframe.of(10, LayerTransform(dx = i * .03f, dy = i * .01f), EasingType.LINEAR))) }
        val base = FloatArray(32) { kotlin.math.cos(it.toDouble()).toFloat() * .1f }
        val result = WarpGridMigration.convertRect(base, points, 4, 4, 6, 5)!!
        fun sample(p: List<WarpGridMigration.PointKeys>, f: Float) = FloatArray(p.size * 2).also { out ->
            p.forEachIndexed { i, t -> AnimationTrack(i, t.keys).evaluate(f)?.let { out[i * 2] = it.dx; out[i * 2 + 1] = it.dy } }
        }
        for (f in listOf(-1f, 0f, 3.5f, 10f, 12f)) {
            val old = sample(points, f); val now = sample(result.points, f)
            for (u in listOf(0f, .37f, 1f)) for (v in listOf(0f, .64f, 1f)) {
                val a = RectWarpGridMath.evaluate(old, 4, 4, u, v)!!
                val b = RectWarpGridMath.evaluate(now, 6, 5, u, v)!!
                assertEquals(a.first, b.first, .00001f); assertEquals(a.second, b.second, .00001f)
            }
        }
        val back = WarpGridMigration.convertRect(result.base, result.points, 6, 5, 4, 4)!!
        assertArrayEquals(base, back.base, .00001f)
        val bad = points.toMutableList()
        bad[1] = WarpGridMigration.PointKeys(bad[1].keys.map { it.copy(easing = EasingType.EASE_IN) })
        assertNull(WarpGridMigration.convertRect(base, bad, 4, 4, 6, 5))
    }
}
