package com.vibetoon.domain.anim

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Упрощённый 2D ARAP (As-Rigid-As-Possible, Sorkine & Alexa): треугольники по возможности только поворачиваются и
 * переносятся, поэтому прядь сгибается дугой и сохраняет длину и толщину, а не растягивается как жвачка.
 *
 * Алгоритм «локально-глобально»: (1) локальный шаг - для каждой вершины находится лучший поворот R_i (полярное
 * разложение 2x2 по ковариации рёбер); (2) глобальный шаг - решается разреженная система Лапласиана с котангенс-весами
 * и закреплёнными вершинами (пины и якоря). Систему решает метод сопряжённых градиентов (разреженная матрица в CSR):
 * это то же самое, что Eigen::SimplicialLDLT по смыслу, но без внешней библиотеки; для сеток до 1024 вершин быстро.
 */
class ArapSystem(private val q: FloatArray, tris: IntArray) {
    val n = q.size / 2
    private val start = IntArray(n + 1)
    private val nbr: IntArray
    private val wgt: FloatArray

    init {
        // котангенс-веса рёбер: для ребра (i, j) сумма 0.5*cot напротив ребра по соседним треугольникам
        val acc = HashMap<Long, Float>()
        fun key(a: Int, b: Int) = minOf(a, b).toLong() * 100003L + maxOf(a, b)
        for (t in 0 until tris.size / 3) {
            val ids = intArrayOf(tris[t * 3], tris[t * 3 + 1], tris[t * 3 + 2])
            for (k in 0 until 3) {
                val o = ids[k]; val a = ids[(k + 1) % 3]; val b = ids[(k + 2) % 3]
                val ax = q[a * 2] - q[o * 2]; val ay = q[a * 2 + 1] - q[o * 2 + 1]
                val bx = q[b * 2] - q[o * 2]; val by = q[b * 2 + 1] - q[o * 2 + 1]
                val cross = kotlin.math.abs(ax * by - ay * bx).coerceAtLeast(1e-6f)
                val cot = (ax * bx + ay * by) / cross
                acc[key(a, b)] = (acc[key(a, b)] ?: 0f) + 0.5f * cot
            }
        }
        val adj = Array(n) { ArrayList<Int>() }
        val w = HashMap<Long, Float>()
        for ((k, v) in acc) {
            val a = (k / 100003L).toInt(); val b = (k % 100003L).toInt()
            adj[a].add(b); adj[b].add(a)
            w[k] = v.coerceAtLeast(1e-3f)   // тупые углы дают отрицательные веса: ограничиваем, чтобы матрица осталась устойчивой
        }
        var total = 0
        for (i in 0 until n) { start[i] = total; total += adj[i].size }
        start[n] = total
        nbr = IntArray(total); wgt = FloatArray(total)
        for (i in 0 until n) {
            var o = start[i]
            for (j in adj[i]) { nbr[o] = j; wgt[o] = w.getValue(key(i, j)); o++ }
        }
    }

    /**
     * Решает деформацию. [fixed] - индексы закреплённых вершин, [target] - их новые позиции (x, y пикселей);
     * [init] - начальное приближение (позиции всех вершин) или null. Возвращает позиции всех вершин (x, y).
     * Мягкие пины: [lambda] (вес пружины на вершину, 0 - нет) и [springTarget] (x, y цели) дают систему
     * `(L + Lambda) P = b + Lambda P_target`: вершины тянутся к цели, а не прибиваются к ней гвоздём.
     */
    fun solve(
        fixed: IntArray, target: FloatArray, init: FloatArray?, outer: Int = 6, cgIter: Int = 80,
        lambda: FloatArray? = null, springTarget: FloatArray? = null
    ): FloatArray {
        val p = (init ?: q).copyOf()
        val isFixed = BooleanArray(n)
        for ((k, v) in fixed.withIndex()) { isFixed[v] = true; p[v * 2] = target[k * 2]; p[v * 2 + 1] = target[k * 2 + 1] }
        if (fixed.isEmpty() || n == 0) return p
        val rot = FloatArray(n * 2) { if (it % 2 == 0) 1f else 0f }   // (cos, sin) на вершину
        val bx = DoubleArray(n); val by = DoubleArray(n)
        repeat(outer) {
            // локальный шаг: R_i = argmax tr(R^T S_i), угол = atan2(S10 - S01, S00 + S11)
            for (i in 0 until n) {
                var s00 = 0.0; var s01 = 0.0; var s10 = 0.0; var s11 = 0.0
                for (e in start[i] until start[i + 1]) {
                    val j = nbr[e]; val w = wgt[e].toDouble()
                    val qx = (q[i * 2] - q[j * 2]).toDouble(); val qy = (q[i * 2 + 1] - q[j * 2 + 1]).toDouble()
                    val px = (p[i * 2] - p[j * 2]).toDouble(); val py = (p[i * 2 + 1] - p[j * 2 + 1]).toDouble()
                    s00 += w * px * qx; s01 += w * px * qy; s10 += w * py * qx; s11 += w * py * qy
                }
                val th = atan2(s10 - s01, s00 + s11)
                rot[i * 2] = cos(th).toFloat(); rot[i * 2 + 1] = sin(th).toFloat()
            }
            // правая часть: b_i = sum_j w_ij/2 * (R_i + R_j)(q_i - q_j); закреплённые соседи переносятся вправо
            for (i in 0 until n) {
                var sx = 0.0; var sy = 0.0
                for (e in start[i] until start[i + 1]) {
                    val j = nbr[e]; val w = wgt[e].toDouble()
                    val dx = (q[i * 2] - q[j * 2]).toDouble(); val dy = (q[i * 2 + 1] - q[j * 2 + 1]).toDouble()
                    val c = (rot[i * 2] + rot[j * 2]).toDouble() * 0.5; val s = (rot[i * 2 + 1] + rot[j * 2 + 1]).toDouble() * 0.5
                    sx += w * (c * dx - s * dy); sy += w * (s * dx + c * dy)
                    if (isFixed[j]) { sx += w * p[j * 2]; sy += w * p[j * 2 + 1] }
                }
                bx[i] = sx; by[i] = sy
            }
            if (lambda != null && springTarget != null) for (i in 0 until n) if (!isFixed[i] && lambda[i] > 0f) {
                bx[i] += lambda[i] * springTarget[i * 2].toDouble(); by[i] += lambda[i] * springTarget[i * 2 + 1].toDouble()
            }
            cg(isFixed, p, bx, 0, cgIter, lambda)
            cg(isFixed, p, by, 1, cgIter, lambda)
        }
        return p
    }

    /** CG для свободных вершин: (L_ii x_i - sum_{j свободные} w_ij x_j) = b_i; [axis] 0 - x, 1 - y; результат пишется в [p]. */
    private fun cg(isFixed: BooleanArray, p: FloatArray, b: DoubleArray, axis: Int, maxIter: Int, lambda: FloatArray?) {
        val x = DoubleArray(n) { p[it * 2 + axis].toDouble() }
        fun mul(v: DoubleArray, out: DoubleArray) {
            for (i in 0 until n) {
                if (isFixed[i]) { out[i] = 0.0; continue }
                var d = 0.0; var s = 0.0
                for (e in start[i] until start[i + 1]) {
                    val w = wgt[e].toDouble(); d += w
                    if (!isFixed[nbr[e]]) s += w * v[nbr[e]]
                }
                out[i] = (d + (lambda?.get(i)?.toDouble() ?: 0.0)) * v[i] - s + 1e-9 * v[i]
            }
        }
        // диагональный (Якоби) предобуславливатель: пружины Lambda сильно меняют масштаб диагонали
        val diag = DoubleArray(n) { i ->
            var d = 0.0
            for (e in start[i] until start[i + 1]) if (!isFixed[nbr[e]]) d += wgt[e].toDouble() else d += wgt[e].toDouble()
            (d + (lambda?.get(i)?.toDouble() ?: 0.0)).coerceAtLeast(1e-9)
        }
        val r = DoubleArray(n); val ap = DoubleArray(n); val z = DoubleArray(n)
        mul(x, ap)
        for (i in 0 until n) r[i] = if (isFixed[i]) 0.0 else b[i] - ap[i]
        for (i in 0 until n) z[i] = r[i] / diag[i]
        val d = z.copyOf()
        var rz = 0.0; for (i in 0 until n) rz += r[i] * z[i]
        var rr = 0.0; for (i in 0 until n) rr += r[i] * r[i]
        val tol = 1e-10 * (1.0 + rr)
        var it = 0
        while (it < maxIter && rr > tol) {
            mul(d, ap)
            var dap = 0.0; for (i in 0 until n) dap += d[i] * ap[i]
            if (dap <= 1e-20) break
            val alpha = rz / dap
            for (i in 0 until n) { x[i] += alpha * d[i]; r[i] -= alpha * ap[i] }
            for (i in 0 until n) z[i] = r[i] / diag[i]
            var rzNew = 0.0; var rrNew = 0.0
            for (i in 0 until n) { rzNew += r[i] * z[i]; rrNew += r[i] * r[i] }
            val beta = rzNew / rz
            for (i in 0 until n) d[i] = z[i] + beta * d[i]
            rz = rzNew; rr = rrNew
            it++
        }
        for (i in 0 until n) if (!isFixed[i]) p[i * 2 + axis] = x[i].toFloat()
    }

    companion object {
        fun length(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
    }
}
