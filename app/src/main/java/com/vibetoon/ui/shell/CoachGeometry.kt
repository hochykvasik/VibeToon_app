package com.vibetoon.ui.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** Координаты целей в окне редактора -> координаты оверлея с ограничением видимой областью. */
object CoachGeometry {
    fun visible(target: Rect, origin: Offset, viewport: Size): Rect? {
        val left = maxOf(0f, target.left - origin.x)
        val top = maxOf(0f, target.top - origin.y)
        val right = minOf(viewport.width, target.right - origin.x)
        val bottom = minOf(viewport.height, target.bottom - origin.y)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }
}
