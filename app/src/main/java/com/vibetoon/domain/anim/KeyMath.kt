package com.vibetoon.domain.anim

import kotlin.math.roundToInt

/** Массовые операции над ключами: растягивание/сжатие во времени и реверс. */
object KeyMath {
    /** Кадр после растягивания относительно [anchor] с коэффициентом [factor] (>1 - длиннее, <1 - короче). */
    fun stretchFrame(f: Int, anchor: Int, factor: Float): Int = anchor + ((f - anchor) * factor).roundToInt()

    /**
     * Реверс ключей одной дорожки: значения идут в обратном порядке, кадры зеркалятся внутри диапазона [первый, последний].
     * Плавность сегмента хранится у левого ключа, поэтому после реверса она переезжает вместе с сегментом (сегмент A->B становится B->A).
     */
    fun reverse(sel: List<Keyframe>): List<Keyframe> {
        val ks = sel.sortedBy { it.frame }
        val n = ks.size
        if (n < 2) return ks
        val lo = ks.first().frame; val hi = ks.last().frame
        return List(n) { i ->
            val src = ks[n - 1 - i]
            val seg = if (i < n - 1) ks[n - 2 - i] else ks[0]
            src.copy(frame = lo + hi - src.frame, easing = seg.easing, curve = seg.curve)
        }
    }
}
