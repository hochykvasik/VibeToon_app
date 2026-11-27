package com.vibetoon.domain.raster

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sprint 33: рамка свободной трансформации выделения в пикселях слоя.
 * Углы по часовой: 0 - левый верхний, 1 - правый верхний, 2 - правый нижний, 3 - левый нижний.
 * Пока рамка правится, пиксели не меняются; «Применить» переносит их одной операцией через гомографию.
 */
object TransformBox {
    enum class Mode { FREE, SKEW, DISTORT }
    enum class Handle { C0, C1, C2, C3, E0, E1, E2, E3, PIVOT, MOVE, ROTATE }

    data class P(val x: Double, val y: Double) {
        operator fun plus(o: P) = P(x + o.x, y + o.y)
        operator fun minus(o: P) = P(x - o.x, y - o.y)
        operator fun times(k: Double) = P(x * k, y * k)
    }

    /** [src] - исходный прямоугольник выделения, [corners] - текущая рамка, [pivot] - опорная точка. */
    data class Box(val src: IRect, val corners: List<P>, val pivot: P)

    /** Числовой ввод: масштаб (1 = 100%), поворот и наклон в градусах, сдвиг в пикселях слоя, отражения. */
    data class Numeric(val scaleX: Double = 1.0, val scaleY: Double = 1.0, val rotationDeg: Double = 0.0,
                       val skewXDeg: Double = 0.0, val skewYDeg: Double = 0.0, val dx: Double = 0.0, val dy: Double = 0.0,
                       val flipX: Boolean = false, val flipY: Boolean = false)

    const val MAX_SCALE = 8.0
    const val MIN_SCALE = 1.0 / 8.0

    fun initial(r: IRect): Box {
        val x0 = r.x.toDouble(); val y0 = r.y.toDouble(); val x1 = (r.x + r.w).toDouble(); val y1 = (r.y + r.h).toDouble()
        return Box(r, listOf(P(x0, y0), P(x1, y0), P(x1, y1), P(x0, y1)), P((x0 + x1) / 2, (y0 + y1) / 2))
    }

    /** Точка рамки по долям (u, v) в [0..1] (билинейно по углам) - для 9 опорных точек. */
    fun at(b: Box, u: Double, v: Double): P {
        val c = b.corners
        val top = c[0] + (c[1] - c[0]) * u
        val bottom = c[3] + (c[2] - c[3]) * u
        return top + (bottom - top) * v
    }

    /** Опорная точка по сетке 3x3: [ax], [ay] в 0..2 (0 - левый/верхний край, 1 - центр, 2 - правый/нижний). */
    fun withAnchor(b: Box, ax: Int, ay: Int): Box = b.copy(pivot = at(b, ax.coerceIn(0, 2) / 2.0, ay.coerceIn(0, 2) / 2.0))

    fun edgeMid(b: Box, i: Int): P { val c = b.corners; return (c[i] + c[(i + 1) % 4]) * .5 }

    private fun cross(a: P, b: P) = a.x * b.y - a.y * b.x

    fun inside(b: Box, p: P): Boolean {
        val c = b.corners
        var pos = 0; var neg = 0
        for (i in 0..3) {
            val s = cross(c[(i + 1) % 4] - c[i], p - c[i])
            if (s > 0) pos++ else if (s < 0) neg++
        }
        return pos == 0 || neg == 0
    }

    /** Что берёт касание: угол, середина стороны, опора; внутри - перенос, снаружи - поворот. */
    fun hit(b: Box, p: P, radius: Double): Handle {
        val c = b.corners
        val near = ArrayList<Pair<Handle, Double>>()
        for (i in 0..3) near += listOf(Handle.C0, Handle.C1, Handle.C2, Handle.C3)[i] to hypot(c[i].x - p.x, c[i].y - p.y)
        for (i in 0..3) { val m = edgeMid(b, i); near += listOf(Handle.E0, Handle.E1, Handle.E2, Handle.E3)[i] to hypot(m.x - p.x, m.y - p.y) }
        near += Handle.PIVOT to hypot(b.pivot.x - p.x, b.pivot.y - p.y) * 1.2   // опора уступает углам при наложении
        near.filter { it.second <= radius }.minByOrNull { it.second }?.let { return it.first }
        return if (inside(b, p)) Handle.MOVE else Handle.ROTATE
    }

    /** Аффинная матрица (a, b, c, d, tx, ty): x' = a x + b y + tx, y' = c x + d y + ty. */
    private fun apply(b: Box, a: Double, bb: Double, c: Double, d: Double, tx: Double, ty: Double, movePivot: Boolean): Box {
        fun m(p: P) = P(a * p.x + bb * p.y + tx, c * p.x + d * p.y + ty)
        return b.copy(corners = b.corners.map(::m), pivot = if (movePivot) m(b.pivot) else b.pivot)
    }

    /** Координаты точки относительно опоры в базисе сторон рамки (u - верхняя сторона, v - левая). */
    private fun local(b: Box, p: P): P? {
        val u = b.corners[1] - b.corners[0]; val v = b.corners[3] - b.corners[0]
        val det = cross(u, v)
        if (abs(det) < 1e-9) return null
        val q = p - b.pivot
        return P(cross(q, v) / det, cross(u, q) / det)
    }

    /** Масштаб (sx, sy) вдоль сторон рамки относительно опоры. */
    private fun scaleLocal(b: Box, sx: Double, sy: Double): Box {
        val u = b.corners[1] - b.corners[0]; val v = b.corners[3] - b.corners[0]
        val det = cross(u, v)
        if (abs(det) < 1e-9) return b
        fun m(p: P): P {
            val q = p - b.pivot
            val lu = cross(q, v) / det; val lv = cross(u, q) / det
            return b.pivot + u * (lu * sx) + v * (lv * sy)
        }
        return b.copy(corners = b.corners.map(::m))
    }

    private fun ratio(to: Double, from: Double): Double = if (abs(from) < 1e-6) 1.0 else to / from

    /** Перетаскивание ручки от [from] к [to] (пиксели слоя) относительно рамки [start] на начало жеста. */
    fun drag(start: Box, handle: Handle, mode: Mode, from: P, to: P, proportional: Boolean): Box {
        val d = to - from
        val c = start.corners
        return when (handle) {
            Handle.MOVE -> apply(start, 1.0, 0.0, 0.0, 1.0, d.x, d.y, true)
            Handle.PIVOT -> start.copy(pivot = start.pivot + d)
            Handle.ROTATE -> {
                val a0 = atan2(from.y - start.pivot.y, from.x - start.pivot.x)
                val a1 = atan2(to.y - start.pivot.y, to.x - start.pivot.x)
                rotate(start, a1 - a0)
            }
            Handle.C0, Handle.C1, Handle.C2, Handle.C3 -> {
                val i = handle.ordinal
                if (mode == Mode.DISTORT) start.copy(corners = c.mapIndexed { k, p -> if (k == i) p + d else p })
                else {
                    val lf = local(start, from) ?: return start; val lt = local(start, to) ?: return start
                    var sx = ratio(lt.x, lf.x); var sy = ratio(lt.y, lf.y)
                    if (proportional) {
                        val q0 = from - start.pivot; val q1 = to - start.pivot
                        val l2 = q0.x * q0.x + q0.y * q0.y
                        val k = if (l2 < 1e-9) 1.0 else (q0.x * q1.x + q0.y * q1.y) / l2
                        sx = k; sy = k
                    }
                    scaleLocal(start, sx, sy)
                }
            }
            Handle.E0, Handle.E1, Handle.E2, Handle.E3 -> {
                val i = handle.ordinal - Handle.E0.ordinal
                val a = i; val b = (i + 1) % 4
                when (mode) {
                    Mode.DISTORT -> start.copy(corners = c.mapIndexed { k, p -> if (k == a || k == b) p + d else p })
                    Mode.SKEW -> {
                        // сдвиг стороны вдоль самой себя: наклон без изменения высоты
                        val e = c[b] - c[a]
                        val l = hypot(e.x, e.y)
                        if (l < 1e-9) start else {
                            val t = (d.x * e.x + d.y * e.y) / (l * l)
                            val s = e * t
                            start.copy(corners = c.mapIndexed { k, p -> if (k == a || k == b) p + s else p })
                        }
                    }
                    Mode.FREE -> {
                        val lf = local(start, from) ?: return start; val lt = local(start, to) ?: return start
                        if (i == 0 || i == 2) scaleLocal(start, 1.0, ratio(lt.y, lf.y)) else scaleLocal(start, ratio(lt.x, lf.x), 1.0)
                    }
                }
            }
        }
    }

    fun rotate(b: Box, radians: Double): Box {
        val cs = cos(radians); val sn = sin(radians)
        val p = b.pivot
        return apply(b, cs, -sn, sn, cs, p.x - cs * p.x + sn * p.y, p.y - sn * p.x - cs * p.y, false)
    }

    /** Числовая трансформация поверх текущей рамки относительно её опоры. */
    fun numeric(b: Box, n: Numeric): Box? {
        val all = listOf(n.scaleX, n.scaleY, n.rotationDeg, n.skewXDeg, n.skewYDeg, n.dx, n.dy)
        if (all.any { !it.isFinite() } || abs(n.scaleX) < 1e-3 || abs(n.scaleY) < 1e-3 || abs(n.skewXDeg) >= 85 || abs(n.skewYDeg) >= 85) return null
        val sx = n.scaleX * if (n.flipX) -1 else 1; val sy = n.scaleY * if (n.flipY) -1 else 1
        val kx = tan(Math.toRadians(n.skewXDeg)); val ky = tan(Math.toRadians(n.skewYDeg))
        val r = Math.toRadians(n.rotationDeg); val cs = cos(r); val sn = sin(r)
        // M = R * K * S, K = [[1, kx], [ky, 1]]
        val k00 = sx; val k01 = kx * sy; val k10 = ky * sx; val k11 = sy
        val a = cs * k00 - sn * k10; val bb = cs * k01 - sn * k11
        val c = sn * k00 + cs * k10; val d = sn * k01 + cs * k11
        val p = b.pivot
        return apply(b, a, bb, c, d, p.x - a * p.x - bb * p.y + n.dx, p.y - c * p.x - d * p.y + n.dy, false).let { it.copy(pivot = it.pivot + P(n.dx, n.dy)) }
    }

    fun isIdentity(b: Box, eps: Double = .01): Boolean = initial(b.src).corners.zip(b.corners).all { (p, q) -> abs(p.x - q.x) < eps && abs(p.y - q.y) < eps }

    fun area(b: Box): Double {
        val c = b.corners
        var s = 0.0
        for (i in 0..3) s += cross(c[i], c[(i + 1) % 4])
        return s / 2
    }

    /** Рамка годится: выпуклая, не вырожденная, масштаб площади в разумных пределах (отражение допускается). */
    fun valid(b: Box): Boolean {
        val c = b.corners
        if (c.size != 4 || c.any { !it.x.isFinite() || !it.y.isFinite() } || b.src.w <= 0 || b.src.h <= 0) return false
        var pos = 0; var neg = 0
        for (i in 0..3) {
            val s = cross(c[(i + 1) % 4] - c[i], c[(i + 2) % 4] - c[(i + 1) % 4])
            if (s > 1e-9) pos++ else if (s < -1e-9) neg++
        }
        if (!(pos == 4 || neg == 4)) return false
        val k = abs(area(b)) / (b.src.w.toDouble() * b.src.h)
        return k in MIN_SCALE * MIN_SCALE..MAX_SCALE * MAX_SCALE
    }

    /**
     * Гомография исходного прямоугольника в рамку: 9 чисел строками [a b c; d e f; g h 1] от нормированных (u, v)
     * в [0..1] (u = (x - src.x)/src.w). null - рамка вырождена.
     */
    fun homography(b: Box): DoubleArray? {
        val c = b.corners
        val x0 = c[0].x; val y0 = c[0].y; val x1 = c[1].x; val y1 = c[1].y
        val x2 = c[2].x; val y2 = c[2].y; val x3 = c[3].x; val y3 = c[3].y
        val sx = x0 - x1 + x2 - x3; val sy = y0 - y1 + y2 - y3
        val g: Double; val h: Double
        if (abs(sx) < 1e-9 && abs(sy) < 1e-9) { g = 0.0; h = 0.0 }
        else {
            val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
            val den = dx1 * dy2 - dx2 * dy1
            if (abs(den) < 1e-12) return null
            g = (sx * dy2 - dx2 * sy) / den; h = (dx1 * sy - sx * dy1) / den
        }
        val m = doubleArrayOf(x1 - x0 + g * x1, x3 - x0 + h * x3, x0, y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h, 1.0)
        return if (m.all { it.isFinite() }) m else null
    }

    fun mapUnit(m: DoubleArray, u: Double, v: Double): P {
        val w = m[6] * u + m[7] * v + m[8]
        return P((m[0] * u + m[1] * v + m[2]) / w, (m[3] * u + m[4] * v + m[5]) / w)
    }

    fun invert(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        if (abs(det) < 1e-12 || !det.isFinite()) return null
        val r = doubleArrayOf(A, -(b * i - c * h), b * f - c * e, B, a * i - c * g, -(a * f - c * d), C, -(a * h - b * g), a * e - b * d)
        return DoubleArray(9) { r[it] / det }
    }
}
