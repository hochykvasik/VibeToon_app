package com.vibetoon.domain.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Спринт 56: плавающее окно референса (как в ibisPaint). Картинка-образец поверх редактора: её можно двигать за
 * шапку, менять размер за уголок, сворачивать в полоску и закрывать. Это только окно интерфейса - в сцену, кадр,
 * проект и экспорт картинка не попадает. Здесь чистая геометрия в dp (рамка всегда внутри области редактора).
 */
object ReferenceWindow {
    /** Высота шапки (перетаскивание, кнопки) - она же вся высота свёрнутого окна. */
    const val BAR = 32f
    const val MIN_W = 140f
    /** Окно не шире этой доли области редактора. */
    const val MAX_FRACTION = 0.6f
    const val DEFAULT_W = 260f
    const val MARGIN = 8f
    /** Длинная сторона картинки после загрузки, px: окно маленькое, память не тратим. */
    const val MAX_SIDE_PX = 1024

    data class Frame(val x: Float, val y: Float, val w: Float, val collapsed: Boolean = false)

    private fun aspectOf(aspect: Float) = if (aspect.isFinite() && aspect > 0.05f) aspect.coerceAtMost(20f) else 1f

    private fun maxW(areaW: Float) = max(MIN_W, areaW * MAX_FRACTION)

    /** Размер окна (ширина, высота) с шапкой: картинка вписана по ширине, высота не больше области. */
    fun size(f: Frame, aspect: Float, areaW: Float, areaH: Float): Pair<Float, Float> {
        val w = f.w.coerceIn(MIN_W, maxW(areaW))
        if (f.collapsed) return w to BAR
        val imgH = w / aspectOf(aspect)
        val h = min(BAR + imgH, max(BAR + 40f, areaH - 2 * MARGIN))
        return w to h
    }

    /** Окно целиком внутри области (если область меньше окна - прижато к левому верхнему углу). */
    fun clamp(f: Frame, aspect: Float, areaW: Float, areaH: Float): Frame {
        val w = f.w.coerceIn(MIN_W, maxW(areaW))
        val (_, h) = size(f.copy(w = w), aspect, areaW, areaH)
        val x = f.x.coerceIn(0f, max(0f, areaW - w))
        val y = f.y.coerceIn(0f, max(0f, areaH - h))
        return f.copy(x = x, y = y, w = w)
    }

    /** Первое появление: справа сверху, с отступом. */
    fun initial(aspect: Float, areaW: Float, areaH: Float): Frame {
        val w = DEFAULT_W.coerceIn(MIN_W, maxW(areaW))
        return clamp(Frame(areaW - w - MARGIN, 56f, w), aspect, areaW, areaH)
    }

    fun moveBy(f: Frame, dx: Float, dy: Float, aspect: Float, areaW: Float, areaH: Float): Frame =
        clamp(f.copy(x = f.x + dx, y = f.y + dy), aspect, areaW, areaH)

    /** Уголок справа снизу: ширина меняется, пропорции картинки сохраняются, окно не вылезает за область. */
    fun resizeBy(f: Frame, dw: Float, aspect: Float, areaW: Float, areaH: Float): Frame {
        if (f.collapsed) return f
        val roomW = max(MIN_W, areaW - f.x)
        val w = (f.w + dw).coerceIn(MIN_W, min(maxW(areaW), roomW))
        return clamp(f.copy(w = w), aspect, areaW, areaH)
    }

    fun toggleCollapsed(f: Frame, aspect: Float, areaW: Float, areaH: Float): Frame =
        clamp(f.copy(collapsed = !f.collapsed), aspect, areaW, areaH)

    /** Шаг прореживания при декодировании (степень двойки), чтобы длинная сторона была не больше [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE_PX): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var s = 1
        while (max(width, height) / (s * 2) >= maxSide) s *= 2
        return s
    }
}
