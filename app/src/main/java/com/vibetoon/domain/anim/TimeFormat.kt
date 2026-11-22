package com.vibetoon.domain.anim

/** Формат времени ММ:СС:КК (минуты:секунды:кадры), как в Alight Motion. */
object TimeFormat {
    const val MAX_DURATION_FRAMES = 5000
    const val MAX_FPS = 120

    private fun two(v: Int) = v.toString().padStart(2, '0')

    fun format(frame: Int, fps: Int): String {
        val fp = fps.coerceAtLeast(1)
        val f = frame.coerceAtLeast(0)
        val sec = f / fp
        return "${two(sec / 60)}:${two(sec % 60)}:${two(f % fp)}"
    }

    /** Только минуты и секунды (подписи линейки). */
    fun formatSeconds(frame: Int, fps: Int): String {
        val sec = frame.coerceAtLeast(0) / fps.coerceAtLeast(1)
        return "${two(sec / 60)}:${two(sec % 60)}"
    }

    /**
     * Ввод цифр "как на калькуляторе": цифры выравниваются вправо по шаблону ММССКК
     * ("106" -> 00:01:06). Возвращает номер кадра; кадры >= fps переносятся в секунды.
     */
    fun parseDigits(digits: String, fps: Int): Int {
        val d = digits.filter { it.isDigit() }.takeLast(6).padStart(6, '0')
        val min = d.substring(0, 2).toInt()
        val sec = d.substring(2, 4).toInt()
        val ff = d.substring(4, 6).toInt()
        return (min * 60 + sec) * fps.coerceAtLeast(1) + ff
    }
}
