package com.vibetoon.domain.vector

import kotlin.math.abs
import kotlin.math.hypot

/** Вид векторного примитива: линия от руки (сглаженный путь), прямая, прямоугольник, эллипс или пресет фигуры (звезда, сердце...). */
/** POLY - ломаная пера (разомкнутый контур), POLYGON - замкнутый контур пера. */
enum class VecKind { PATH, LINE, RECT, ELLIPSE, PRESET, POLY, POLYGON }

/**
 * Векторный примитив слоя. Точки [pts] - плоский список x0,y0,x1,y1,... в пикселях холста проекта (слой занимает весь холст):
 * PATH - вся линия, LINE/RECT/ELLIPSE - две точки (начало и конец жеста), PRESET - две точки рамки + имя пресета ShapeKind.
 * [stroke] - цвет обводки ARGB, [fill] - заливка ARGB (0 - без заливки), [width] - толщина обводки в px холста.
 */
data class VectorShape(
    val kind: VecKind,
    val pts: List<Float>,
    val stroke: Long = 0xFF000000L,
    val fill: Long = 0L,
    val width: Float = 8f,
    val preset: String = ""
)

/** Чистая математика векторного слоя (без Android): отбор точек жеста, сборка примитива, границы. */
object VectorMath {
    /** Убирает точки ближе [minDist] к предыдущей оставленной (шум пальца); первая и последняя точки остаются. */
    fun simplify(pts: List<Float>, minDist: Float): List<Float> {
        if (pts.size <= 4) return pts
        val out = ArrayList<Float>()
        out += pts[0]; out += pts[1]
        var i = 2
        while (i < pts.size - 2) {
            if (hypot(pts[i] - out[out.size - 2], pts[i + 1] - out[out.size - 1]) >= minDist) { out += pts[i]; out += pts[i + 1] }
            i += 2
        }
        out += pts[pts.size - 2]; out += pts[pts.size - 1]
        return out
    }

    /** Размах точек: minX, minY, maxX, maxY. */
    fun bounds(pts: List<Float>): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i + 1 < pts.size) {
            b[0] = minOf(b[0], pts[i]); b[2] = maxOf(b[2], pts[i]); b[1] = minOf(b[1], pts[i + 1]); b[3] = maxOf(b[3], pts[i + 1]); i += 2
        }
        return b
    }

    /**
     * Примитив из жеста: LINE/RECT/ELLIPSE берут первую и последнюю точки, PATH - все (после [simplify]).
     * null - жест слишком мал (тап без движения не создаёт пустой примитив).
     */
    fun fromGesture(kind: VecKind, pts: List<Float>, stroke: Long, fill: Long, width: Float): VectorShape? {
        if (pts.size < 4) return null
        val sx = pts[0]; val sy = pts[1]; val ex = pts[pts.size - 2]; val ey = pts[pts.size - 1]
        val twoPoint = listOf(sx, sy, ex, ey)
        return when (kind) {
            VecKind.PATH -> {
                val s = simplify(pts, MIN_POINT_DIST)
                if (s.size < 4 || (bounds(s).let { abs(it[2] - it[0]) + abs(it[3] - it[1]) } < MIN_EXTENT)) null
                else VectorShape(VecKind.PATH, s, stroke, fill, width)
            }
            VecKind.LINE -> if (hypot(ex - sx, ey - sy) < MIN_EXTENT) null else VectorShape(VecKind.LINE, twoPoint, stroke, 0L, width)
            VecKind.RECT, VecKind.ELLIPSE ->
                if (abs(ex - sx) < MIN_EXTENT || abs(ey - sy) < MIN_EXTENT) null else VectorShape(kind, twoPoint, stroke, fill, width)
            VecKind.PRESET, VecKind.POLY, VecKind.POLYGON -> null
        }
    }

    /** Фигура-пресет по центру холста: квадратная рамка со стороной [side]. */
    fun preset(name: String, cx: Float, cy: Float, side: Float, stroke: Long, fill: Long, width: Float) =
        VectorShape(VecKind.PRESET, listOf(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f), stroke, fill, width, name)

    fun withShape(list: List<VectorShape>, s: VectorShape): List<VectorShape> = list + s
    fun withoutLast(list: List<VectorShape>): List<VectorShape> = if (list.isEmpty()) list else list.dropLast(1)

    const val MIN_POINT_DIST = 2f
    const val MIN_EXTENT = 3f
}
