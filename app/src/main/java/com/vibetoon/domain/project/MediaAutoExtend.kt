package com.vibetoon.domain.project

import com.vibetoon.domain.anim.TimeFormat

/**
 * Спринт 41: автопродление проекта под длину добавленного медиа. Раньше проект продлевался только под аудио (MP3);
 * теперь то же правило действует для видеоклипа (слой «Видео», вставка из библиотеки, видео как символ). Настройка
 * «Продлевать проект под длину аудио и видео» (по умолчанию вкл.) выключает продление для обоих видов.
 */
object MediaAutoExtend {
    const val PREF = "auto_extend_media"

    /** Решение: новая длительность (кадры) и обрезан ли конец медиа пределом длительности. */
    data class Plan(val frames: Int, val clipped: Boolean)

    /** Кадров, чтобы медиа длиной [durationMs] проиграло целиком при [fps] (последний неполный кадр включается). */
    fun framesFor(durationMs: Long, fps: Int): Int {
        if (durationMs <= 0L) return 0
        val f = fps.coerceIn(1, TimeFormat.MAX_FPS).toLong()
        return ((durationMs * f + 999L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** null - продлевать не нужно (выключено, длина неизвестна или проект уже не короче медиа). */
    fun plan(currentFrames: Int, durationMs: Long, fps: Int, enabled: Boolean): Plan? = planFrames(currentFrames, framesFor(durationMs, fps), enabled)

    /** То же по готовому числу кадров (видео как символ: число кадров известно из плана декодирования). */
    fun planFrames(currentFrames: Int, needFrames: Int, enabled: Boolean): Plan? {
        if (!enabled || needFrames <= currentFrames) return null
        val d = needFrames.coerceAtMost(TimeFormat.MAX_DURATION_FRAMES)
        if (d <= currentFrames) return null
        return Plan(d, needFrames > d)
    }
}
