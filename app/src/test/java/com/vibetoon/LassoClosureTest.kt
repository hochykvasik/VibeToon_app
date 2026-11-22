package com.vibetoon

import com.vibetoon.domain.raster.LassoClosure
import org.junit.Assert.assertEquals
import org.junit.Test

class LassoClosureTest {
    @Test fun closedLoopIsNotMistakenForTap() {
        val points = floatArrayOf(0f, 0f, 60f, 0f, 60f, 60f, 0f, 2f)
        assertEquals(LassoClosure.Result.CLOSE, LassoClosure.classify(points, false, 24f))
        assertEquals(LassoClosure.Result.CLOSE, LassoClosure.classify(points, true, 24f))
    }
    @Test fun openStrokeRequiresExplicitClosureOnlyWhenEnabled() {
        val points = floatArrayOf(0f, 0f, 40f, 0f, 40f, 40f)
        assertEquals(LassoClosure.Result.OPEN, LassoClosure.classify(points, true, 24f))
        assertEquals(LassoClosure.Result.CLOSE, LassoClosure.classify(points, false, 24f))
    }
    @Test fun tapsAndInvalidInputDoNotBecomeSelections() {
        assertEquals(LassoClosure.Result.TAP, LassoClosure.classify(floatArrayOf(0f, 0f, 2f, 1f), true, 24f))
        assertEquals(LassoClosure.Result.TAP, LassoClosure.classify(floatArrayOf(0f, 0f, Float.NaN, 1f), true, 24f))
        assertEquals(LassoClosure.Result.OPEN, LassoClosure.classify(floatArrayOf(0f, 0f, 40f, 0f), true, 24f))
    }
    @Test fun resumeOnlyAtStartOrEnd() {
        val points = floatArrayOf(0f, 0f, 80f, 0f, 80f, 80f)
        assertEquals(LassoClosure.Resume.CLOSE, LassoClosure.resume(points, 10f, 0f, 24f))
        assertEquals(LassoClosure.Resume.CONTINUE, LassoClosure.resume(points, 80f, 70f, 24f))
        assertEquals(LassoClosure.Resume.MISS, LassoClosure.resume(points, 40f, 40f, 24f))
        assertEquals(LassoClosure.Resume.MISS, LassoClosure.resume(points, Float.NaN, 0f, 24f))
        assertEquals(LassoClosure.Resume.MISS, LassoClosure.resume(floatArrayOf(0f, 0f), 0f, 0f, 24f))
    }
    @Test fun startHasPriorityWhenMarkersOverlap() {
        val points = floatArrayOf(0f, 0f, 100f, 0f, 10f, 0f)
        assertEquals(LassoClosure.Resume.CLOSE, LassoClosure.resume(points, 5f, 0f, 24f))
    }
    @Test fun manualClosureUsesInclusiveRadius() {
        val points = floatArrayOf(0f, 0f, 40f, 0f, 24f, 0f)
        assertEquals(LassoClosure.Result.CLOSE, LassoClosure.classify(points, true, 24f))
        assertEquals(LassoClosure.Result.OPEN, LassoClosure.classify(points, true, 23f))
    }
}
