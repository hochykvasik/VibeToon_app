package com.vibetoon

import com.vibetoon.domain.export.VideoPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPixelsTest {
    private fun rgba(vararg pixels: Int): ByteArray {
        val out = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { i, argb ->
            out[i * 4] = ((argb shr 16) and 255).toByte()
            out[i * 4 + 1] = ((argb shr 8) and 255).toByte()
            out[i * 4 + 2] = (argb and 255).toByte()
            out[i * 4 + 3] = ((argb shr 24) and 255).toByte()
        }
        return out
    }

    @Test fun blackAndWhiteUseLimitedRange() {
        val black = VideoPixels.fromGlRgba(rgba(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()), 2, 2)
        assertTrue(black.y.all { it.toInt() and 0xFF == 16 })
        assertTrue(black.u.all { it.toInt() and 0xFF == 128 })
        assertTrue(black.v.all { it.toInt() and 0xFF == 128 })
        val white = VideoPixels.fromGlRgba(rgba(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()), 2, 2)
        assertTrue(white.y.all { it.toInt() and 0xFF == 235 })
        assertTrue(white.u.all { it.toInt() and 0xFF == 128 })
        assertTrue(white.v.all { it.toInt() and 0xFF == 128 })
    }

    @Test fun redConvertsToBt601() {
        val red = VideoPixels.fromGlRgba(rgba(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFFFF0000.toInt()), 2, 2)
        assertEquals(82, red.y[0].toInt() and 0xFF)
        assertEquals(90, red.u[0].toInt() and 0xFF)
        assertEquals(240, red.v[0].toInt() and 0xFF)
    }

    @Test fun glBottomRowBecomesTopRow() {
        // GL readback: first row is bottom. Bottom=red, top=blue.
        val img = VideoPixels.fromGlRgba(rgba(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt()), 2, 2)
        assertEquals(41, img.y[0].toInt() and 0xFF)   // top row after flip = blue (GL bottom row was red)
        assertEquals(82, img.y[2].toInt() and 0xFF)   // bottom row = red
    }

    @Test fun rejectsOddOrMismatchedBuffers() {
        try { VideoPixels.fromGlRgba(ByteArray(12), 3, 1); assertTrue(false) } catch (e: IllegalArgumentException) { }
        try { VideoPixels.fromGlRgba(ByteArray(8), 2, 2); assertTrue(false) } catch (e: IllegalArgumentException) { }
    }
}
