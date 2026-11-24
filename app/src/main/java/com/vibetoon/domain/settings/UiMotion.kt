package com.vibetoon.domain.settings

/**
 * Спринт 31: правила анимаций интерфейса (панели, окна, диалоги) в стиле ToonSquid.
 * Длительности базовые: Compose сам умножает их на системный «Масштаб длительности анимации»
 * (Settings.Global.ANIMATOR_DURATION_SCALE). Здесь решается только, есть ли анимация вообще:
 * масштаб 0 («Отключить анимацию», «Убрать анимацию» в спецвозможностях) или выключенная настройка
 * приложения «Плавные анимации интерфейса» - панели и окна появляются мгновенно.
 */
object UiMotion {
    /** Выезд боковой панели (слои, инспектор) и нижних панелей. */
    const val PANEL_MS = 240
    /** Появление окна/диалога (прозрачность + масштаб). */
    const val DIALOG_MS = 180
    /** Исчезновение короче появления: интерфейс отзывается быстрее. */
    const val EXIT_MS = 160
    /** Плавающие строки инструментов над холстом. */
    const val TOOLBAR_MS = 200
    /** Начальный масштаб окна при появлении. */
    const val DIALOG_START_SCALE = 0.94f
    /** На сколько (доля размера) сдвигается плавающая строка инструментов при появлении. */
    const val TOOLBAR_OFFSET = 0.5f

    /** Масштаб из системных настроек: NaN/отрицательный (ошибка чтения) - 1, верхний предел 10 (как в Android). */
    fun normalizeScale(systemScale: Float): Float = when {
        systemScale.isNaN() || systemScale < 0f -> 1f
        else -> systemScale.coerceAtMost(10f)
    }

    /** Анимации включены: настройка приложения и ненулевой системный масштаб. */
    fun enabled(systemScale: Float, appEnabled: Boolean): Boolean = appEnabled && normalizeScale(systemScale) > 0f

    /** Длительность для показа (0 - мгновенно). Масштаб применяет Compose, поэтому здесь база или 0. */
    fun duration(baseMs: Int, systemScale: Float, appEnabled: Boolean): Int =
        if (enabled(systemScale, appEnabled)) baseMs.coerceAtLeast(0) else 0

    /** Сколько займёт анимация на экране с учётом системного масштаба (для тестов и метрик). */
    fun effectiveMs(baseMs: Int, systemScale: Float, appEnabled: Boolean): Int =
        if (!enabled(systemScale, appEnabled)) 0 else (baseMs * normalizeScale(systemScale)).toInt()

    /**
     * Кадров при частоте экрана [refreshHz]: анимация из одних свойств слоя (alpha/scale/translation) без
     * перекомпоновки - на 120 Гц те же 240 мс дают 29 кадров вместо 14 на 60 Гц.
     */
    fun frames(baseMs: Int, systemScale: Float, appEnabled: Boolean, refreshHz: Float): Int {
        val ms = effectiveMs(baseMs, systemScale, appEnabled)
        if (ms <= 0 || !(refreshHz > 0f)) return 0
        return (ms * refreshHz / 1000f).toInt()
    }
}
