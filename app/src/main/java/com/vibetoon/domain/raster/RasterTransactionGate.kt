package com.vibetoon.domain.raster

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Сериализует сохранение и смену состояния с растровой транзакцией. Не управляет жизненным циклом GL. */
class RasterTransactionGate {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
