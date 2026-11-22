package com.vibetoon

import com.vibetoon.domain.project.CanvasMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasMathTest {
    @Test fun resizeScalesPositionsSizesAndOffsets() {
        val m = CanvasMap.resize(1920, 1080, 960, 540)
        assertEquals(100f, m.x(200f), 0.001f); assertEquals(50f, m.y(100f), 0.001f)
        assertEquals(240f, m.w(480f), 0.001f); assertEquals(-10f, m.dx(-20f), 0.001f)
        assertEquals(0.5f, m.text(1f), 0.001f)
    }

    @Test fun cropShiftsOriginKeepsSizes() {
        val m = CanvasMap.crop(100, 50)
        assertEquals(0f, m.x(100f), 0.001f); assertEquals(150f, m.y(200f), 0.001f)
        assertEquals(480f, m.w(480f), 0.001f); assertEquals(-20f, m.dx(-20f), 0.001f)   // смещение трансформации не двигается
    }

    @Test fun cropValidationAndRatio() {
        assertTrue(CanvasMap.validCrop(1920, 1080, 0, 0, 1920, 1080))
        assertFalse(CanvasMap.validCrop(1920, 1080, 100, 0, 1920, 1080))   // вылезла вправо
        assertFalse(CanvasMap.validCrop(1920, 1080, 0, 0, 10, 10))          // меньше минимума
        assertEquals(540, CanvasMap.keepRatio(1920, 1080, 960))
    }
}
