package com.vibetoon

import com.vibetoon.domain.paint.ShapeAssist
import org.junit.Assert.*
import org.junit.Test

class ShapeAssistTest {
    private fun p(x: Float, y: Float, pressure: Float = 1f) = ShapeAssist.Point(x, y, pressure)
    @Test fun heldStraightStrokeIsRecognisedWithEndpointPressure() {
        val points = listOf(p(0f, 0f, .3f), p(25f, .5f), p(50f, 1f), p(100f, 0f, .8f))
        assertEquals(listOf(points.first(), points.last()), ShapeAssist.line(points, 500, 1f))
        assertNull(ShapeAssist.line(points, 499, 1f))
    }
    @Test fun tapsShortAndNonFiniteStrokesAreNeverReplaced() {
        assertNull(ShapeAssist.line(listOf(p(0f, 0f), p(1f, 0f), p(2f, 0f)), 1000, 1f))
        assertNull(ShapeAssist.line(listOf(p(0f, 0f), p(Float.NaN, 0f), p(100f, 0f)), 1000, 1f))
        assertNull(ShapeAssist.line(listOf(p(0f, 0f), p(50f, 0f), p(100f, 0f)), 1000, 0f))
    }
    @Test fun curvesLoopsAndBacktrackingAreRejected() {
        assertNull(ShapeAssist.line(listOf(p(0f, 0f), p(50f, 25f), p(100f, 0f)), 600, 1f))
        assertNull(ShapeAssist.line(listOf(p(0f, 0f), p(100f, 0f), p(0f, 0f)), 600, 1f))
        assertNull(ShapeAssist.line(listOf(p(0f, 0f), p(80f, 0f), p(30f, 0f), p(100f, 0f)), 600, 1f))
    }
    @Test fun noiseToleranceScalesWithDensity() {
        val line = listOf(p(0f, 0f), p(50f, 5f), p(100f, 0f))
        assertNull(ShapeAssist.line(line, 500, 1f))
        assertNotNull(ShapeAssist.line(line, 500, 2f))
    }
    @Test fun heldTriangleRequiresThreeStraightEdgesAndClosedPath() {
        val v = listOf(p(0f, 100f), p(50f, 0f), p(100f, 100f), p(0f, 100f))
        val points = (0 until 3).flatMap { edge -> (0 until 12).map { i ->
            val t = i / 12f; p(v[edge].x * (1-t) + v[edge+1].x*t, v[edge].y * (1-t) + v[edge+1].y*t)
        } } + v.last()
        assertEquals(4, ShapeAssist.triangle(points, 500, 1f)!!.size)
        assertNull(ShapeAssist.triangle(points, 499, 1f))
        assertNull(ShapeAssist.triangle(points.dropLast(8), 500, 1f))
        val oval = (0..64).map { i -> val t = 2.0 * Math.PI * i / 64; p((50 + 50 * kotlin.math.cos(t)).toFloat(), (50 + 50 * kotlin.math.sin(t)).toFloat()) }
        assertNull(ShapeAssist.triangle(oval, 500, 1f))
    }
    @Test fun pointCountIsBounded() {
        val points = (0..ShapeAssist.MAX_POINTS).map { p(it.toFloat(), 0f) }
        assertNull(ShapeAssist.line(points, 1000, 1f))
    }
    @Test fun closedEllipseAndRectangleRequireHoldAndRecognisableContour() {
        val oval = (0..64).map { i ->
            val t = 2.0 * Math.PI * i / 64
            p((60 + 50 * kotlin.math.cos(t)).toFloat(), (50 + 35 * kotlin.math.sin(t)).toFloat())
        }
        assertNull(ShapeAssist.closed(oval, 499, 1f))
        val fittedOval = ShapeAssist.closed(oval, 500, 1f)!!
        assertEquals(65, fittedOval.size)
        val rect = (0..40).map { i ->
            val edge = i / 10; val t = (i % 10) * 10f
            when (edge) { 0 -> p(t, 0f); 1 -> p(100f, t); 2 -> p(100f - t, 100f); 3 -> p(0f, 100f - t); else -> p(0f, 0f) }
        }
        assertEquals(5, ShapeAssist.closed(rect, 500, 1f)!!.size)
        assertNull(ShapeAssist.closed(oval.dropLast(10), 500, 1f))
    }

}
