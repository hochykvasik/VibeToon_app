package com.vibetoon

import com.vibetoon.domain.raster.QueuedGlRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class QueuedGlRequestTest {
    @Test fun timeoutBeforeStartPreventsLateGlExecution() {
        val request = QueuedGlRequest()
        assertTrue(request.cancelIfQueued())
        assertFalse(request.tryStart())
        assertFalse(request.cancelIfQueued())
        assertFalse(request.finish())
    }
    @Test fun startedGlCannotBeDeclaredCancelled() {
        val request = QueuedGlRequest()
        assertTrue(request.tryStart())
        assertFalse(request.cancelIfQueued())
        assertTrue(request.finish())
        assertFalse(request.finish())
    }
    @Test fun concurrentStartAndCancelHaveOneWinner() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val request = QueuedGlRequest()
                val start = CountDownLatch(1)
                val a = pool.submit<Boolean> { start.await(); request.tryStart() }
                val b = pool.submit<Boolean> { start.await(); request.cancelIfQueued() }
                start.countDown()
                assertNotEquals(a.get(), b.get())
            }
        } finally { pool.shutdownNow() }
    }
}
