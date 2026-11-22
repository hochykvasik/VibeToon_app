package com.vibetoon

import com.vibetoon.domain.project.MediaIdAllocator
import com.vibetoon.domain.project.MEDIA_ID_BASE
import org.junit.Assert.*
import org.junit.Test

class MediaIdAllocatorTest {
    @Test fun deletingAnImportedLayerDoesNotReuseItsIdForNewDrawing() {
        val ids = MediaIdAllocator()
        val imported = ids.next(emptyList())
        val drawing = ids.next(emptyList())
        assertEquals(MEDIA_ID_BASE, imported)
        assertEquals(imported + 1, drawing)
    }
    @Test fun staleSavedLayerStateIsNotAssignedToNewMedia() {
        val ids = MediaIdAllocator()
        assertEquals(MEDIA_ID_BASE + 6, ids.next(listOf(MEDIA_ID_BASE + 5, -12, 200000)))
        ids.reset()
        assertEquals(MEDIA_ID_BASE, ids.next(emptyList()))
    }
}
