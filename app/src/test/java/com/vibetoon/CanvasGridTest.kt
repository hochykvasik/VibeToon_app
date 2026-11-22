package com.vibetoon

import com.vibetoon.domain.render.Camera
import com.vibetoon.domain.render.CanvasGrid
import org.junit.Assert.*
import org.junit.Test

class CanvasGridTest {
    @Test fun gridIsClippedToCanvasAndMovesWithCamera() {
        val before = CanvasGrid.lines(1000f, 800f, 1000, 500, Camera(), 8)!!
        val after = CanvasGrid.lines(1000f, 800f, 1000, 500, Camera(panX = 20f, panY = -10f), 8)!!
        assertEquals(before.left + 20f, after.left, 0.001f)
        assertEquals(before.top - 10f, after.top, 0.001f)
        assertTrue(after.vertical.all { it >= after.left - 0.001f && it <= after.right + 0.001f })
        assertTrue(after.horizontal.all { it >= after.top - 0.001f && it <= after.bottom + 0.001f })
    }

    @Test fun densityIsSquareAndStableUnderZoom() {
        for (count in CanvasGrid.densityOptions) {
            val grid = CanvasGrid.lines(1000f, 1000f, 1000, 1000, Camera(), count)!!
            val spacing = 950f / count
            assertEquals(spacing, grid.vertical[1] - grid.vertical[0], 0.001f)
            assertEquals(spacing, grid.horizontal[1] - grid.horizontal[0], 0.001f)
            val zoom = CanvasGrid.lines(1000f, 1000f, 1000, 1000, Camera(zoom = 0.5f), count)!!
            assertEquals(spacing * 0.5f, zoom.vertical[1] - zoom.vertical[0], 0.001f)
        }
        assertEquals(16, CanvasGrid.density(10000))
        assertNull(CanvasGrid.lines(1000f, 800f, 1000, 500, Camera(panX = 10000f)))
        assertNull(CanvasGrid.lines(0f, 800f, 1000, 500, Camera()))
    }

    @Test fun extremeAspectRatioDoesNotProduceUnboundedLines() {
        val grid = CanvasGrid.lines(1000f, 1000f, 1, 8192, Camera(), 64)!!
        assertTrue(grid.horizontal.size <= 2048)
    }
}
