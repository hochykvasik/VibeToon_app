package com.vibetoon.domain.render

/**
 * Скользящее окно времени кадра (мс) для метрик производительности: среднее, максимум и 95-й перцентиль.
 * Без аллокаций на каждый замер (кольцевой буфер). Не потокобезопасно: один поток пишет и читает.
 */
class FrameTimer(private val window: Int = 120) {
    private val buf = FloatArray(window.coerceAtLeast(1))
    private var n = 0
    private var pos = 0

    fun add(ms: Float) {
        if (!ms.isFinite() || ms < 0f) return
        buf[pos] = ms
        pos = (pos + 1) % buf.size
        if (n < buf.size) n++
    }

    val count: Int get() = n
    fun clear() { n = 0; pos = 0 }

    fun average(): Float = if (n == 0) 0f else (0 until n).sumOf { buf[it].toDouble() }.toFloat() / n
    fun max(): Float = if (n == 0) 0f else (0 until n).maxOf { buf[it] }

    /** Перцентиль [p] (0..1) по текущему окну. */
    fun percentile(p: Float): Float {
        if (n == 0) return 0f
        val s = buf.copyOf(n).also { it.sort() }
        val i = ((n - 1) * p.coerceIn(0f, 1f)).toInt()
        return s[i]
    }

    /** Доля кадров дольше бюджета [budgetMs] (например 1000/fps) - «пропущенные» кадры. */
    fun overBudget(budgetMs: Float): Float = if (n == 0) 0f else (0 until n).count { buf[it] > budgetMs }.toFloat() / n
}
