package com.vibetoon.domain.paint

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Пресеты фигур: контуры в единичном квадрате [0,1]x[0,1] (y вниз). Один пресет - один или несколько полигонов. */
enum class ShapeKind(val title: String) {
    CIRCLE("Круг"), SQUARE("Квадрат"), TRIANGLE("Треугольник"), STAR("Звезда"),
    ARROW("Стрелка"), HEART("Сердце"), BUBBLE("Диалог")
}

object ShapeMath {
    /** Полигоны фигуры: список контуров, каждый - плоский массив x0,y0,x1,y1,... */
    fun polygons(kind: ShapeKind, segments: Int = 64): List<FloatArray> = when (kind) {
        ShapeKind.CIRCLE -> listOf(ellipse(0.5f, 0.5f, 0.5f, 0.5f, segments))
        ShapeKind.SQUARE -> listOf(floatArrayOf(0.05f, 0.05f, 0.95f, 0.05f, 0.95f, 0.95f, 0.05f, 0.95f))
        ShapeKind.TRIANGLE -> listOf(floatArrayOf(0.5f, 0.05f, 0.97f, 0.92f, 0.03f, 0.92f))
        ShapeKind.STAR -> listOf(FloatArray(20).also { a ->
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.5f else 0.2f
                val ang = -PI / 2 + i * PI / 5
                a[i * 2] = 0.5f + (cos(ang) * r).toFloat(); a[i * 2 + 1] = 0.53f + (sin(ang) * r).toFloat()
            }
        })
        ShapeKind.ARROW -> listOf(floatArrayOf(0.03f, 0.35f, 0.55f, 0.35f, 0.55f, 0.1f, 0.97f, 0.5f, 0.55f, 0.9f, 0.55f, 0.65f, 0.03f, 0.65f))
        ShapeKind.HEART -> listOf(FloatArray(segments * 2).also { a ->
            for (i in 0 until segments) {
                val t = 2 * PI * i / segments
                val x = 16 * sin(t).pow(3)
                val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
                a[i * 2] = 0.5f + (x / 34.0).toFloat(); a[i * 2 + 1] = 0.45f - (y / 34.0).toFloat()
            }
        })
        ShapeKind.BUBBLE -> listOf(
            ellipse(0.5f, 0.42f, 0.48f, 0.34f, segments),
            floatArrayOf(0.22f, 0.62f, 0.12f, 0.95f, 0.5f, 0.72f)   // хвостик
        )
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int) = FloatArray(n * 2).also { a ->
        for (i in 0 until n) {
            val t = 2 * PI * i / n
            a[i * 2] = cx + (cos(t) * rx).toFloat(); a[i * 2 + 1] = cy + (sin(t) * ry).toFloat()
        }
    }

    /** Границы всех контуров: minX, minY, maxX, maxY. */
    fun bounds(polys: List<FloatArray>): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in polys) for (i in p.indices step 2) {
            b[0] = minOf(b[0], p[i]); b[2] = maxOf(b[2], p[i]); b[1] = minOf(b[1], p[i + 1]); b[3] = maxOf(b[3], p[i + 1])
        }
        return b
    }
}
