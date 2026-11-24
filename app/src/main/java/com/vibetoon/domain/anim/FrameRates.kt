package com.vibetoon.domain.anim

import kotlin.math.roundToInt

/**
 * Спринт 32: частота кадров 1–120 во всём приложении - проект, таймлайн, воспроизведение и экспорт.
 * Одно место с пределами и пресетами, чтобы диалоги, импорт и файлы не расходились.
 */
object FrameRates {
    const val MIN = 1
    const val MAX = TimeFormat.MAX_FPS

    /** Пресеты проекта и таймлайна (поле ввода принимает любое значение 1–120). */
    val PROJECT_PRESETS = listOf(8, 12, 15, 24, 25, 30, 48, 50, 60, 90, 120)
    /** Пресеты экспорта видео, MOV и PNG-последовательности; частота проекта добавляется отдельно ([exportOptions]). */
    val EXPORT_PRESETS = listOf(12, 15, 24, 25, 30, 48, 50, 60, 90, 120)

    /**
     * GIF хранит задержку кадра в сотых долях секунды, и браузеры заменяют задержку меньше 2 сотых на 10 сотых:
     * кадры чаще 50 fps в GIF не воспроизводятся честно.
     */
    const val GIF_MAX = 50
    val GIF_PRESETS = listOf(8, 10, 12, 15, 20, 24, 25, 30, 50)

    fun clamp(fps: Int): Int = fps.coerceIn(MIN, MAX)

    /** Значение поля ввода: null, если это не число 1–120 (тогда кнопка «Применить» недоступна). */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN..MAX }

    /** Варианты частоты в экспорте: пресеты плюс частота проекта (по возрастанию, без повторов). */
    fun exportOptions(projectFps: Int, presets: List<Int> = EXPORT_PRESETS): List<Int> =
        (presets + clamp(projectFps)).filter { it in MIN..MAX }.distinct().sorted()

    /** Варианты GIF: частота проекта добавляется, только если GIF умеет её показать. */
    fun gifOptions(projectFps: Int): List<Int> =
        (GIF_PRESETS + projectFps).filter { it in MIN..GIF_MAX }.distinct().sorted()

    /** Частота по умолчанию в окне экспорта: как в проекте (для GIF - не выше [GIF_MAX]). */
    fun defaultExport(projectFps: Int, gif: Boolean = false): Int = if (gif) clamp(projectFps).coerceAtMost(GIF_MAX) else clamp(projectFps)

    /**
     * Задержка [index]-го кадра GIF в сотых долях секунды. Ошибка округления не накапливается: начало кадра i -
     * round(i*100/fps), поэтому 24 fps дают 4,4,5,4,4,4,... и секунда анимации длится ровно секунду.
     */
    fun gifDelayCs(index: Int, fps: Int): Int {
        val f = fps.coerceIn(MIN, GIF_MAX)
        fun start(i: Int) = (i.toLong() * 100 + f / 2) / f
        return (start(index + 1) - start(index)).toInt().coerceAtLeast(2)
    }

    /**
     * Сколько кадров анимации экран реально покажет за секунду при частоте дисплея [refreshHz]. При fps выше
     * частоты экрана воспроизведение идёт по времени (PlaybackMath) и пропускает кадры; экспорт сохраняет все.
     */
    fun shownPerSecond(fps: Int, refreshHz: Float): Int {
        val hz = if (refreshHz.isFinite() && refreshHz >= 1f) refreshHz.roundToInt() else 60
        return minOf(clamp(fps), hz)
    }

    /** true - на этом экране часть кадров при просмотре будет пропущена (показываем подсказку). */
    fun exceedsDisplay(fps: Int, refreshHz: Float): Boolean = shownPerSecond(fps, refreshHz) < clamp(fps)
}
