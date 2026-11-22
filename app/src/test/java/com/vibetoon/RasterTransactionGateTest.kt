package com.vibetoon

import com.vibetoon.domain.raster.RasterTransactionGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RasterTransactionGateTest {
    @Test fun saveAndProjectResetWaitForTransactionPublication() = runBlocking {
        val gate = RasterTransactionGate()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val tx = launch { gate.run { events += "texture"; entered.complete(Unit); finish.await(); events += "geometry" } }
        entered.await()
        val save = async { gate.run { events += "save" } }
        val reset = async { gate.run { events += "reset" } }
        yield()
        assertEquals(listOf("texture"), events)
        finish.complete(Unit)
        tx.join(); save.await(); reset.await()
        assertEquals(listOf("texture", "geometry"), events.take(2))
        assertEquals(setOf("save", "reset"), events.drop(2).toSet())
    }

    @Test fun cancellationReleasesGate() = runBlocking {
        val gate = RasterTransactionGate()
        val entered = CompletableDeferred<Unit>()
        val worker = launch { gate.run { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        entered.await(); worker.cancelAndJoin()
        assertEquals(7, gate.run { 7 })
    }
    @Test fun lifecycleSaveSuspendsWithoutBlockingOtherMainCoroutines() = runBlocking {
        val gate = RasterTransactionGate()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val extraction = launch { gate.run { started.complete(Unit); finish.await() } }
        started.await()
        var saved = false
        val stopSave = launch { gate.run { saved = true } }
        yield()
        var resumeProcessed = false
        val resume = launch { resumeProcessed = true }
        resume.join()
        assertTrue(resumeProcessed)
        assertFalse(saved)
        finish.complete(Unit)
        extraction.join(); stopSave.join()
        assertTrue(saved)
    }

}
