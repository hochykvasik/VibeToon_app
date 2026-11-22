package com.vibetoon

import com.vibetoon.domain.paint.RulerPath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class RulerPathTest {
    @Test fun straightLinePreservesDirection() {
        val a = RulerPath.Point(10f, 20f); val b = RulerPath.Point(-20f, 50f)
        assertEquals(listOf(a, b), RulerPath.path(RulerPath.Mode.LINE, a, b))
    }
    @Test fun compassUsesStartAsCenterAndClosesAtEnd() {
        val a = RulerPath.Point(10f, 20f); val b = RulerPath.Point(40f, 60f)
        val p = RulerPath.path(RulerPath.Mode.COMPASS, a, b)!!
        assertEquals(b.x, p.first().x, .001f); assertEquals(b.y, p.first().y, .001f)
        assertEquals(p.first().x, p.last().x, .001f); assertEquals(p.first().y, p.last().y, .001f)
        p.forEach { assertEquals(50f, hypot(it.x - a.x, it.y - a.y), .001f) }
    }
    @Test fun cancelDegenerateAndNonfiniteGestures() {
        val a = RulerPath.Point(0f, 0f)
        assertNull(RulerPath.path(RulerPath.Mode.COMPASS, a, a))
        assertNull(RulerPath.path(RulerPath.Mode.OFF, a, RulerPath.Point(4f, 0f)))
        assertNull(RulerPath.path(RulerPath.Mode.LINE, a, RulerPath.Point(Float.NaN, 0f)))
    }
}
