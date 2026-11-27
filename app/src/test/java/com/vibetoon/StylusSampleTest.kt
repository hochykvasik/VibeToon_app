package com.vibetoon

import com.vibetoon.domain.input.StylusSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stylus sprint: нажим пальца/пера, исторические точки, точка отпускания и фантомные скачки. */
class StylusSampleTest {
    @Test fun fingerAndBrokenSensorFallBackSafely() {
        assertEquals(1f, StylusSample.pressure(0.3f, stylus = false), 0f)
        assertEquals(1f, StylusSample.pressure(0f, stylus = true), 0f)
        assertEquals(0.4f, StylusSample.pressure(Float.NaN, true, last = 0.4f), 0f)
        assertEquals(0.4f, StylusSample.pressure(0f, true, last = 0.4f), 0f)
        assertEquals("лёгкое касание не исчезает", StylusSample.MIN_PRESSURE, StylusSample.pressure(0.001f, true), 0f)
        assertEquals(1f, StylusSample.pressure(1.7f, true), 0f)
    }

    @Test fun historicalPointsGetTheirOwnPressure() {
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 0.75f), StylusSample.interpolate(0f, 1f, 3), 1e-6f)
        assertEquals(0, StylusSample.interpolate(0f, 1f, 0).size)
        assertArrayEquals("реальный нажим MotionEvent", floatArrayOf(0.3f, 0.35f), StylusSample.historical(floatArrayOf(0.3f, 0.35f), 0.2f, 0.4f, 2, true), 1e-6f)
        assertArrayEquals("ноль в истории заменяется предыдущим", floatArrayOf(0.3f, 0.3f), StylusSample.historical(floatArrayOf(0.3f, 0f), 0.2f, 0.4f, 2, true), 1e-6f)
        assertArrayEquals("размер не совпал - интерполяция", floatArrayOf(0.3f), StylusSample.historical(floatArrayOf(0.9f, 0.9f), 0.2f, 0.4f, 1, true), 1e-6f)
        assertArrayEquals("палец всегда 1.0", floatArrayOf(1f, 1f), StylusSample.historical(floatArrayOf(0.1f, 0.1f), 0.2f, 0.4f, 2, false), 0f)
    }

    @Test fun releaseKeepsWidthAndDropsPhantomJumps() {
        assertEquals("нулевой нажим отпускания не делает хвостик", 0.6f, StylusSample.releasePressure(0f, true, 0.6f), 0f)
        assertEquals(0.8f, StylusSample.releasePressure(0.8f, true, 0.6f), 0f)
        assertEquals(1f, StylusSample.releasePressure(0f, false, 0.6f), 0f)
        assertTrue(StylusSample.keepReleasePoint(0f, 0f, 30f, 40f, 2f))
        assertFalse("скачок на 200 px при подъёме - фантом", StylusSample.keepReleasePoint(0f, 0f, 120f, 160f, 2f))
        assertFalse(StylusSample.keepReleasePoint(0f, 0f, Float.NaN, 0f, 1f))
    }

    @Test fun tiltIsReportedInDegrees() {
        assertEquals(45, StylusSample.tiltDegrees((Math.PI / 4).toFloat()))
        assertEquals(0, StylusSample.tiltDegrees(0f))   // спринт 44: вертикальное перо, а не «нет данных»
        assertNull(StylusSample.tiltDegrees(Float.NaN))
    }
}
