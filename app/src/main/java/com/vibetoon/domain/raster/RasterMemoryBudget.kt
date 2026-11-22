package com.vibetoon.domain.raster

/** Консервативный бюджет рабочих массивов: не является гарантией успешной аллокации. */
object RasterMemoryBudget {
    const val HEAP_RESERVE = 16L * 1024 * 1024
    const val SYSTEM_RESERVE = 32L * 1024 * 1024

    fun available(heapMax: Long, heapTotal: Long, heapFree: Long, systemAvailable: Long, systemThreshold: Long): Long {
        if (heapMax <= 0 || heapTotal < 0 || heapFree < 0 || heapFree > heapTotal || systemAvailable < 0 || systemThreshold < 0) return 0
        val heapUsed = heapTotal - heapFree
        val heapHeadroom = (heapMax - heapUsed).coerceAtLeast(0)
        val heapBudget = (heapHeadroom - HEAP_RESERVE).coerceAtLeast(0)
        val systemBudget = (systemAvailable - maxOf(systemThreshold, SYSTEM_RESERVE)).coerceAtLeast(0)
        return minOf(heapBudget, systemBudget, ExpandedSelectionMove.DEFAULT_CPU_BUDGET)
    }
}
