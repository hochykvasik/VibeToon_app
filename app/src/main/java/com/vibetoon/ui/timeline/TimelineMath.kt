package com.vibetoon.ui.timeline

object TimelineMath {
    /**
     * Куда прокрутить таймлайн, чтобы playhead в позиции x (px содержимого) был виден:
     * null, если он уже виден с отступом margin от краёв окна шириной viewport.
     */
    fun autoScrollTarget(x: Float, scroll: Int, viewport: Int, margin: Int): Int? {
        if (viewport <= 0) return null
        return when {
            x < scroll + margin -> (x - margin).toInt().coerceAtLeast(0)
            x > scroll + viewport - margin -> (x - viewport + margin).toInt().coerceAtLeast(0)
            else -> null
        }
    }
}
