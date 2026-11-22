package com.vibetoon.domain.raster

import java.util.concurrent.atomic.AtomicInteger

/** Отмена возможна только до начала GL-вызова: выполняющуюся замену считать отменённой небезопасно. */
class QueuedGlRequest {
    private val state = AtomicInteger(QUEUED)
    fun tryStart(): Boolean = state.compareAndSet(QUEUED, RUNNING)
    fun cancelIfQueued(): Boolean = state.compareAndSet(QUEUED, CANCELLED)
    fun finish(): Boolean = state.compareAndSet(RUNNING, FINISHED)

    private companion object {
        const val QUEUED = 0
        const val RUNNING = 1
        const val CANCELLED = 2
        const val FINISHED = 3
    }
}
