package com.vibetoon.domain.vector

import kotlin.math.abs
import kotlin.math.hypot

/** Вид векторного примитива: линия от руки (сглаженный путь), прямая, прямоугольник, эллипс или пресет фигуры (звезда, сердце...). */
/** POLY - ломаная пера (разомкнутый контур), POLYGON - замкнутый контур пера, BOOL - булева операция двух фигур (пакет 18). */
enum class VecKind { PATH, LINE, RECT, ELLIPSE, PRESET, POLY, POLYGON, BOOL }

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
    val preset: String = "",
    /** BOOL: операция ([VectorBool.OPS]) и две фигуры (нижняя, верхняя); их точки - части [pts] по порядку. */
    val op: String = "",
    val parts: List<VectorShape> = emptyList()
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
            VecKind.PRESET, VecKind.POLY, VecKind.POLYGON, VecKind.BOOL -> null
        }
    }

    /** Фигура-пресет по центру холста: квадратная рамка со стороной [side]. */
    fun preset(name: String, cx: Float, cy: Float, side: Float, stroke: Long, fill: Long, width: Float) =
        VectorShape(VecKind.PRESET, listOf(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f), stroke, fill, width, name)

    /** Index selections are valid only while the entire layer is unchanged. */
    fun selectionStillValid(snapshot: List<VectorShape>?, current: List<VectorShape>, shapeIndex: Int, pointIndex: Int): Boolean =
        snapshot != null && snapshot == current && current.getOrNull(shapeIndex)?.let { pointIndex in 0 until it.pts.size / 2 } == true

    /** A preview is a new list; original shapes remain intact until the caller commits. */
    fun moveNode(shapes: List<VectorShape>, shapeIndex: Int, pointIndex: Int, x: Float, y: Float): List<VectorShape>? {
        if (!x.isFinite() || !y.isFinite()) return null
        val shape = shapes.getOrNull(shapeIndex) ?: return null
        if (pointIndex !in 0 until shape.pts.size / 2) return null
        val points = shape.pts.toMutableList()
        points[pointIndex * 2] = x; points[pointIndex * 2 + 1] = y
        return shapes.toMutableList().also { it[shapeIndex] = shape.copy(pts = points.toList()) }
    }

    /** Style edits leave topology and other primitives untouched. */
    fun restyle(shape: VectorShape, stroke: Long, width: Float): VectorShape? {
        if (!width.isFinite() || width <= 0f || width > 1500f || stroke !in 0L..0xffffffffL) return null
        return shape.copy(stroke = stroke, width = width)
    }

    fun withShape(list: List<VectorShape>, s: VectorShape): List<VectorShape> = list + s
    fun withoutLast(list: List<VectorShape>): List<VectorShape> = if (list.isEmpty()) list else list.dropLast(1)

    const val MIN_POINT_DIST = 2f
    const val MIN_EXTENT = 3f
}

/**
 * Пакет 18: булевы операции векторных фигур (без разрушения): выбранная фигура и фигура под ней в списке слоя
 * становятся одной фигурой BOOL - объединение, вычитание верхней из нижней, пересечение или исключение (XOR).
 * Узлы фигуры BOOL - узлы её частей подряд, поэтому правка узлов работает как раньше; «Разобрать» возвращает части
 * с их текущими узлами. Стиль (обводка, заливка, толщина) берётся у нижней фигуры. Линии и ломаные - не площади,
 * в операциях не участвуют.
 */
object VectorBool {
    const val UNION = "union"
    const val SUBTRACT = "subtract"
    const val INTERSECT = "intersect"
    const val EXCLUDE = "exclude"
    val OPS = listOf(UNION, SUBTRACT, INTERSECT, EXCLUDE)

    enum class Error { NO_SHAPE, NO_SHAPE_BELOW, NOT_AREA, BAD_OP, NOT_COMPOUND }

    /** Результат: новый список и индекс получившейся фигуры, либо ошибка. */
    data class Result(val shapes: List<VectorShape>, val index: Int, val error: Error? = null)

    /** Замкнутая площадь: участвует в операциях. */
    fun isArea(s: VectorShape): Boolean = when (s.kind) {
        VecKind.LINE, VecKind.POLY -> false
        VecKind.BOOL -> s.op in OPS && s.parts.size == 2
        else -> s.pts.size >= 4
    }

    fun combine(shapes: List<VectorShape>, index: Int, op: String): Result {
        if (op !in OPS) return Result(shapes, index, Error.BAD_OP)
        val upper = shapes.getOrNull(index) ?: return Result(shapes, index, Error.NO_SHAPE)
        val lower = shapes.getOrNull(index - 1) ?: return Result(shapes, index, Error.NO_SHAPE_BELOW)
        if (!isArea(upper) || !isArea(lower)) return Result(shapes, index, Error.NOT_AREA)
        val fill = if (lower.fill != 0L) lower.fill else upper.fill
        val c = VectorShape(VecKind.BOOL, lower.pts + upper.pts, lower.stroke, fill, lower.width, "", op, listOf(lower, upper))
        val out = shapes.toMutableList()
        out[index - 1] = c
        out.removeAt(index)
        return Result(out, index - 1)
    }

    /** Части фигуры BOOL с их текущими узлами (срезы [VectorShape.pts]); при несовпадении размеров - как сохранены. */
    fun parts(c: VectorShape): List<VectorShape> {
        if (c.kind != VecKind.BOOL) return emptyList()
        if (c.parts.sumOf { it.pts.size } != c.pts.size) return c.parts
        var at = 0
        return c.parts.map { part -> part.copy(pts = c.pts.subList(at, at + part.pts.size).toList()).also { at += part.pts.size } }
    }

    /** «Разобрать»: фигура BOOL заменяется своими частями (нижняя, верхняя). */
    fun release(shapes: List<VectorShape>, index: Int): Result {
        val c = shapes.getOrNull(index) ?: return Result(shapes, index, Error.NO_SHAPE)
        if (c.kind != VecKind.BOOL) return Result(shapes, index, Error.NOT_COMPOUND)
        val out = shapes.toMutableList()
        out.removeAt(index)
        out.addAll(index, parts(c))
        return Result(out, index + 1)
    }
}
