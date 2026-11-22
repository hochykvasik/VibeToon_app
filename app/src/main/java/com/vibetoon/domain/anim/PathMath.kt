package com.vibetoon.domain.anim

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Граф рёбер сетки и геодезические (по рёбрам) расстояния: влияние не перепрыгивает через пустоту на соседние пряди. */
object MeshGraph {
    fun adjacency(n: Int, tris: IntArray): Array<IntArray> {
        val sets = Array(n) { LinkedHashSet<Int>() }
        for (t in 0 until tris.size / 3) {
            val a = tris[t * 3]; val b = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
            sets[a].add(b); sets[a].add(c); sets[b].add(a); sets[b].add(c); sets[c].add(a); sets[c].add(b)
        }
        return Array(n) { sets[it].toIntArray() }
    }

    private class Node(val d: Float, val i: Int)

    /**
     * Дейкстра по рёбрам: [pos] - позиции вершин в пикселях (x, y), [sources] - стартовые вершины с начальными
     * расстояниями [init]. Недостижимые вершины (другая «компонента» сетки) получают +бесконечность.
     */
    fun geodesic(pos: FloatArray, adj: Array<IntArray>, sources: IntArray, init: FloatArray): FloatArray {
        val n = pos.size / 2
        val dist = FloatArray(n) { Float.POSITIVE_INFINITY }
        val pq = PriorityQueue<Node>(compareBy { it.d })
        for ((j, s) in sources.withIndex()) if (init[j] < dist[s]) { dist[s] = init[j]; pq.add(Node(init[j], s)) }
        while (pq.isNotEmpty()) {
            val cur = pq.poll()!!
            if (cur.d > dist[cur.i]) continue
            for (j in adj[cur.i]) {
                val nd = cur.d + hypot(pos[cur.i * 2] - pos[j * 2], pos[cur.i * 2 + 1] - pos[j * 2 + 1])
                if (nd < dist[j]) { dist[j] = nd; pq.add(Node(nd, j)) }
            }
        }
        return dist
    }

    /** Средняя длина ребра (px) - масштаб для выбора стартовых вершин. */
    fun meanEdge(pos: FloatArray, adj: Array<IntArray>): Float {
        var s = 0.0; var c = 0
        for (i in adj.indices) for (j in adj[i]) if (j > i) { s += hypot(pos[i * 2] - pos[j * 2], pos[i * 2 + 1] - pos[j * 2 + 1]); c++ }
        return if (c == 0) 0f else (s / c).toFloat()
    }
}

/**
 * Deform Path: линия (центрипетальный Catmull-Rom) через пины. Каждая вершина запоминает локальную систему
 * координат линии: s - длина дуги В ПИКСЕЛЯХ (не доля), u и d - смещения вдоль касательной и нормали. При изгибе линии
 * вершина встаёт на ту же длину дуги, поэтому прядь не растягивается: тянется только форма, а не длина.
 * За концами линия продолжается по касательной.
 */
object PathMath {
    class Curve(val x: FloatArray, val y: FloatArray, val tx: FloatArray, val ty: FloatArray, val s: FloatArray) {
        val size get() = x.size
        val length get() = s[s.size - 1]
    }

    internal fun cr(p0: Float, p1: Float, p2: Float, p3: Float, t0: Float, t1: Float, t2: Float, t3: Float, t: Float): Float {
        val a1 = p0 * ((t1 - t) / (t1 - t0)) + p1 * ((t - t0) / (t1 - t0))
        val a2 = p1 * ((t2 - t) / (t2 - t1)) + p2 * ((t - t1) / (t2 - t1))
        val a3 = p2 * ((t3 - t) / (t3 - t2)) + p3 * ((t - t2) / (t3 - t2))
        val b1 = a1 * ((t2 - t) / (t2 - t0)) + a2 * ((t - t0) / (t2 - t0))
        val b2 = a2 * ((t3 - t) / (t3 - t1)) + a3 * ((t - t1) / (t3 - t1))
        return b1 * ((t2 - t) / (t2 - t1)) + b2 * ((t - t1) / (t2 - t1))
    }

    /** Кривая через [pts] (x, y в пикселях, минимум 2 точки); [sub] - число сэмплов на сегмент. */
    fun build(pts: FloatArray, sub: Int = 24): Curve? {
        val n = pts.size / 2
        if (n < 2) return null
        val ex = FloatArray((n + 2) * 2)
        ex[0] = 2 * pts[0] - pts[2]; ex[1] = 2 * pts[1] - pts[3]
        for (i in 0 until n * 2) ex[2 + i] = pts[i]
        ex[(n + 1) * 2] = 2 * pts[(n - 1) * 2] - pts[(n - 2) * 2]; ex[(n + 1) * 2 + 1] = 2 * pts[(n - 1) * 2 + 1] - pts[(n - 2) * 2 + 1]
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        fun tt(t: Float, a: Int, b: Int): Float =
            t + max(hypot(ex[b * 2] - ex[a * 2], ex[b * 2 + 1] - ex[a * 2 + 1]), 1e-4f).pow(0.5f)
        for (i in 0 until n - 1) {
            val t0 = 0f; val t1 = tt(t0, i, i + 1); val t2 = tt(t1, i + 1, i + 2); val t3 = tt(t2, i + 2, i + 3)
            val steps = if (i == n - 2) sub else sub - 1
            for (k in 0..steps) {
                val t = t1 + (k.toFloat() / sub) * (t2 - t1)
                xs.add(cr(ex[i * 2], ex[i * 2 + 2], ex[i * 2 + 4], ex[i * 2 + 6], t0, t1, t2, t3, t))
                ys.add(cr(ex[i * 2 + 1], ex[i * 2 + 3], ex[i * 2 + 5], ex[i * 2 + 7], t0, t1, t2, t3, t))
            }
        }
        val m = xs.size
        val x = FloatArray(m) { xs[it] }; val y = FloatArray(m) { ys[it] }
        val s = FloatArray(m)
        for (i in 1 until m) s[i] = s[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        val tx = FloatArray(m); val ty = FloatArray(m)
        for (i in 0 until m) {
            val a = max(i - 1, 0); val b = min(i + 1, m - 1)
            val dx = x[b] - x[a]; val dy = y[b] - y[a]
            val l = hypot(dx, dy)
            if (l > 1e-6f) { tx[i] = dx / l; ty[i] = dy / l } else { tx[i] = if (i > 0) tx[i - 1] else 1f; ty[i] = if (i > 0) ty[i - 1] else 0f }
        }
        return Curve(x, y, tx, ty, s)
    }

    /** Точка и касательная на длине дуги [s] пикселей (за концами - продолжение по касательной): out = x, y, tx, ty. */
    fun at(c: Curve, s: Float, out: FloatArray) {
        val last = c.size - 1
        if (s <= 0f) { out[0] = c.x[0] + c.tx[0] * s; out[1] = c.y[0] + c.ty[0] * s; out[2] = c.tx[0]; out[3] = c.ty[0]; return }
        if (s >= c.length) {
            val e = s - c.length
            out[0] = c.x[last] + c.tx[last] * e; out[1] = c.y[last] + c.ty[last] * e; out[2] = c.tx[last]; out[3] = c.ty[last]; return
        }
        var lo = 0; var hi = last
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (c.s[mid] <= s) lo = mid else hi = mid }
        val seg = c.s[hi] - c.s[lo]
        val a = if (seg > 1e-6f) (s - c.s[lo]) / seg else 0f
        out[0] = c.x[lo] + (c.x[hi] - c.x[lo]) * a; out[1] = c.y[lo] + (c.y[hi] - c.y[lo]) * a
        val tx = c.tx[lo] + (c.tx[hi] - c.tx[lo]) * a; val ty = c.ty[lo] + (c.ty[hi] - c.ty[lo]) * a
        val l = hypot(tx, ty)
        if (l > 1e-6f) { out[2] = tx / l; out[3] = ty / l } else { out[2] = c.tx[lo]; out[3] = c.ty[lo] }
    }

    /** Привязка вершин: [s], [u], [d] - локальные координаты, [w] - вес влияния (smoothstep по геодезическому расстоянию). */
    class Binding(val s: FloatArray, val u: FloatArray, val d: FloatArray, val w: FloatArray)

    /**
     * Привязывает вершины [q] (пиксели) к исходной кривой [rest]. Проекция - на отрезок между сэмплами (без ступенек),
     * за концами - на продолжение по касательной. Вес: расстояние до кривой считается по рёбрам сетки [tris] от вершин,
     * ближайших к кривой, поэтому соседние пряди (другая связная часть сетки) не получают влияния.
     */
    fun bind(q: FloatArray, tris: IntArray, rest: Curve, radius: Float): Binding {
        val n = q.size / 2
        val sArr = FloatArray(n); val uArr = FloatArray(n); val dArr = FloatArray(n)
        val euclid = FloatArray(n)
        val tmp = FloatArray(4)
        for (i in 0 until n) {
            val vx = q[i * 2]; val vy = q[i * 2 + 1]
            var best = Float.MAX_VALUE; var bs = 0f
            for (k in 0 until rest.size - 1) {
                val ex = rest.x[k + 1] - rest.x[k]; val ey = rest.y[k + 1] - rest.y[k]
                val l2 = ex * ex + ey * ey
                var t = if (l2 > 1e-9f) ((vx - rest.x[k]) * ex + (vy - rest.y[k]) * ey) / l2 else 0f
                val raw = t
                t = t.coerceIn(0f, 1f)
                val dist = hypot(vx - (rest.x[k] + ex * t), vy - (rest.y[k] + ey * t))
                if (dist < best) {
                    best = dist
                    val tu = if (k == 0 && raw < 0f) raw else if (k == rest.size - 2 && raw > 1f) raw else t
                    bs = rest.s[k] + tu * (rest.s[k + 1] - rest.s[k])
                }
            }
            sArr[i] = bs; euclid[i] = best
            at(rest, bs, tmp)
            val rx = vx - tmp[0]; val ry = vy - tmp[1]
            uArr[i] = rx * tmp[2] + ry * tmp[3]
            dArr[i] = -rx * tmp[3] + ry * tmp[2]      // нормаль = (-ty, tx)
        }
        // геодезическое расстояние до кривой: старт - вершины, почти ближайшие к кривой
        val adj = MeshGraph.adjacency(n, tris)
        // старт - ближайшая к каждому сэмплу кривой вершина: покрывает всю кривую и не цепляет чужие пряди
        val src = ArrayList<Int>(); val init = ArrayList<Float>()
        for (k in 0 until rest.size) {
            var bi = 0; var bd = Float.MAX_VALUE
            for (i in 0 until n) { val dd = hypot(q[i * 2] - rest.x[k], q[i * 2 + 1] - rest.y[k]); if (dd < bd) { bd = dd; bi = i } }
            src.add(bi); init.add(bd)
        }
        val geo = MeshGraph.geodesic(q, adj, src.toIntArray(), init.toFloatArray())
        val r = max(radius, 1f)
        val w = FloatArray(n) {
            val g = geo[it]
            if (g >= r || g.isInfinite()) 0f else { val x = 1f - g / r; x * x * (3f - 2f * x) }
        }
        return Binding(sArr, uArr, dArr, w)
    }

    /** Новые позиции вершин (пиксели) для текущей кривой [cur]; вершины вне влияния остаются на месте. */
    fun deform(q: FloatArray, b: Binding, cur: Curve): FloatArray {
        val out = q.copyOf()
        val tmp = FloatArray(4)
        for (i in 0 until q.size / 2) {
            val w = b.w[i]
            if (w <= 1e-4f) continue
            at(cur, b.s[i], tmp)
            val tx = tmp[0] + tmp[2] * b.u[i] - tmp[3] * b.d[i]
            val ty = tmp[1] + tmp[3] * b.u[i] + tmp[2] * b.d[i]
            out[i * 2] = q[i * 2] * (1f - w) + tx * w
            out[i * 2 + 1] = q[i * 2 + 1] * (1f - w) + ty * w
        }
        return out
    }
}
