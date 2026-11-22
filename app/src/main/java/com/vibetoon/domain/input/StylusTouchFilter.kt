package com.vibetoon.domain.input

/** Состояние касания пера, не наведения. Экземпляр ограничен жизнью редакторского холста. */
class StylusTouchFilter {
    private var penDown = false
    private var releasedAt: Long? = null

    fun observePen(pressed: Boolean, timeMillis: Long) {
        if (pressed) {
            penDown = true
        } else if (penDown) {
            penDown = false
            releasedAt = timeMillis
        }
    }

    /** Только Touch фильтруется; неизвестный указатель и перо никогда не считаются ладонью. */
    fun blockTouch(enabled: Boolean, isTouch: Boolean, nowMillis: Long): Boolean {
        if (!enabled || !isTouch) return false
        if (penDown) return true
        val last = releasedAt ?: return false
        return nowMillis >= last && nowMillis - last < GUARD_MS
    }

    companion object { const val GUARD_MS = 1200L }
}
