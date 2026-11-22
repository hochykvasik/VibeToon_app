package com.vibetoon.domain.project

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Один общий результат фоновой подготовки на ключ; после завершения ошибка допускает повтор. */
class PreparationRegistry<K : Any>(private val executor: Executor) {
    private val pending = ConcurrentHashMap<K, CompletableDeferred<Result<Unit>>>()

    fun request(key: K, isReady: () -> Boolean, prepare: () -> Unit): CompletableDeferred<Result<Unit>> {
        if (isReady()) return CompletableDeferred(Result.success(Unit))
        synchronized(pending) {
            if (isReady()) return CompletableDeferred(Result.success(Unit))
            pending[key]?.let { return it }
            val result = CompletableDeferred<Result<Unit>>()
            pending[key] = result
            try {
                executor.execute {
                    val outcome = runCatching { prepare() }
                    synchronized(pending) { pending.remove(key, result) }
                    result.complete(outcome)
                }
            } catch (t: Throwable) {
                pending.remove(key, result)
                result.complete(Result.failure(t))
            }
            return result
        }
    }
}
