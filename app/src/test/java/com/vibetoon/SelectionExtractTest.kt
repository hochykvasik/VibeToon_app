package com.vibetoon

import com.vibetoon.domain.raster.SelectionExtract
import org.junit.Assert.*
import org.junit.Test

class SelectionExtractTest {
    @Test fun selectedPixelsAreCopiedAndCutClearsOnlyMask() {
        val rgba = byteArrayOf(10, 20, 30, 127, 40, 50, 60, -1, 20, 10, 5, 64, 0, 0, 0, 0)
        val original = rgba.copyOf()
        val part = SelectionExtract.extract(rgba, 0, 2, 2, byteArrayOf(1, 0, 1, 0))!!
        assertEquals(127, part.argb[0] ushr 24)
        assertEquals(64, part.argb[2] ushr 24)
        assertEquals(0, part.argb[1]); assertEquals(0, part.argb[3])
        assertArrayEquals(original, rgba)
        assertArrayEquals(byteArrayOf(10, 20, 30, 127, 20, 10, 5, 64), part.cut.before)
        assertArrayEquals(ByteArray(8), part.cut.after)
        assertEquals(0, part.cut.rect.x); assertEquals(0, part.cut.rect.y)
        assertEquals(1, part.cut.rect.w); assertEquals(2, part.cut.rect.h)
    }
    @Test fun preservesChannelWithinValidPremultipliedRange() {
        for (a in 1..255) for (channel in 0..a) {
            val p = byteArrayOf(channel.toByte(), 0, 0, a.toByte())
            val c = SelectionExtract.extract(p, 0, 1, 1, byteArrayOf(1))!!.argb[0]
            val straight = (c ushr 16) and 255
            assertEquals(channel, (straight * a + 127) / 255)
        }
    }
    @Test fun emptyInvalidAndOversizedInputsAreRejected() {
        assertNull(SelectionExtract.extract(ByteArray(8), 0, 1, 1, byteArrayOf(0)))
        assertNull(SelectionExtract.extract(ByteArray(4), 0, 2, 1, byteArrayOf(1)))
        assertNull(SelectionExtract.extract(ByteArray(4), -1, 1, 1, byteArrayOf(1)))
        assertNull(SelectionExtract.extract(ByteArray(4), 0, 5000, 5000, byteArrayOf(1)))
        assertNull(SelectionExtract.extract(byteArrayOf(0,0,0,0), 0, 1, 1, byteArrayOf(1)))
    }
    @Test fun headerOffsetAndCopyLeaveOriginalIntact() {
        val px = byteArrayOf(99, 98, 97, 96, 95, 94, 93, 92, 4, 8, 12, 16)
        val orig = px.copyOf()
        val r = SelectionExtract.extract(px, 8, 1, 1, byteArrayOf(1))!!
        assertEquals(16, r.argb[0] ushr 24)
        assertArrayEquals(orig, px)
        assertArrayEquals(byteArrayOf(4, 8, 12, 16), r.cut.before)
    }
    @Test fun cutUndoRedoPatchAndCopyUseTheSameSelection() {
        val source = byteArrayOf(10, 20, 30, 127, 40, 50, 60, -1)
        val part = SelectionExtract.extract(source, 0, 2, 1, byteArrayOf(1, 0))!!
        val original = source.copyOf()
        val op = com.vibetoon.domain.paint.PaintOp(100001, part.cut.rect.x, part.cut.rect.y, part.cut.rect.w, part.cut.rect.h, part.cut.before, part.cut.after)
        assertEquals(0, part.argb[1])
        System.arraycopy(op.after()!!, 0, source, 0, 4)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 40, 50, 60, -1), source)
        System.arraycopy(op.before()!!, 0, source, 0, 4)
        assertArrayEquals(original, source)
        System.arraycopy(op.after()!!, 0, source, 0, 4)
        assertEquals(0, source[3].toInt())
    }

}
