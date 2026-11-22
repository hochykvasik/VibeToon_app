package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.*
import org.junit.Test

class WarpGridMigrationTest {
    private fun point(i: Int): WarpGridMigration.PointKeys {
        if (i % 3 == 0) return WarpGridMigration.PointKeys(emptyList())
        val a = Keyframe.of(0, LayerTransform(dx = i * .01f, dy = -i * .02f), EasingType.LINEAR)
        val b = Keyframe.of(10, LayerTransform(dx = i * .03f, dy = i * .015f), EasingType.LINEAR)
        return WarpGridMigration.PointKeys(listOf(a, b))
    }
    private fun sample(points: List<WarpGridMigration.PointKeys>, frame: Float): FloatArray {
        val out = FloatArray(points.size * 2)
        points.forEachIndexed { i, p -> AnimationTrack(i, p.keys).evaluate(frame)?.let { out[i * 2] = it.dx; out[i * 2 + 1] = it.dy } }
        return out
    }
    @Test fun animatedElevationPreservesBetweenFramesAndCanReturnToCubic() {
        val base = FloatArray(32) { kotlin.math.sin(it * .7).toFloat() * .3f }
        val points = (0 until 16).map(::point)
        val up = WarpGridMigration.convert(base, points, 4, 5)!!
        val down = WarpGridMigration.convert(up.base, up.points, 5, 4)!!
        assertArrayEquals(base, down.base, .00001f)
        for (frame in listOf(-1f, 0f, 2.5f, 7.5f, 10f, 11f)) {
            val old = sample(points, frame); val high = sample(up.points, frame); val restored = sample(down.points, frame)
            for (u in listOf(0f, .21f, .7f, 1f)) for (v in listOf(0f, .45f, 1f)) {
                val a = WarpGridMath.evaluate(old, 4, u, v)!!
                val b = WarpGridMath.evaluate(high, 5, u, v)!!
                val c = WarpGridMath.evaluate(restored, 4, u, v)!!
                assertEquals(a.first, b.first, .00001f); assertEquals(a.second, b.second, .00001f)
                assertEquals(a.first, c.first, .00001f); assertEquals(a.second, c.second, .00001f)
            }
        }
    }
    @Test fun refusesDifferentEasingFramesAndLossyReductionWithoutChangingInputs() {
        val base = FloatArray(32)
        val points = (0 until 16).map(::point).toMutableList()
        points[1] = WarpGridMigration.PointKeys(points[1].keys.map { it.copy(easing = EasingType.EASE_IN) })
        assertNull(WarpGridMigration.convert(base, points, 4, 5))
        assertEquals(EasingType.EASE_IN, points[1].keys.first().easing)
        val high = FloatArray(50).also { it[7] = .5f }
        assertNull(WarpGridMigration.convert(high, List(25) { WarpGridMigration.PointKeys(emptyList()) }, 5, 4))
        assertEquals(.5f, high[7], 0f)
    }
}
