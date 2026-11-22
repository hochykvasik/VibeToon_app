package com.vibetoon.domain.render

/** Очерёдность открытия одинакова для рекомендации, урока и обычного проекта. */
object ProjectRenderStartup {
    suspend fun <T> start(acquireReady: suspend () -> T, clear: suspend (T) -> Unit,
                          restore: suspend (T) -> Unit, publish: suspend (T) -> Unit): T {
        val view = acquireReady()
        clear(view)
        restore(view)
        publish(view)
        return view
    }
}
