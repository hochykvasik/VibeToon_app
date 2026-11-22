package com.vibetoon

import com.vibetoon.domain.project.ThumbnailBounds
import org.junit.Assert.*
import org.junit.Test

class ThumbnailBoundsTest {
    @Test fun cropsSmallContentWithPadding() {
        val p = IntArray(100 * 80)
        for (y in 35..39) for (x in 45..54) p[y * 100 + x] = 0x80010203.toInt()
        assertEquals(ThumbnailBounds.Rect(44, 34, 12, 7), ThumbnailBounds.padded(p, 100, 80))
    }
    @Test fun preservesEdgeContentAndIgnoresTransparentRgb() {
        val p = IntArray(20 * 20) { 0x00ffffff }
        p[0] = 0x01000000
        assertEquals(ThumbnailBounds.Rect(0, 0, 2, 2), ThumbnailBounds.padded(p, 20, 20))
    }
    @Test fun handlesEmptyInvalidAndFullFrame() {
        assertNull(ThumbnailBounds.padded(IntArray(9), 3, 3))
        assertNull(ThumbnailBounds.padded(IntArray(9), 10, 10))
        assertNull(ThumbnailBounds.padded(IntArray(9), 0, 10))
        assertEquals(ThumbnailBounds.Rect(0, 0, 2, 2), ThumbnailBounds.padded(IntArray(4) { -1 }, 2, 2))
    }
}
