package com.vibetoon

import com.vibetoon.domain.export.GalleryCopy
import org.junit.Assert.*
import org.junit.Test

class GalleryCopyTest {
    @Test fun publishesOnlyAfterWriting() {
        val calls = mutableListOf<String>()
        val ok = GalleryCopy.publish(create = { calls += "create"; 1 }, write = { calls += "write" },
            finish = { calls += "finish"; true }, discard = { calls += "delete" })
        assertTrue(ok); assertEquals(listOf("create", "write", "finish"), calls)
    }
    @Test fun removesPartialEntryOnWriteOrPublishFailure() {
        for (failWrite in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val ok = GalleryCopy.publish(create = { 1 }, write = { if (failWrite) throw java.io.IOException() },
                finish = { false }, discard = { calls += "delete" })
            assertFalse(ok); assertEquals(listOf("delete"), calls)
        }
        assertFalse(GalleryCopy.publish(create = { null as Int? }, write = {}, finish = { true }, discard = { fail("nothing to delete") }))
    }
}
