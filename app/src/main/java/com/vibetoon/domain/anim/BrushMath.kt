package com.vibetoon.domain.anim

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Кисть по вершинам сетки (Deform Brush): Grab - сдвиг, Smooth - разглаживание, Bloat - раздутие, Pinch - сжатие.
 * Сдвигает ВЕРШИНЫ (смещения, как у остальных деформеров), а не пиксели. Ядро спада - Вендланд C2. Проводка пальца
 * разбивается на шаги по 25% радиуса, чтобы быстрый мазок не оставлял дыр. После каждого шага сглаживается поле
 * СМЕЩЕНИЙ (а не позиции): исходная форма не «худеет», границу не стягивает, а складки сетки разглаживаются.
 */
object BrushMath {
    enum class Mode { GRAB, SMOOTH, BLOAT, PINCH }

    /** Вендланд C2: (1 - q)^4 (4q + 1) на q = d / R; 1 в центре, 0 на радиусе, без острых углов. */
    fun falloff(d: Float, r: Float): Float {
        if (d >= r) return 0f
        val q = d / r
        val m = 1f - q
        return m * m * m * m * (4f * q + 1f)
    }

    /**
     * Один мазок от ([fromU], [fromV]) до ([toU], [toV]) (доли слоя). [off] - смещения вершин (доли слоя), меняются
     * на месте; [radiusPx] - радиус кисти в пикселях слоя; [w], [h] - размер слоя. Возвращает индексы затронутых вершин.
     */
    fun stroke(
        mesh: Mesh, adj: Array<IntArray>, off: FloatArray, w: Float, h: Float,
        fromU: Float, fromV: Float, toU: Float, toV: Float, radiusPx: Float, strength: Float, mode: Mode
    ): Set<Int> {
        val touched = HashSet<Int>()
        if (radiusPx <= 1f || strength <= 0.001f || mesh.count == 0) return touched
        val mx = (toU - fromU) * w; val my = (toV - fromV) * h
        val len = hypot(mx, my)
        val step = max(radiusPx * 0.25f, 2f)
        val steps = max(1, ceil(len / step).toInt())
        val dx = mx / steps; val dy = my / steps
        for (s in 1..steps) {
            val cx = fromU * w + dx * s; val cy = fromV * h + dy * s
            val hit = ArrayList<Int>()
            val wt = ArrayList<Float>()
            for (i in 0 until mesh.count) {
                val px = (mesh.u(i) + off[i * 2]) * w; val py = (mesh.v(i) + off[i * 2 + 1]) * h
                val d = hypot(px - cx, py - cy)
                if (d >= radiusPx) continue
                val f = falloff(d, radiusPx) * strength
                hit.add(i); wt.add(f)
                when (mode) {
                    Mode.GRAB -> { off[i * 2] += dx * f / w; off[i * 2 + 1] += dy * f / h }
                    Mode.BLOAT -> { off[i * 2] += (px - cx) * f * 0.15f / w; off[i * 2 + 1] += (py - cy) * f * 0.15f / h }
                    Mode.PINCH -> { off[i * 2] -= (px - cx) * f * 0.15f / w; off[i * 2 + 1] -= (py - cy) * f * 0.15f / h }
                    Mode.SMOOTH -> {}
                }
            }
            if (hit.isEmpty() || adj.size != mesh.count) continue
            val passes = if (mode == Mode.SMOOTH) 3 else 1
            val lam = if (mode == Mode.SMOOTH) 0.4f * strength else 0.15f
            repeat(passes) {
                val snap = off.copyOf()
                for ((j, i) in hit.withIndex()) {
                    val nb = adj[i]
                    if (nb.isEmpty()) continue
                    var ax = 0f; var ay = 0f
                    for (k in nb) { ax += snap[k * 2]; ay += snap[k * 2 + 1] }
                    ax /= nb.size; ay /= nb.size
                    val a = (wt[j] / max(strength, 1e-3f)).coerceIn(0f, 1f) * lam
                    off[i * 2] = snap[i * 2] * (1f - a) + ax * a
                    off[i * 2 + 1] = snap[i * 2 + 1] * (1f - a) + ay * a
                }
            }
            touched.addAll(hit)
        }
        return touched
    }
}
