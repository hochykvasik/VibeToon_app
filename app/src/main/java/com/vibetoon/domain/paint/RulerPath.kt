package com.vibetoon.domain.paint

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Gesture geometry in screen pixels: A→B is a line, or center→radius for the compass. */
object RulerPath {
    enum class Mode { OFF, LINE, COMPASS }
    data class Point(val x: Float, val y: Float)

    fun path(mode: Mode, a: Point, b: Point): List<Point>? {
        if (mode == Mode.OFF || listOf(a.x, a.y, b.x, b.y).any { !it.isFinite() }) return null
        val r = hypot(b.x - a.x, b.y - a.y)
        if (!r.isFinite() || r < 2f || r > 10000f) return null
        if (mode == Mode.LINE) return listOf(a, b)
        val count = (2.0 * PI * r / 3.0).toInt().coerceIn(32, 1024)
        val angle = kotlin.math.atan2(b.y - a.y, b.x - a.x)
        return (0..count).map { i ->
            val t = angle + (2.0 * PI * i / count).toFloat()
            Point(a.x + r * cos(t), a.y + r * sin(t))
        }
    }
}
