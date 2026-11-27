package com.vibetoon.domain.render

/**
 * Спринт 41: когда применять новый размер буфера GL-поверхности. Первый размер (буфера ещё нет) и смена ориентации
 * применяются сразу; изменения во время анимации панелей (таймлайн, нижнее меню) - один раз, после паузы [SETTLE_MS].
 */
object ResizeSettle {
    const val SETTLE_MS = 140L

    fun applyNow(bufferW: Int, bufferH: Int, w: Int, h: Int): Boolean {
        if (bufferW <= 0 || bufferH <= 0) return true
        if (w == bufferW && h == bufferH) return true
        val rotated = (bufferW > bufferH) != (w > h) && kotlin.math.abs(w - bufferW) > bufferW / 4
        return rotated
    }
}
