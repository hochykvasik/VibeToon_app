package com.vibetoon

import com.vibetoon.domain.raster.ExpandedSelectionMove
import org.junit.Assert.*
import org.junit.Test

class ExpandedSelectionMoveTest {
    private fun alpha(bytes: ByteArray, w: Int, x: Int, y: Int) = bytes[(y * w + x) * 4 + 3].toInt() and 255

    @Test fun expandsLeftAndBottomWithoutLosingUnselectedPixels() {
        val original = ByteArray(3 * 2 * 4)
        original[(0 * 3 + 0) * 4 + 3] = 255.toByte()
        original[(1 * 3 + 2) * 4 + 3] = 255.toByte()
        val mask = ByteArray(6).also { it[0] = 1 }
        val r = ExpandedSelectionMove.prepare(original, 0, 3, 2, mask, -2, 3)!!
        assertEquals(5, r.width); assertEquals(4, r.height)
        assertEquals(-2, r.left); assertEquals(0, r.top)
        assertEquals(255, alpha(r.after, r.width, 0, 3))
        assertEquals(0, alpha(r.after, r.width, 2, 0))
        assertEquals(255, alpha(r.after, r.width, 4, 1))
        assertEquals(1, r.mask[3 * r.width].toInt())
        assertArrayEquals(original, r.before)
        assertEquals(255, alpha(original, 3, 0, 0))
    }

    @Test fun expandsRightAndTopAndRejectsOversizedOrInvalidInputs() {
        val src = ByteArray(16).also { it[15] = 255.toByte() }
        val mask = byteArrayOf(0, 0, 0, 1)
        val r = ExpandedSelectionMove.prepare(src, 0, 2, 2, mask, 2, -2)!!
        assertEquals(4, r.width); assertEquals(3, r.height)
        assertEquals(0, r.left); assertEquals(-1, r.top)
        assertEquals(255, alpha(r.after, r.width, 3, 0))
        assertNull(ExpandedSelectionMove.prepare(src, 0, 2, 2, mask, Int.MAX_VALUE, 0))
        assertNull(ExpandedSelectionMove.prepare(src, 0, 2, 2, mask, 0, Int.MIN_VALUE))
        assertNull(ExpandedSelectionMove.prepare(src, 0, 2, 2, byteArrayOf(1), 1, 0))
        assertNull(ExpandedSelectionMove.prepare(src, 1, 2, 2, mask, 1, 0))
    }

    @Test fun overlapsCompositePremultipliedPixels() {
        val src = byteArrayOf(64, 0, 0, 128.toByte(), 0, 0, 100, 255.toByte())
        val r = ExpandedSelectionMove.prepare(src, 0, 2, 1, byteArrayOf(1, 0), 1, 0)!!
        assertEquals(64, r.after[4].toInt() and 255)
        assertEquals(49, r.after[6].toInt() and 255)
        assertEquals(255, r.after[7].toInt() and 255)
    }
    @Test fun rejectsExpansionThatNormalProjectReloadWouldDownsample() {
        val width = ExpandedSelectionMove.MAX_SIDE
        val pixels = ByteArray(width * 4)
        val mask = ByteArray(width).also { it[width - 1] = 1 }
        assertNull(ExpandedSelectionMove.prepare(pixels, 0, width, 1, mask, 1, 0))
    }

    @Test fun expansionCanReachButNotExceedReloadSideLimit() {
        val width = ExpandedSelectionMove.MAX_SIDE - 1
        val pixels = ByteArray(width * 4).also { it[width * 4 - 1] = 255.toByte() }
        val mask = ByteArray(width).also { it[width - 1] = 1 }
        val result = ExpandedSelectionMove.prepare(pixels, 0, width, 1, mask, 1, 0)!!
        assertEquals(ExpandedSelectionMove.MAX_SIDE, result.width)
        assertEquals(255, result.after[result.after.size - 1].toInt() and 255)
    }

    @Test fun memoryPlanRejectsBeforeAllocationAndRespectsBudget() {
        val mask = byteArrayOf(1, 0, 0, 0)
        val plan = ExpandedSelectionMove.plan(2, 2, mask, -1, 0)!!
        assertEquals(3, plan.width)
        assertTrue(plan.estimatedCpuBytes > 0)
        assertNull(ExpandedSelectionMove.plan(2, 2, mask, -1, 0, plan.estimatedCpuBytes - 1))
        assertNull(ExpandedSelectionMove.prepare(ByteArray(16), 0, 2, 2, mask, -1, 0, plan.estimatedCpuBytes - 1))
        assertNotNull(ExpandedSelectionMove.plan(2, 2, mask, -1, 0, plan.estimatedCpuBytes))
        assertNull(ExpandedSelectionMove.plan(2, 2, mask, Int.MAX_VALUE, 0, Long.MAX_VALUE))
    }

    @Test fun tutorialCanvasSizeCanHitSideLimitEvenWhenMemoryIsAmple() {
        val width = 1920; val height = 1080
        val selection = ByteArray(width * height).also { it[width - 1] = 1 }
        assertNotNull(ExpandedSelectionMove.plan(width, height, selection, 128, 0, Long.MAX_VALUE))
        assertNull(ExpandedSelectionMove.plan(width, height, selection, 129, 0, Long.MAX_VALUE))
        assertNull(ExpandedSelectionMove.plan(width, height, selection, 1, 0, 1L))
    }

}
