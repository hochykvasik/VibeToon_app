package com.vibetoon.ui

import android.view.MotionEvent
import com.vibetoon.domain.input.StylusButtons
import com.vibetoon.domain.input.StylusSample

/**
 * Stylus sprint: наблюдатель MotionEvent (через Modifier.motionEventSpy) для данных пера, которых нет в Compose:
 * реальный нажим исторических точек (getHistoricalPressure), наклон AXIS_TILT и ориентация, тип инструмента
 * TOOL_TYPE_STYLUS / TOOL_TYPE_ERASER. Ничего не потребляет; используется только в основном потоке.
 *
 * Спринт 44 (аудит пера):
 *  - наклон без датчика - NaN, вертикальное перо с датчиком - 0 (раньше оба были 0 и вертикальное перо показывалось
 *    как «нет данных»); наличие оси проверяется по InputDevice.getMotionRange один раз на устройство;
 *  - кнопка на корпусе пера (getButtonState: BUTTON_STYLUS_PRIMARY/SECONDARY) - [barrelPressed];
 *  - FLAG_CANCELED (Android 13+: система признала касание случайным, например ладонью) - [canceledAt];
 *  - состояние пера сбрасывается, когда перо уходит (HOVER_EXIT) или новый жест начат без пера: старый ластик,
 *    кнопка или наклон не переносятся на следующее касание.
 */
class StylusTracker {
    /** Время события (uptime, мс), к которому относятся [historicalPressure]. */
    var eventTime = -1L; private set
    var historicalPressure: FloatArray? = null; private set
    var tiltRadians = Float.NaN; private set
    var orientationRadians = Float.NaN; private set
    var eraserTip = false; private set
    /** Кнопка на корпусе пера зажата (в последнем событии пера). */
    var barrelPressed = false; private set
    /** Время события, в котором система отменила касание (FLAG_CANCELED / ACTION_CANCEL), или -1. */
    var canceledAt = -1L; private set

    private val axes = HashMap<Int, BooleanArray>()   // deviceId -> [есть AXIS_TILT, есть AXIS_ORIENTATION]

    private fun axesOf(ev: MotionEvent): BooleanArray? {
        val id = ev.deviceId
        axes[id]?.let { return it }
        val dev = runCatching { ev.device }.getOrNull() ?: return null
        val src = ev.source
        val r = booleanArrayOf(
            (dev.getMotionRange(MotionEvent.AXIS_TILT, src) ?: dev.getMotionRange(MotionEvent.AXIS_TILT)) != null,
            (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, src) ?: dev.getMotionRange(MotionEvent.AXIS_ORIENTATION)) != null
        )
        axes[id] = r
        return r
    }

    fun reset() {
        eraserTip = false; barrelPressed = false
        tiltRadians = Float.NaN; orientationRadians = Float.NaN
    }

    fun onEvent(ev: MotionEvent) {
        val action = ev.actionMasked
        // Android 13+: FLAG_CANCELED (0x20) на ACTION_POINTER_UP / ACTION_CANCEL - касание признано случайным
        if (action == MotionEvent.ACTION_CANCEL || ((action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) && (ev.flags and FLAG_CANCELED) != 0))
            canceledAt = ev.eventTime
        else if (action == MotionEvent.ACTION_DOWN) canceledAt = -1L
        var idx = -1
        for (i in 0 until ev.pointerCount) {
            val t = ev.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) { idx = i; break }
        }
        if (idx < 0) {
            if (action == MotionEvent.ACTION_DOWN) reset()   // новый жест пальцем или мышью: прошлое состояние пера не действует
            return
        }
        if (action == MotionEvent.ACTION_HOVER_EXIT) { reset(); return }
        eraserTip = ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
        barrelPressed = StylusButtons.pressed(ev.buttonState, true)
        val ax = axesOf(ev)
        tiltRadians = StylusSample.tiltAxis(ev.getAxisValue(MotionEvent.AXIS_TILT, idx), ax?.get(0))
        orientationRadians = if (ax == null || ax[1]) ev.getOrientation(idx) else Float.NaN
        val n = ev.historySize
        historicalPressure = if (n > 0) FloatArray(n) { h -> ev.getHistoricalPressure(idx, h) } else FloatArray(0)
        eventTime = ev.eventTime
    }

    /** Реальные исторические нажимы для события Compose с временем [uptime] или null. */
    fun historyFor(uptime: Long): FloatArray? = if (uptime == eventTime) historicalPressure else null

    /** Касание, закончившееся в событии [uptime], отменено системой (FLAG_CANCELED). */
    fun canceled(uptime: Long): Boolean = canceledAt >= 0 && canceledAt == uptime

    private companion object {
        const val FLAG_CANCELED = 0x20   // MotionEvent.FLAG_CANCELED (API 33)
    }
}
