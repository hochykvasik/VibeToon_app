package com.vibetoon.domain.anim

import kotlin.math.abs

/** Магнит таймлайна: прилипание кадра к ближайшей цели (ключ, маркер, граница клипа) в пределах порога. */
object SnapMath {
    /** Лесенка: старты клипов встык (Start_i = End_{i-1}); первый стартует с [first]. */
    fun chainStarts(first: Int, lengths: List<Int>): List<Int> {
        val out = ArrayList<Int>(lengths.size)
        var at = first
        for (len in lengths) { out += at; at += len }
        return out
    }

    /** Ближайшая цель не дальше [threshold] кадров от [frame]; иначе сам кадр. При равном расстоянии - левая цель. */
    fun snap(frame: Int, targets: Collection<Int>, threshold: Int): Int {
        if (threshold <= 0) return frame
        var best = frame
        var bestD = Int.MAX_VALUE
        for (t in targets) {
            val d = abs(t - frame)
            if (d <= threshold && (d < bestD || (d == bestD && t < best))) { best = t; bestD = d }
        }
        return best
    }
}
