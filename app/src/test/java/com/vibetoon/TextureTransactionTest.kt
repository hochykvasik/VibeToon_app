package com.vibetoon

import com.vibetoon.domain.raster.RasterTransactionGate
import com.vibetoon.domain.raster.TextureTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class TextureTransactionTest {
    @Test fun failedPublicationRollsBackTextureAndHistory() = runBlocking {
        var texture = "before"
        var history = "before"
        val result = TextureTransaction.execute(
            replace = { texture = "after"; true },
            publish = { history = "partial"; error("publication failed") },
            rollback = { texture = "before"; history = "before"; true }
        )
        assertEquals(TextureTransaction.Outcome.ROLLED_BACK, result)
        assertEquals("before", texture)
        assertEquals("before", history)
    }

    @Test fun failedReplacementDoesNotPublishOrRollback() = runBlocking {
        var called = false
        val result = TextureTransaction.execute(replace = { false }, publish = { called = true }, rollback = { called = true; true })
        assertEquals(TextureTransaction.Outcome.REJECTED, result)
        assertFalse(called)
    }

    @Test fun failedRollbackOrUnknownReplaceRequiresRecovery() = runBlocking {
        assertEquals(TextureTransaction.Outcome.RECOVERY_REQUIRED, TextureTransaction.execute(
            replace = { true }, publish = { error("failed") }, rollback = { false }))
        assertEquals(TextureTransaction.Outcome.RECOVERY_REQUIRED, TextureTransaction.execute(
            replace = { error("unknown") }, publish = {}, rollback = { true }))
    }

    @Test fun runningGlKeepsGateOwnedUntilResultAndPublication() = runBlocking {
        val gate = RasterTransactionGate()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val work = async { gate.run { TextureTransaction.execute(
            replace = { started.complete(Unit); finish.await(); true }, publish = {}, rollback = { true }) } }
        started.await()
        val save = async { gate.run { "saved" } }
        assertNull(withTimeoutOrNull(50) { save.await() })
        finish.complete(Unit)
        assertEquals(TextureTransaction.Outcome.COMMITTED, work.await())
        assertEquals("saved", save.await())
    }
}
