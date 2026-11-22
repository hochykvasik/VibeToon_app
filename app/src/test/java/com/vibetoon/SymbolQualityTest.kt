package com.vibetoon

import com.vibetoon.domain.project.SymbolMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Символы (Sprint 6.9.1): картинка экземпляра в полном размере холста, чёткие миниатюры, повторы без «пустого» экземпляра. */
class SymbolQualityTest {
    @Test fun renderSizeIsFullCanvasResolution() {
        // раньше потолок был 1024 px по длинной стороне: 1920 x 1080 -> 1024 x 576, текстура растягивалась на холст (мыло)
        assertEquals(1920 to 1080, SymbolMath.renderSize(1920, 1080, 4096))
        assertEquals(2048 to 2048, SymbolMath.renderSize(2048, 2048, 8192))
        assertEquals(1080 to 1920, SymbolMath.renderSize(1080, 1920, 4096))
    }

    @Test fun renderSizeOnlyShrinksBeyondGpuLimit() {
        val (w, h) = SymbolMath.renderSize(8000, 4000, 4096)
        assertEquals(4096, w); assertEquals(2048, h)
        assertEquals(1 to 1, SymbolMath.renderSize(0, -5, 4096))
    }

    @Test fun thumbnailsAreSharpButNeverUpscaled() {
        assertEquals(320 to 180, SymbolMath.thumbSize(1920, 1080))
        assertEquals(180 to 320, SymbolMath.thumbSize(1080, 1920))
        assertEquals(100 to 50, SymbolMath.thumbSize(100, 50))
        assertTrue(SymbolMath.THUMB_SIDE >= 256)
    }

    @Test fun retryBackoffGrowsAndIsCapped() {
        assertEquals(400L, SymbolMath.retryDelayMs(1))
        assertEquals(800L, SymbolMath.retryDelayMs(2))
        assertEquals(1600L, SymbolMath.retryDelayMs(3))
        assertEquals(5000L, SymbolMath.retryDelayMs(10))
        assertEquals(400L, SymbolMath.retryDelayMs(0))
    }

    @Test fun cacheKeyChangesWithRevision() {
        assertNotEquals(SymbolMath.cacheKey(5, 2, 0), SymbolMath.cacheKey(5, 2, 1))
        assertNotEquals(SymbolMath.cacheKey(5, 2, 0), SymbolMath.cacheKey(5, 3, 0))
        assertEquals(SymbolMath.cacheKey(5, 2, 7), SymbolMath.cacheKey(5, 2, 7))
    }
}
