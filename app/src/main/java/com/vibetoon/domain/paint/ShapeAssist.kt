package com.vibetoon.domain.paint

import kotlin.math.hypot

/** Консервативное распознавание отрезка в экранных координатах. */
object ShapeAssist {
    const val HOLD_MS = 500L
    const val MAX_POINTS = 2048
    data class Point(val x: Float, val y: Float, val pressure: Float)

    fun line(points: List<Point>, heldMs: Long, density: Float): List<Point>? {
        if (heldMs < HOLD_MS || points.size < 3 || points.size > MAX_POINTS || !density.isFinite() || density <= 0f ||
            points.any { !it.x.isFinite() || !it.y.isFinite() || !it.pressure.isFinite() }) return null
        val a = points.first(); val b = points.last()
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (!len.isFinite() || len < 40f * density) return null
        val tolerance = maxOf(3f * density, len * .035f)
        var previous = 0f
        for (p in points) {
            val along = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (len * len)
            val cross = kotlin.math.abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
            if (cross > tolerance || along < -0.05f || along > 1.05f || along < previous - .08f) return null
            previous = maxOf(previous, along)
        }
        return listOf(a, b)
    }
    /** Held, closed triangle only when the entire path follows three distinct straight edges. */
    fun triangle(points: List<Point>, heldMs: Long, density: Float): List<Point>? {
        if (heldMs < HOLD_MS || points.size !in 12..MAX_POINTS || !density.isFinite() || density <= 0f ||
            points.any { !it.x.isFinite() || !it.y.isFinite() || !it.pressure.isFinite() }) return null
        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val size = minOf(maxX - minX, maxY - minY)
        if (!size.isFinite() || size < 40f * density || dist(points.first(), points.last()) > maxOf(8f * density, size * .1f)) return null
        val first = points.indices.maxByOrNull { dist(points[0], points[it]) } ?: return null
        val second = points.indices.maxByOrNull { dist(points[first], points[it]) } ?: return null
        val a = points[first]; val b = points[second]; val ab = dist(a, b)
        if (!ab.isFinite() || ab < 2f) return null
        fun lineDistance(p: Point) = kotlin.math.abs((p.x - a.x) * (b.y - a.y) - (p.y - a.y) * (b.x - a.x)) / ab
        val third = points.indices.maxByOrNull { lineDistance(points[it]) } ?: return null
        val c = points[third]
        if (lineDistance(c) < size * .45f) return null
        fun segmentDistance(p: Point, u: Point, v: Point): Float {
            val dx = v.x - u.x; val dy = v.y - u.y; val denom = dx * dx + dy * dy
            if (denom <= 0f || !denom.isFinite()) return Float.POSITIVE_INFINITY
            val t = (((p.x - u.x) * dx + (p.y - u.y) * dy) / denom).coerceIn(0f, 1f)
            return hypot(p.x - u.x - t * dx, p.y - u.y - t * dy)
        }
        val tolerance = maxOf(3f * density, size * .045f)
        if (points.any { minOf(segmentDistance(it, a, b), segmentDistance(it, b, c), segmentDistance(it, c, a)) > tolerance }) return null
        val perimeter = dist(a, b) + dist(b, c) + dist(c, a)
        val length = points.zipWithNext().sumOf { (u, v) -> dist(u, v).toDouble() }.toFloat()
        if (!perimeter.isFinite() || length < perimeter * .90f || length > perimeter * 1.2f) return null
        // Retain gesture direction and start from the nearest of the detected corners.
        val ordered = listOf(first, second, third).sorted().map { points[it] }
        val start = ordered.indices.minByOrNull { dist(ordered[it], points.first()) } ?: return null
        return (0..3).map { ordered[(start + it) % 3] }
    }

    /** Замкнутый штрих только при уверенном совпадении с осевым прямоугольником или эллипсом. */
    fun closed(points: List<Point>, heldMs: Long, density: Float): List<Point>? {
        if (heldMs < HOLD_MS || points.size < 12 || points.size > MAX_POINTS || !density.isFinite() || density <= 0f ||
            points.any { !it.x.isFinite() || !it.y.isFinite() || !it.pressure.isFinite() }) return null
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val w = maxX - minX; val h = maxY - minY
        if (!w.isFinite() || !h.isFinite() || minOf(w, h) < 40f * density) return null
        val a = points.first(); val z = points.last()
        if (hypot(a.x - z.x, a.y - z.y) > maxOf(10f * density, minOf(w, h) * .15f)) return null
        val cx = (minX + maxX) * .5f; val cy = (minY + maxY) * .5f
        var ellipseError = 0f; var rectError = 0f; var area = 0f; var length = 0f
        val quadrants = BooleanArray(4)
        val corners = BooleanArray(4)
        for (i in points.indices) {
            val p = points[i]; val q = points[(i + 1) % points.size]
            val x = (p.x - cx) / (w * .5f); val y = (p.y - cy) / (h * .5f)
            ellipseError += kotlin.math.abs(hypot(x, y) - 1f)
            rectError += kotlin.math.abs(maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) - 1f)
            val quadrant = (if (x >= 0) 1 else 0) + (if (y >= 0) 2 else 0)
            quadrants[quadrant] = true
            if (kotlin.math.abs(x) > .72f && kotlin.math.abs(y) > .72f) corners[quadrant] = true
            area += p.x * q.y - q.x * p.y
            length += hypot(q.x - p.x, q.y - p.y)
        }
        if (!quadrants.all { it } || length < 2f * (w + h) * .65f || length > 2f * (w + h) * 1.5f) return null
        val pressure = points.map { it.pressure }.average().toFloat()
        val clockwise = area >= 0f
        if (corners.all { it } && rectError / points.size < .09f) {
            val vertices = listOf(Point(minX, minY, pressure), Point(maxX, minY, pressure), Point(maxX, maxY, pressure), Point(minX, maxY, pressure))
            val start = vertices.indices.minByOrNull { hypot(vertices[it].x - a.x, vertices[it].y - a.y) } ?: return null
            return (0..4).map { vertices[(start + if (clockwise) it else 4 - it) % 4] }
        }
        if (ellipseError / points.size >= .10f) return null
        val start = kotlin.math.atan2((a.y - cy) / h, (a.x - cx) / w)
        return (0..64).map { i ->
            val angle = start + (if (clockwise) 1 else -1) * (2.0 * Math.PI * i / 64).toFloat()
            Point(cx + w * .5f * kotlin.math.cos(angle), cy + h * .5f * kotlin.math.sin(angle), pressure)
        }
    }

}
