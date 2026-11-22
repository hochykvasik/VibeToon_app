package com.vibetoon

import com.vibetoon.domain.paint.HistoryMemoryBudget
import com.vibetoon.domain.raster.ExpandedLayerHistory
import com.vibetoon.domain.raster.ExpandedLayerSnapshot
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import org.junit.Assert.*
import org.junit.Test

class HistoryMemoryBudgetTest {
    @Test fun countsFullSnapshotsIncludingSelectionAndTrimsOldestPrefix() {
        val media = MediaItem(1, MediaKind.IMAGE, "x", "assets/x.png", 2, 2, 0f, 0f, 2f, 2f)
        val pixels = ByteArray(16).also { it[3] = 255.toByte() }
        val change = ExpandedLayerSnapshot.prepare(media, pixels, 0, 2, 2, byteArrayOf(1, 0, 0, 0), -1, 0)!!
        val history = ExpandedLayerHistory(change)
        assertEquals(16L + 24L + 6L, history.sizeBytes)
        assertEquals(HistoryMemoryBudget.Trim(2, 0), HistoryMemoryBudget.trim(listOf(5, history.sizeBytes, 10), listOf(3), 20))
    }

    @Test fun trimRedoFromBottomPreservesUsableTopAndHandlesOversize() {
        assertEquals(HistoryMemoryBudget.Trim(1, 1), HistoryMemoryBudget.trim(listOf(10), listOf(70, 10), 20))
        assertEquals(HistoryMemoryBudget.Trim(1, 0), HistoryMemoryBudget.trim(listOf(100), emptyList(), 10))
        assertEquals(HistoryMemoryBudget.Trim(0, 0), HistoryMemoryBudget.trim(listOf(10), listOf(20), 30))
        assertEquals(HistoryMemoryBudget.Trim(1, 0), HistoryMemoryBudget.trim(listOf(Long.MAX_VALUE, 10), emptyList(), 20))
    }
}
