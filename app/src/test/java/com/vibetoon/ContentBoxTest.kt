package com.vibetoon

import com.vibetoon.domain.raster.RasterMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBoxTest {
    @Test fun boxFollowsOpaquePixelsNotWholeLayer() {
        val w = 100; val h = 50
        val a = ByteArray(w * h)
        for (y in 10..19) for (x in 20..39) a[y * w + x] = 0xFF.toByte()
        val b = RasterMath.alphaBox(a, 0, w, h)
        assertEquals(0.20f, b[0], 1e-4f); assertEquals(0.20f, b[1], 1e-4f)
        assertEquals(0.40f, b[2], 1e-4f); assertEquals(0.40f, b[3], 1e-4f)
        assertTrue(RasterMath.alphaBox(ByteArray(w * h), 0, w, h).isEmpty())
    }

    @Test fun subQuadMapsBoxInsideLayerQuad() {
        val q = floatArrayOf(0f, 0f, 200f, 0f, 200f, 100f, 0f, 100f)
        val s = RasterMath.subQuad(q, floatArrayOf(0.25f, 0.5f, 0.75f, 1f))
        assertEquals(50f, s[0], 1e-3f); assertEquals(50f, s[1], 1e-3f)
        assertEquals(150f, s[4], 1e-3f); assertEquals(100f, s[5], 1e-3f)
    }
}
