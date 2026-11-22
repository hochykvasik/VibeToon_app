package com.vibetoon

import com.vibetoon.domain.raster.RasterMemoryBudget
import com.vibetoon.domain.raster.ExpandedSelectionMove
import org.junit.Assert.*
import org.junit.Test

class RasterMemoryBudgetTest {
    private val mb = 1024L * 1024
    @Test fun usesTheSmallestHeapSystemAndPolicyHeadroom() {
        assertEquals(34 * mb, RasterMemoryBudget.available(100 * mb, 80 * mb, 30 * mb, 500 * mb, 0))
        assertEquals(8 * mb, RasterMemoryBudget.available(500 * mb, 0, 0, 40 * mb, 0))
        assertEquals(0L, RasterMemoryBudget.available(500 * mb, 0, 0, 40 * mb, 50 * mb))
        assertEquals(ExpandedSelectionMove.DEFAULT_CPU_BUDGET, RasterMemoryBudget.available(1000 * mb, 0, 0, 1000 * mb, 0))
        assertEquals(0L, RasterMemoryBudget.available(0, 10, 0, 100, 0))
    }
    @Test fun estimatedDeviceHeadroomCanRejectOrAllowPlanWithoutAllocatingTexture() {
        val mask = byteArrayOf(1, 0, 0, 0)
        val low = RasterMemoryBudget.available(20 * mb, 15 * mb, 0, 100 * mb, 0)
        assertNull(ExpandedSelectionMove.plan(2, 2, mask, -1, 0, low))
        val enough = RasterMemoryBudget.available(128 * mb, 20 * mb, 0, 500 * mb, 0)
        assertNotNull(ExpandedSelectionMove.plan(2, 2, mask, -1, 0, enough))
        assertEquals(1, mask[0].toInt())
    }

}
