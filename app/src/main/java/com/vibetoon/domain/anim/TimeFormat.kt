package com.vibetoon.domain.anim

/** Формат времени ММ:СС:КК (минуты:секунды:кадры), как в Alight Motion. */
object TimeFormat {
    /**
     * Спринт 41: предел длительности - 72 000 кадров (10 минут при 120 fps, 50 минут при 24 fps) вместо 5000
     * (раньше при 120 fps это было всего ~41 с). Предел держится в кадрах: таймлайн, ключи и экспорт работают с номерами
     * кадров; ширина полосы таймлайна ограничивается отдельно ([TimelineZoom]).
     */
    const val MAX_DURATION_FRAMES = 72_000
    const val MAX_FPS = 120

    /** Предел длительности в секундах при частоте [fps] (для подписи «до ММ:СС»). */
    fun maxSeconds(fps: Int): Int = MAX_DURATION_FRAMES / fps.coerceIn(1, MAX_FPS)

    /** Длительность «ММ:СС» (минуты не ограничены двумя цифрами: 75:00). */
    fun formatDuration(frames: Int, fps: Int): String {
        val sec = frames.coerceAtLeast(0) / fps.coerceAtLeast(1)
        return "${two(sec / 60)}:${two(sec % 60)}"
    }

    /**
     * Ввод длительности временем: «ММ:СС» или «ММ:СС:КК» (три части - всегда минуты, секунды, кадры); одно число -
     * секунды. Секунды 0–59, кадры меньше [fps]; точка и запятая принимаются как «:». Возвращает число кадров
     * (не меньше 1) или null, если ввод не разобран. Предел длительности проверяет вызывающий.
     */
    fun parseDuration(text: String, fps: Int): Int? {
        val fp = fps.coerceAtLeast(1)
        val parts = text.trim().replace('.', ':').replace(',', ':').split(':')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || it.length > 5 || !it.all(Char::isDigit) }) return null
        val n = parts.map { it.toLong() }
        val frames = when (n.size) {
            1 -> n[0] * fp
            2 -> if (n[1] > 59) return null else (n[0] * 60 + n[1]) * fp
            else -> if (n[1] > 59 || n[2] >= fp) return null else (n[0] * 60 + n[1]) * fp + n[2]
        }
        if (frames < 1 || frames > Int.MAX_VALUE) return null
        return frames.toInt()
    }

    /** Текст поля длительности: «ММ:СС», если длительность - целые секунды, иначе «ММ:СС:КК». */
    fun durationText(frames: Int, fps: Int): String =
        if (frames % fps.coerceAtLeast(1) == 0) formatDuration(frames, fps) else format(frames, fps)

    /** Смена частоты с сохранением длительности по времени (а не по числу кадров). */
    fun reframe(frames: Int, fromFps: Int, toFps: Int): Int {
        val f = fromFps.coerceAtLeast(1); val t = toFps.coerceAtLeast(1)
        return ((frames.toLong() * t + f / 2) / f).toInt().coerceIn(1, MAX_DURATION_FRAMES)
    }

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

/**
 * Спринт 41: масштаб таймлайна для длинных проектов. Полоса кадров - один элемент Compose шириной frameDp × кадры, а
 * Constraints Compose не представляют размер больше 262 143 px. Поэтому наибольший масштаб (dp на кадр) уменьшается с
 * длиной проекта, а наименьший при необходимости опускается ниже обычного, чтобы ширина всегда помещалась.
 */
object TimelineZoom {
    /** Безопасная ширина полосы в пикселях (с запасом до предела Compose 2^18 - 1). */
    const val MAX_CONTENT_PX = 240_000f

    /** Наибольший dp на кадр для [frames] кадров при плотности [density] px/dp; не больше [cap]. */
    fun maxFrameDp(frames: Int, density: Float, cap: Float): Float {
        val d = if (density.isFinite() && density > 0f) density else 1f
        return minOf(cap, MAX_CONTENT_PX / frames.coerceAtLeast(1) / d)
    }

    /** Наименьший dp на кадр: обычный [floor], но не больше наибольшего (длинный проект). */
    fun minFrameDp(frames: Int, density: Float, floor: Float, cap: Float): Float = minOf(floor, maxFrameDp(frames, density, cap))

    /** Масштаб [dp], приведённый к допустимому диапазону для проекта. */
    fun clamp(dp: Float, frames: Int, density: Float, floor: Float, cap: Float): Float {
        val hi = maxFrameDp(frames, density, cap)
        val lo = minOf(floor, hi)
        return if (dp.isFinite()) dp.coerceIn(lo, hi) else lo
    }
}
