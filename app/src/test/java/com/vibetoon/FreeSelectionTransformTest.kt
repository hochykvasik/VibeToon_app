package com.vibetoon

import com.vibetoon.domain.project.*
import com.vibetoon.domain.raster.*
import org.junit.Assert.*
import org.junit.Test

class FreeSelectionTransformTest {
    private fun p(x: Float, y: Float) = FreeSelectionTransform.Point(x,y)
    @Test fun gestureMapsBothContactsAndRejectsBadScale() {
        val g = FreeSelectionTransform.Gesture(p(2f,2f), p(6f,2f), p(3f,3f), p(3f,11f))
        val m = FreeSelectionTransform.matrix(g)!!
        assertEquals(3.0, m.x(2.0, 2.0), .00001); assertEquals(3.0, m.y(2.0, 2.0), .00001)
        assertEquals(3.0, m.x(6.0, 2.0), .00001); assertEquals(11.0, m.y(6.0, 2.0), .00001)
        assertNull(FreeSelectionTransform.matrix(g.copy(endB = p(100f, 0f))))
        assertNull(FreeSelectionTransform.matrix(g.copy(b = g.a)))
        assertNull(FreeSelectionTransform.matrix(g.copy(endA = p(Float.NaN, 0f))))
    }
    @Test fun translationMovesPixelsAndKeepsHistoryAndRejectsBoundary() {
        val m = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/ink.png", 8, 8, 0f,0f,8f,8f)
        val pixels = ByteArray(256); val mask = ByteArray(64)
        mask[2*8+2] = 1; pixels[(2*8+2)*4] = 33; pixels[(2*8+2)*4+3] = 255.toByte()
        pixels[(7*8+7)*4] = 50; pixels[(7*8+7)*4+3] = 255.toByte()
        val g = FreeSelectionTransform.Gesture(p(1f,1f),p(5f,1f),p(2f,2f),p(6f,2f))
        val change = FreeSelectionTransform.prepare(m,pixels,0,8,8,mask,g,Long.MAX_VALUE)!!
        assertEquals(0, change.after.rgba[(2*8+2)*4].toInt())
        assertEquals(33, change.after.rgba[(3*8+3)*4].toInt() and 255)
        assertEquals(50, change.after.rgba[(7*8+7)*4].toInt() and 255)
        assertEquals(1, change.selection[3*8+3].toInt())
        assertArrayEquals(pixels, change.before.rgba)
        assertNull(FreeSelectionTransform.prepare(m,pixels,0,8,8,mask,g.copy(endA=p(20f,2f),endB=p(24f,2f)),Long.MAX_VALUE))
        assertNull(FreeSelectionTransform.prepare(m,pixels,0,8,8,mask,g,0))
    }
}
