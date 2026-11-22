package com.vibetoon.data

import android.app.ActivityManager
import android.content.Context
import com.vibetoon.domain.raster.RasterMemoryBudget

/** Запас памяти устройства в момент вызова; при ошибке безопасно запрещаем аллокацию. */
object RasterMemory {
    fun available(context: Context): Long = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        if (info.lowMemory) return 0L
        val runtime = Runtime.getRuntime()
        RasterMemoryBudget.available(runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory(), info.availMem, info.threshold)
    }.getOrDefault(0L)
}
