package com.vibetoon

import com.vibetoon.domain.paint.ShapeKind
import com.vibetoon.domain.paint.ShapeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeMathTest {
    @Test fun everyShapeFitsTheUnitSquareAndIsNotEmpty() {
        for (k in ShapeKind.values()) {
            val polys = ShapeMath.polygons(k)
            assertTrue("$k", polys.isNotEmpty() && polys.all { it.size >= 6 && it.size % 2 == 0 })
            val b = ShapeMath.bounds(polys)
            assertTrue("$k ${b.toList()}", b[0] >= 0f && b[1] >= 0f && b[2] <= 1f && b[3] <= 1f)
            assertTrue("$k слишком мала", b[2] - b[0] > 0.7f && b[3] - b[1] > 0.7f)
        }
    }
    @Test fun starHasFiveRays() { assertEquals(10, ShapeMath.polygons(ShapeKind.STAR)[0].size / 2) }
}
