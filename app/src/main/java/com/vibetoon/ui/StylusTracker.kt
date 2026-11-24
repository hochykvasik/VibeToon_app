package com.vibetoon.ui

import android.view.MotionEvent

/**
 * Stylus sprint: наблюдатель MotionEvent (через Modifier.motionEventSpy) для данных пера, которых нет в Compose:
 * реальный нажим исторических точек (getHistoricalPressure), наклон AXIS_TILT и ориентация, тип инструмента
 * TOOL_TYPE_STYLUS / TOOL_TYPE_ERASER. Ничего не потребляет; используется только в основном потоке.
 */
class StylusTracker {
    /** Время события (uptime, мс), к которому относятся [historicalPressure]. */
    var eventTime = -1L; private set
    var historicalPressure: FloatArray? = null; private set
    var tiltRadians = Float.NaN; private set
    var orientationRadians = Float.NaN; private set
    var eraserTip = false; private set

    fun onEvent(ev: MotionEvent) {
        var idx = -1
        for (i in 0 until ev.pointerCount) {
            val t = ev.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) { idx = i; break }
        }
        if (idx < 0) return
        eraserTip = ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
        tiltRadians = ev.getAxisValue(MotionEvent.AXIS_TILT, idx)
        orientationRadians = ev.getOrientation(idx)
        val n = ev.historySize
        historicalPressure = if (n > 0) FloatArray(n) { h -> ev.getHistoricalPressure(idx, h) } else FloatArray(0)
        eventTime = ev.eventTime
    }

    /** Реальные исторические нажимы для события Compose с временем [uptime] или null. */
    fun historyFor(uptime: Long): FloatArray? = if (uptime == eventTime) historicalPressure else null
}
