package com.vibetoon.domain.input

/** В одном жесте пикер допускается только для неподвижного одиночного указателя. */
object LongPressPicker {
    fun cancelled(pointerStillDown: Boolean, pressedCount: Int, consumed: Boolean, distance: Float, slop: Float): Boolean =
        !pointerStillDown || pressedCount != 1 || consumed || !distance.isFinite() || distance > slop
}
