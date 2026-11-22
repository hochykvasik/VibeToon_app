package com.vibetoon

import com.vibetoon.domain.paint.GradientGeometry
import org.junit.Assert.*
import org.junit.Test

class GradientGeometryTest {
    @Test fun preservesScreenCoordinatesUnderRotationAndScale() {
        val q = floatArrayOf(100f, 20f, 100f, 220f, 50f, 220f, 50f, 20f)
        assertArrayEquals(floatArrayOf(100f,20f,0f,200f,-50f,0f,100f,20f,50f,220f),
            GradientGeometry.create(q, 100f, 20f, 50f, 220f)!!, 0f)
    }
    @Test fun rejectsDegenerateAndInvalidGestures() {
        val q = floatArrayOf(0f,0f,100f,0f,100f,100f,0f,100f)
        assertNull(GradientGeometry.create(q, 1f, 1f, 1f, 1f))
        assertNull(GradientGeometry.create(q, 0f, 0f, Float.NaN, 4f))
        assertNull(GradientGeometry.create(floatArrayOf(), 0f, 0f, 4f, 4f))
    }
}
