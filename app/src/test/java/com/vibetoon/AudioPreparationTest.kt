package com.vibetoon

import com.vibetoon.domain.project.AudioReadiness
import com.vibetoon.domain.project.PreparationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class AudioPreparationTest {
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNext() = tasks.removeFirst().run()
    }

    @Test fun loadingWaitsForPublishedPcmAndConcurrentRequestsShareWork() = runBlocking {
        val executor = ManualExecutor()
        val registry = PreparationRegistry<String>(executor)
        var pcmReady = false
        var state: AudioReadiness = AudioReadiness.Preparing("music.mp3")
        val first = registry.request("music", { pcmReady }) { pcmReady = true }
        val second = registry.request("music", { pcmReady }) { error("duplicate decode") }
        assertSame(first, second)
        assertFalse(first.isCompleted)
        assertTrue(state is AudioReadiness.Preparing)
        executor.runNext()
        first.await().getOrThrow()
        state = AudioReadiness.Ready("music.mp3")
        assertTrue(pcmReady)
        assertTrue(state is AudioReadiness.Ready)
        assertTrue(registry.request("music", { pcmReady }) { error("already ready") }.await().isSuccess)
        assertTrue(executor.tasks.isEmpty())
    }

    @Test fun failureCompletesAndAllowsRetry() = runBlocking {
        val executor = ManualExecutor()
        val registry = PreparationRegistry<String>(executor)
        val result = registry.request("file", { false }) { error("bad audio") }
        executor.runNext()
        assertTrue(result.await().isFailure)
        var ready = false
        val retry = registry.request("file", { ready }) { ready = true }
        executor.runNext()
        assertTrue(retry.await().isSuccess)
        assertTrue(ready)
    }
}
