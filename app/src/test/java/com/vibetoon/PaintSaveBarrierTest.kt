package com.vibetoon

import com.vibetoon.domain.project.PaintSaveBarrier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PaintSaveBarrierTest {
    @Test fun pngFailurePreventsMetadataPublication() = runBlocking {
        var json = "old geometry"
        val saved = PaintSaveBarrier.save(paint = { false }, metadata = { json = "new geometry" })
        assertFalse(saved)
        assertEquals("old geometry", json)
    }

    @Test fun jsonPublishedOnlyAfterPngSuccess() = runBlocking {
        val steps = mutableListOf<String>()
        val saved = PaintSaveBarrier.save(paint = { steps += "png"; true }, metadata = { steps += "json" })
        assertTrue(saved)
        assertEquals(listOf("png", "json"), steps)
    }

    @Test fun metadataFailureIsNotReportedAsSuccess() = runBlocking {
        try {
            PaintSaveBarrier.save(paint = { true }, metadata = { error("disk failure") })
            fail("must propagate failure")
        } catch (e: IllegalStateException) { assertEquals("disk failure", e.message) }
    }
}
