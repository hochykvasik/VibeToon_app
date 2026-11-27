package com.vibetoon.domain.tutorial

/**
 * Спринт 53: где показать карточку подсказки урока. Карточка занимает низ экрана; если подсвеченная кнопка тоже внизу
 * (таймлайн, строка рисунков покадровой анимации), карточка закрыла бы её - тогда она уходит наверх.
 */
object CoachPlacement {
    /** Доля высоты, ниже которой цель считается «внизу». */
    const val LOWER_FRACTION = 0.5f

    /** true - карточку ставить сверху: центр цели [targetCenterY] ниже середины области высотой [viewportHeight]. */
    fun cardOnTop(targetCenterY: Float, viewportHeight: Float): Boolean =
        viewportHeight > 0f && targetCenterY.isFinite() && targetCenterY > viewportHeight * LOWER_FRACTION
}
