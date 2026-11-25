package com.vibetoon

import com.vibetoon.domain.perf.CellCacheKey
import com.vibetoon.domain.perf.CellCachePolicy
import com.vibetoon.domain.perf.VideoDecodePolicy
import com.vibetoon.domain.project.FrameCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 31: пул декодирования, бюджет кэша и предзагрузка рисунков; потоки и размер кадров видео. */
class CellCachePolicyTest {
    private val mib = 1024L * 1024L
    private val cells = listOf(FrameCell(0, "a"), FrameCell(4, "b"), FrameCell(8, "c"), FrameCell(12, "d"))

    @Test fun decodeThreadsFollowCoresAndLowRam() {
        assertEquals(1, CellCachePolicy.decodeThreads(8, lowRam = true))
        assertEquals(1, CellCachePolicy.decodeThreads(2, lowRam = false))
        assertEquals(2, CellCachePolicy.decodeThreads(4, lowRam = false))
        assertEquals(3, CellCachePolicy.decodeThreads(8, lowRam = false))
        assertEquals(3, CellCachePolicy.decodeThreads(16, lowRam = false))
        assertEquals(1, CellCachePolicy.decodeThreads(0, lowRam = false))
    }

    @Test fun budgetIsQuarterOfMemoryClassClamped() {
        assertEquals(64 * mib, CellCachePolicy.budgetBytes(256, false))
        assertEquals(128 * mib, CellCachePolicy.budgetBytes(512, false))
        assertEquals(160 * mib, CellCachePolicy.budgetBytes(1024, false))
        assertEquals(24 * mib, CellCachePolicy.budgetBytes(64, false))
        assertEquals(64 * mib, CellCachePolicy.budgetBytes(0, false))     // неизвестно - как 256 МБ
        assertEquals(16 * mib, CellCachePolicy.budgetBytes(512, true))
    }

    @Test fun trimLevelsShrinkOrDrop() {
        val b = 64 * mib
        assertEquals(b, CellCachePolicy.trimmedBudget(b, 5))
        assertEquals(b / 2, CellCachePolicy.trimmedBudget(b, 10))
        assertEquals(0L, CellCachePolicy.trimmedBudget(b, 15))
        assertEquals(0L, CellCachePolicy.trimmedBudget(b, 20))   // UI_HIDDEN: приложение ушло в фон
    }

    @Test fun prefetchDepthSharesHalfBudgetBetweenLayers() {
        val fullHd = CellCachePolicy.bitmapBytes(1920, 1080)
        assertEquals(1920L * 1080 * 4, fullHd)
        assertEquals(4, CellCachePolicy.prefetchDepth(fullHd, 64 * mib, 1))   // 32 МБ / 7,9 МБ
        assertEquals(2, CellCachePolicy.prefetchDepth(fullHd, 64 * mib, 2))
        assertEquals(1, CellCachePolicy.prefetchDepth(fullHd, 64 * mib, 3))
        assertEquals(0, CellCachePolicy.prefetchDepth(fullHd, 16 * mib, 2))   // не помещается - выкл.
        assertEquals(4, CellCachePolicy.prefetchDepth(CellCachePolicy.bitmapBytes(512, 512), 64 * mib, 1))
        assertEquals(0, CellCachePolicy.prefetchDepth(0, 64 * mib, 1))
        assertEquals(0, CellCachePolicy.prefetchDepth(fullHd, 64 * mib, 0))
    }

    @Test fun prefetchFollowsPlayDirection() {
        assertEquals(listOf("b", "c"), CellCachePolicy.prefetchFiles(cells, 1, +1, 2, loop = false))
        assertEquals(listOf("c", "b"), CellCachePolicy.prefetchFiles(cells, 13, -1, 2, loop = false))
        assertEquals(emptyList<String>(), CellCachePolicy.prefetchFiles(cells, 13, +1, 2, loop = false))
    }

    @Test fun prefetchWrapsWhenLooping() {
        assertEquals(listOf("a", "b"), CellCachePolicy.prefetchFiles(cells, 12, +1, 2, loop = true))
        assertEquals(listOf("d", "c"), CellCachePolicy.prefetchFiles(cells, 0, -1, 2, loop = true))
        // глубина больше числа рисунков: каждый файл один раз, активный не входит
        assertEquals(listOf("c", "d", "a"), CellCachePolicy.prefetchFiles(cells, 5, +1, 10, loop = true))
    }

    @Test fun prefetchSkipsActiveAndDuplicateFiles() {
        val reused = listOf(FrameCell(0, "a"), FrameCell(2, "b"), FrameCell(4, "a"), FrameCell(6, "c"))
        assertEquals(listOf("b", "c"), CellCachePolicy.prefetchFiles(reused, 0, +1, 3, loop = false))
        assertEquals(emptyList<String>(), CellCachePolicy.prefetchFiles(listOf(FrameCell(0, "a")), 0, +1, 2, true))
        assertEquals(emptyList<String>(), CellCachePolicy.prefetchFiles(cells, 0, +1, 0, true))
        // несортированный список и кадр до первого рисунка
        assertEquals(listOf("b"), CellCachePolicy.prefetchFiles(cells.reversed(), -3, +1, 1, false))
    }

    @Test fun directionIsForwardDuringPlaybackEvenOnLoopWrap() {
        assertEquals(1, CellCachePolicy.direction(47, 0, playing = true))
        assertEquals(-1, CellCachePolicy.direction(10, 9, playing = false))
        assertEquals(1, CellCachePolicy.direction(9, 10, playing = false))
        assertEquals(1, CellCachePolicy.direction(5, 5, playing = false))
    }

    @Test fun cacheKeyChangesWhenFileIsRewritten() {
        val before = CellCacheKey("/p/cell_1.png", 1000L, 5000L)
        assertNotEquals(before, CellCacheKey("/p/cell_1.png", 2000L, 5000L))
        assertNotEquals(before, CellCacheKey("/p/cell_1.png", 1000L, 5004L))
        assertEquals(before, CellCacheKey("/p/cell_1.png", 1000L, 5000L))
    }

    @Test fun videoThreadsAndPreviewSize() {
        assertEquals(1, VideoDecodePolicy.threads(4))
        assertEquals(2, VideoDecodePolicy.threads(8))
        assertEquals(2, VideoDecodePolicy.threads(12))
        assertEquals(1, VideoDecodePolicy.threads(1))
        assertEquals(1280, VideoDecodePolicy.maxSide(false, 3))
        assertEquals(960, VideoDecodePolicy.maxSide(true, 1))   // спринт 46: последовательный декодер
        assertEquals(720, VideoDecodePolicy.maxSide(true, 2))
        assertEquals(480, VideoDecodePolicy.maxSide(true, 4))
        assertTrue(VideoDecodePolicy.maxSide(true, 2) < VideoDecodePolicy.maxSide(true, 1))
    }
}
