package com.vibetoon.domain.anim

import com.vibetoon.domain.model.Affine2D
import kotlin.math.hypot
import kotlin.math.max

/** Сегмент кости в канвасе: сустав (hx, hy) и конец (tx, ty). */
class BoneSeg(val id: Int, val hx: Float, val hy: Float, val tx: Float, val ty: Float, val strength: Float = 1f) {
    val length get() = hypot(tx - hx, ty - hy)
}

/**
 * Веса костей (skinning). Вес кости для вершины = Strength * (длина кости / расстояние до её отрезка),
 * берутся до [MAX_INFLUENCES] самых сильных костей, сумма весов нормируется до 1.
 */
object SkinMath {
    const val MAX_INFLUENCES = 4
    private const val MIN_DIST = 1f

    /** Расстояние от точки до отрезка кости. */
    fun distToSegment(px: Float, py: Float, b: BoneSeg): Float {
        val vx = b.tx - b.hx
        val vy = b.ty - b.hy
        val l2 = vx * vx + vy * vy
        val t = if (l2 <= 1e-6f) 0f else (((px - b.hx) * vx + (py - b.hy) * vy) / l2).coerceIn(0f, 1f)
        return hypot(px - (b.hx + vx * t), py - (b.hy + vy * t))
    }

    /** Пары (индекс в [bones], вес) для точки, сумма весов = 1; пусто, если костей нет. */
    fun weights(px: Float, py: Float, bones: List<BoneSeg>): List<Pair<Int, Float>> {
        if (bones.isEmpty()) return emptyList()
        val raw = bones.mapIndexed { i, b ->
            val len = max(b.length, 1f)
            i to b.strength * (len / max(distToSegment(px, py, b), MIN_DIST))
        }.sortedByDescending { it.second }.take(MAX_INFLUENCES)
        val sum = raw.sumOf { it.second.toDouble() }.toFloat()
        if (sum <= 0f) return emptyList()
        return raw.map { it.first to it.second / sum }
    }

    /**
     * Спринт 49: смешанная дельта костей для веса [w] (sum w_i * D_i как одна аффинная матрица). Так как сумма весов = 1,
     * результат применяется к точке так же, как [skin]: B(p) = sum(w_i * D_i(p)). Пустые веса - единичная матрица.
     */
    fun blend(w: List<Pair<Int, Float>>, deltas: List<Affine2D>): Affine2D {
        if (w.isEmpty()) return Affine2D.IDENTITY
        var a = 0f; var b = 0f; var c = 0f; var d = 0f; var tx = 0f; var ty = 0f
        for ((i, wi) in w) {
            val m = deltas[i]
            a += wi * m.a; b += wi * m.b; c += wi * m.c; d += wi * m.d; tx += wi * m.tx; ty += wi * m.ty
        }
        return Affine2D(a, b, c, d, tx, ty)
    }

    /**
     * Позиция точки после скининга: sum(w_i * D_i(p)), где D_i - дельта позы кости (оси холста).
     * Без костей и без весов точка остаётся на месте.
     */
    fun skin(px: Float, py: Float, bones: List<BoneSeg>, deltas: List<Affine2D>): Pair<Float, Float> {
        val w = weights(px, py, bones)
        if (w.isEmpty()) return px to py
        var x = 0f
        var y = 0f
        for ((i, wi) in w) {
            x += wi * deltas[i].applyX(px, py)
            y += wi * deltas[i].applyY(px, py)
        }
        return x to y
    }
}
