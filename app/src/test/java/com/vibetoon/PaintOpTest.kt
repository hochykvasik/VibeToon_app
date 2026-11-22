package com.vibetoon

import com.vibetoon.domain.paint.PaintBudget
import com.vibetoon.domain.paint.PaintOp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PaintOpTest {
    private fun packet(x: Int, y: Int, w: Int, h: Int, before: ByteArray, after: ByteArray): ByteArray {
        val out = ByteArray(16 + before.size + after.size)
        val hdr = intArrayOf(x, y, w, h)
        for (i in 0 until 4) for (k in 0 until 4) out[i * 4 + k] = ((hdr[i] shr (8 * k)) and 0xFF).toByte()
        before.copyInto(out, 16); after.copyInto(out, 16 + before.size)
        return out
    }

    @Test fun parseCompressAndRestoreRoundTrip() {
        val w = 40; val h = 30
        val rnd = Random(7)
        val before = ByteArray(w * h * 4).also { rnd.nextBytes(it) }
        val after = ByteArray(w * h * 4) { 0 }          // однородный буфер сжимается почти в ничто
        val op = PaintOp.parse(5, packet(12, 34, w, h, before, after))!!
        assertEquals(5, op.layerId); assertEquals(12, op.x); assertEquals(34, op.y); assertEquals(w, op.w); assertEquals(h, op.h)
        val raw = op.sizeBytes
        op.compress()
        assertArrayEquals(before, op.before())
        assertArrayEquals(after, op.after())
        assertTrue("после сжатия занимает меньше: ${op.sizeBytes} < $raw", op.sizeBytes < raw)
    }

    @Test fun badPacketIsRejected() {
        assertNull(PaintOp.parse(1, ByteArray(8)))
        assertNull(PaintOp.parse(1, packet(0, 0, 10, 10, ByteArray(10), ByteArray(10))))   // буферы короче w*h*4
    }

    @Test fun evictFreesMemory() {
        val op = PaintOp(1, 0, 0, 2, 2, ByteArray(16), ByteArray(16))
        assertNotNull(op.before())
        op.evict()
        assertNull(op.before()); assertNull(op.after()); assertEquals(0L, op.sizeBytes); assertTrue(op.evicted)
    }

    @Test fun budgetEvictsOldestFirst() {
        assertEquals(0, PaintBudget.overflow(listOf(10L, 20L), limit = 100))
        assertEquals(2, PaintBudget.overflow(listOf(100L, 100L, 100L), limit = 150))
        assertEquals(1, PaintBudget.overflow(listOf(100L, 10L, 10L), limit = 50))
        assertEquals(3, PaintBudget.overflow(listOf(300L, 300L, 300L), limit = 0))
    }
}
