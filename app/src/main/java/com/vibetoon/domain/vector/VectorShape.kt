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
    val parts: List<VectorShape> = emptyList(),
    /**
     * Пакет 19: ручки Безье контуров пера (POLY/POLYGON) - по 4 числа на узел: вход dx,dy и выход dx,dy относительно узла
     * (ручки двигаются вместе с узлом). Пусто - контур из прямых, как раньше.
     */
    val handles: List<Float> = emptyList()
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

/**
 * Пакет 19: узлы Безье контуров пера. «Сгладить узел» ставит ручки по направлению соседей (треть расстояния до каждого),
 * «Угол» убирает ручки узла, перетаскивание ручки зеркалит направление второй ручки (её длина сохраняется).
 * Ручки хранятся относительно узла, поэтому перемещение узла, части булевых операций и удаление узла их не ломают.
 */
object VectorBezier {
    const val IN = 0
    const val OUT = 1

    fun supports(s: VectorShape): Boolean = s.kind == VecKind.POLY || s.kind == VecKind.POLYGON

    fun hasHandles(s: VectorShape): Boolean = supports(s) && s.handles.size == s.pts.size * 2 && s.handles.any { it != 0f }

    private fun nodes(s: VectorShape) = s.pts.size / 2

    private fun full(s: VectorShape): MutableList<Float> =
        if (s.handles.size == s.pts.size * 2) s.handles.toMutableList() else MutableList(s.pts.size * 2) { 0f }

    /** Все ручки нулевые - поле пустое (контур снова из прямых, файл как раньше). */
    private fun pack(s: VectorShape, h: List<Float>): VectorShape = s.copy(handles = if (h.all { it == 0f }) emptyList() else h.toList())

    /** Соседи узла [k] по контуру (у замкнутого - по кругу); null - соседа нет. */
    private fun neighbor(s: VectorShape, k: Int, d: Int): Int? {
        val n = nodes(s)
        val j = k + d
        return when {
            j in 0 until n -> j
            s.kind == VecKind.POLYGON && n >= 3 -> (j + n) % n
            else -> null
        }
    }

    /** Абсолютные координаты ручки [which] узла [k] (x, y). */
    fun handleAt(s: VectorShape, k: Int, which: Int): Pair<Float, Float>? {
        if (!supports(s) || k !in 0 until nodes(s)) return null
        val h = if (s.handles.size == s.pts.size * 2) s.handles else return s.pts[k * 2] to s.pts[k * 2 + 1]
        return (s.pts[k * 2] + h[k * 4 + which * 2]) to (s.pts[k * 2 + 1] + h[k * 4 + which * 2 + 1])
    }

    fun smoothNode(s: VectorShape, k: Int): VectorShape? {
        if (!supports(s) || k !in 0 until nodes(s)) return null
        val x = s.pts[k * 2]; val y = s.pts[k * 2 + 1]
        val prev = neighbor(s, k, -1); val next = neighbor(s, k, 1)
        if (prev == null && next == null) return null
        val px = prev?.let { s.pts[it * 2] } ?: x; val py = prev?.let { s.pts[it * 2 + 1] } ?: y
        val nx = next?.let { s.pts[it * 2] } ?: x; val ny = next?.let { s.pts[it * 2 + 1] } ?: y
        var tx = nx - px; var ty = ny - py
        val tl = kotlin.math.hypot(tx, ty)
        if (tl < 1e-3f) return null
        tx /= tl; ty /= tl
        val dIn = kotlin.math.hypot(x - px, y - py) / 3f
        val dOut = kotlin.math.hypot(nx - x, ny - y) / 3f
        val h = full(s)
        h[k * 4] = -tx * dIn; h[k * 4 + 1] = -ty * dIn; h[k * 4 + 2] = tx * dOut; h[k * 4 + 3] = ty * dOut
        return pack(s, h)
    }

    fun smoothAll(s: VectorShape): VectorShape? {
        if (!supports(s)) return null
        var r = s
        for (k in 0 until nodes(s)) smoothNode(r, k)?.let { r = it }
        return if (r == s) null else r
    }

    fun cornerNode(s: VectorShape, k: Int): VectorShape? {
        if (!supports(s) || k !in 0 until nodes(s)) return null
        val h = full(s)
        for (i in 0 until 4) h[k * 4 + i] = 0f
        return pack(s, h)
    }

    fun nodeHasHandles(s: VectorShape, k: Int): Boolean =
        hasHandles(s) && k in 0 until nodes(s) && (0 until 4).any { s.handles[k * 4 + it] != 0f }

    /** Ручка [which] узла [k] в точку x,y; вторая ручка узла поворачивается напротив (длина сохраняется). Новый список. */
    fun moveHandle(shapes: List<VectorShape>, si: Int, k: Int, which: Int, x: Float, y: Float): List<VectorShape>? {
        if (!x.isFinite() || !y.isFinite() || which !in 0..1) return null
        val s = shapes.getOrNull(si) ?: return null
        if (!supports(s) || k !in 0 until nodes(s)) return null
        val h = full(s)
        val dx = x - s.pts[k * 2]; val dy = y - s.pts[k * 2 + 1]
        h[k * 4 + which * 2] = dx; h[k * 4 + which * 2 + 1] = dy
        val o = (1 - which) * 2
        val ol = kotlin.math.hypot(h[k * 4 + o], h[k * 4 + o + 1])
        val l = kotlin.math.hypot(dx, dy)
        if (ol > 0f && l > 1e-3f) { h[k * 4 + o] = -dx / l * ol; h[k * 4 + o + 1] = -dy / l * ol }
        return shapes.toMutableList().also { it[si] = pack(s, h) }
    }

    /** Удаление узла вместе с его ручками. */
    fun withoutNode(s: VectorShape, k: Int): VectorShape {
        val pts = s.pts.toMutableList(); pts.removeAt(k * 2 + 1); pts.removeAt(k * 2)
        val h = if (s.handles.size == s.pts.size * 2) s.handles.toMutableList().also { l -> repeat(4) { l.removeAt(k * 4) } } else emptyList()
        return pack(s.copy(pts = pts), h)
    }

    /**
     * Пакет 20: ручки при постановке точки пером. [handles] - ручки контура в работе (по 4 на точку), у точки [k]
     * выход ставится в dx,dy (вектор протяжки пальцем), вход - зеркально. Длина меньше [minLen] - ручек нет (простой тап).
     */
    fun penPull(handles: List<Float>, k: Int, dx: Float, dy: Float, minLen: Float): List<Float> {
        if (k < 0 || handles.size < (k + 1) * 4 || !dx.isFinite() || !dy.isFinite()) return handles
        val h = handles.toMutableList()
        val on = kotlin.math.hypot(dx, dy) >= minLen
        h[k * 4] = if (on) -dx else 0f; h[k * 4 + 1] = if (on) -dy else 0f
        h[k * 4 + 2] = if (on) dx else 0f; h[k * 4 + 3] = if (on) dy else 0f
        return h
    }

    /** Контур пера из точек и ручек в работе: ручки неверной длины или все нулевые - прямые отрезки, как раньше. */
    fun fromPen(closed: Boolean, pts: List<Float>, handles: List<Float>, stroke: Long, fill: Long, width: Float): VectorShape {
        val s = VectorShape(if (closed && pts.size >= 6) VecKind.POLYGON else VecKind.POLY, pts.toList(), stroke, fill, width)
        return if (handles.size == pts.size * 2) pack(s, handles) else s
    }

    /** Точки кривой контура для предпросмотра (по [steps] на сегмент): x0,y0,x1,y1,... в координатах контура. */
    fun flatten(s: VectorShape, steps: Int): List<Float> {
        val segs = cubics(s)
        if (segs.isEmpty()) return s.pts.toList()
        val n = steps.coerceIn(1, 64)
        val out = ArrayList<Float>(segs.size * n * 2 + 2)
        out += segs[0][0]; out += segs[0][1]
        for (c in segs) {
            val straight = c[2] == c[0] && c[3] == c[1] && c[4] == c[6] && c[5] == c[7]
            val m = if (straight) 1 else n
            for (i in 1..m) {
                val t = i.toFloat() / m; val u = 1f - t
                val a = u * u * u; val b = 3f * u * u * t; val cc = 3f * u * t * t; val d = t * t * t
                out += a * c[0] + b * c[2] + cc * c[4] + d * c[6]
                out += a * c[1] + b * c[3] + cc * c[5] + d * c[7]
            }
        }
        return out
    }

    /** Сегменты кубических кривых: x0,y0, c1x,c1y, c2x,c2y, x1,y1 (замкнутый контур - с последним к первому). */
    fun cubics(s: VectorShape): List<FloatArray> {
        val n = nodes(s)
        if (n < 2) return emptyList()
        val h = if (s.handles.size == s.pts.size * 2) s.handles else List(s.pts.size * 2) { 0f }
        val segs = if (s.kind == VecKind.POLYGON) n else n - 1
        return (0 until segs).map { i ->
            val j = (i + 1) % n
            val x0 = s.pts[i * 2]; val y0 = s.pts[i * 2 + 1]; val x1 = s.pts[j * 2]; val y1 = s.pts[j * 2 + 1]
            floatArrayOf(x0, y0, x0 + h[i * 4 + 2], y0 + h[i * 4 + 3], x1 + h[j * 4], y1 + h[j * 4 + 1], x1, y1)
        }
    }
}
