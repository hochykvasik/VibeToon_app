package com.vibetoon.render

/**
 * Метрики последнего кадра GL-потока (пишет VtRenderer, читает ViewModel для панели метрик).
 * Счётчики собираются только при включённой панели - в обычной работе накладных расходов нет.
 */
object RenderMetrics {
    @Volatile var enabled = false
    @Volatile var drawMs = 0f
    @Volatile var drawn = 0
    @Volatile var culled = 0
    @Volatile var scissoredFx = 0
    @Volatile var fxPasses = 0
    @Volatile var fxCached = 0
    /** Пакет 27: групп, наложенных из FBO-кэша поддерева. */
    @Volatile var groupCached = 0
}
