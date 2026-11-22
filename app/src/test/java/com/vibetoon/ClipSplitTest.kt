package com.vibetoon

import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.ClipSplit
import org.junit.Assert.*
import org.junit.Test

class ClipSplitTest {
    @Test fun splitsMultipleAtomicallyAndPreservesSourceOffsets() {
        val clips = listOf(Clip(0, 20, speed = 2f, offset = 3), Clip(4, 24, speed = 1f, reverse = true, offset = 2))
        val result = ClipSplit.split(clips, 10)!!
        assertEquals(2, result.size)
        assertEquals(23, result[0].second.offset)
        assertEquals(16, result[1].first.offset)
        assertEquals(2, result[1].second.offset)
        assertEquals(10, result[0].first.end)
        assertEquals(10, result[1].second.start)
        assertNull(ClipSplit.split(clips, 4))
        assertNull(ClipSplit.split(emptyList(), 4))
    }
}
