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

    // ---- ластик и палец по контурам (Only Erase/Smudge Selected Path Layers решает, какие слои трогать)

    /** Расстояние от точки до ломаной [pl] (x0,y0,x1,y1,...); одна точка - расстояние до неё. */
    fun distToPolyline(x: Float, y: Float, pl: List<Float>): Float {
        if (pl.size < 2) return Float.MAX_VALUE
        if (pl.size < 4) return hypot(x - pl[0], y - pl[1])
        var best = Float.MAX_VALUE
        var i = 0
        while (i + 3 < pl.size) {
            val ax = pl[i]; val ay = pl[i + 1]; val bx = pl[i + 2]; val by = pl[i + 3]
            val vx = bx - ax; val vy = by - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 < 1e-12f) 0f else (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0f, 1f)
            best = minOf(best, hypot(x - (ax + t * vx), y - (ay + t * vy)))
            i += 2
        }
        return best
    }

    /** Вставляет точки так, чтобы соседние были не дальше [step] (контур для ластика и пальца). */
    fun densify(pts: List<Float>, step: Float, closed: Boolean = false): List<Float> {
        if (pts.size < 4 || step <= 0f) return pts
        val out = ArrayList<Float>(pts.size * 2)
        val n = pts.size / 2
        val segs = if (closed) n else n - 1
        for (i in 0 until segs) {
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val j = (i + 1) % n
            val bx = pts[j * 2]; val by = pts[j * 2 + 1]
            val k = kotlin.math.ceil(hypot(bx - ax, by - ay) / step).toInt().coerceIn(1, 4096)
            for (s in 0 until k) { val t = s.toFloat() / k; out += ax + (bx - ax) * t; out += ay + (by - ay) * t }
        }
        if (closed) { out += pts[0]; out += pts[1] } else { out += pts[pts.size - 2]; out += pts[pts.size - 1] }
        return out
    }

    /** Контур примитива ломаной (в пикселях холста) и признак замкнутости. */
    fun outline(s: VectorShape): Pair<List<Float>, Boolean> {
        val p = s.pts
        return when (s.kind) {
            VecKind.PATH, VecKind.POLY, VecKind.LINE -> p to false
            VecKind.POLYGON -> p to true
            VecKind.RECT, VecKind.PRESET -> {
                if (p.size < 4) return p to false
                listOf(p[0], p[1], p[2], p[1], p[2], p[3], p[0], p[3]) to true
            }
            VecKind.ELLIPSE -> {
                if (p.size < 4) return p to false
                val cx = (p[0] + p[2]) / 2f; val cy = (p[1] + p[3]) / 2f
                val rx = abs(p[2] - p[0]) / 2f; val ry = abs(p[3] - p[1]) / 2f
                val out = ArrayList<Float>(96)
                for (i in 0 until 48) {
                    val a = (i / 48.0 * 2.0 * Math.PI)
                    out += cx + rx * kotlin.math.cos(a).toFloat(); out += cy + ry * kotlin.math.sin(a).toFloat()
                }
                out to true
            }
        }
    }

    private fun insideClosed(x: Float, y: Float, pl: List<Float>): Boolean {
        var inside = false
        val n = pl.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = pl[i * 2]; val yi = pl[i * 2 + 1]; val xj = pl[j * 2]; val yj = pl[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Касается ли ластик (ломаная [eraser], радиус [radius]) примитива: контура или залитой внутренности. */
    fun touches(s: VectorShape, eraser: List<Float>, radius: Float): Boolean {
        val (pl, closed) = outline(s)
        val reach = radius + s.width / 2f
        val dense = densify(pl, maxOf(1f, radius / 2f), closed)
        var i = 0
        while (i + 1 < dense.size) { if (distToPolyline(dense[i], dense[i + 1], eraser) <= reach) return true; i += 2 }
        if (closed && s.fill != 0L) {
            var k = 0
            while (k + 1 < eraser.size) { if (insideClosed(eraser[k], eraser[k + 1], pl)) return true; k += 2 }
        }
        return false
    }

    /**
     * Векторный ластик. Разомкнутые контуры (путь, ломаная пера, прямая) режутся: точки под ластиком уходят,
     * остаток распадается на куски-пути. Замкнутые фигуры (прямоугольник, эллипс, многоугольник, пресет)
     * удаляются целиком, если ластик их коснулся.
     */
    fun erase(list: List<VectorShape>, eraser: List<Float>, radius: Float): List<VectorShape> {
        if (eraser.size < 2 || radius <= 0f) return list
        val out = ArrayList<VectorShape>(list.size)
        for (s in list) {
            if (!touches(s, eraser, radius)) { out += s; continue }
            if (s.kind != VecKind.PATH && s.kind != VecKind.POLY && s.kind != VecKind.LINE) continue
            val dense = densify(s.pts, maxOf(1f, radius / 3f))
            val reach = radius + s.width / 2f
            var run = ArrayList<Float>()
            fun flush() {
                if (run.size >= 4) out += s.copy(kind = if (s.kind == VecKind.LINE) VecKind.PATH else s.kind, pts = simplify(run, MIN_POINT_DIST))
                run = ArrayList()
            }
            var i = 0
            while (i + 1 < dense.size) {
                if (distToPolyline(dense[i], dense[i + 1], eraser) <= reach) flush() else { run += dense[i]; run += dense[i + 1] }
                i += 2
            }
            flush()
        }
        return out
    }

    /**
     * Палец по контурам: точки путей ближе [radius] к (x, y) сдвигаются на (dx, dy) * [strength] с мягким спадом.
     * Прямая превращается в путь (её можно гнуть); прямоугольники/эллипсы/пресеты не меняются.
     */
    fun smudge(list: List<VectorShape>, x: Float, y: Float, dx: Float, dy: Float, radius: Float, strength: Float): List<VectorShape> {
        if (radius <= 0f || (dx == 0f && dy == 0f)) return list
        return list.map { s ->
            if (s.kind != VecKind.PATH && s.kind != VecKind.POLY && s.kind != VecKind.POLYGON && s.kind != VecKind.LINE) return@map s
            val (pl, closed) = outline(s)
            if (distToPolyline(x, y, if (closed && pl.size >= 2) pl + listOf(pl[0], pl[1]) else pl) > radius) return@map s
            val dense = densify(s.pts, maxOf(1f, radius / 4f), false)
            val moved = ArrayList<Float>(dense.size)
            var i = 0
            while (i + 1 < dense.size) {
                val d = hypot(dense[i] - x, dense[i + 1] - y)
                val w = if (d >= radius) 0f else (1f - d / radius).let { it * it * (3f - 2f * it) } * strength.coerceIn(0f, 1f)
                moved += dense[i] + dx * w; moved += dense[i + 1] + dy * w
                i += 2
            }
            s.copy(kind = if (s.kind == VecKind.LINE) VecKind.PATH else s.kind, pts = moved)
        }
    }

    const val MIN_POINT_DIST = 2f
    const val MIN_EXTENT = 3f
}
