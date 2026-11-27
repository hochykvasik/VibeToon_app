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
        // спринт 61: нечисловые координаты (область ещё не измерена при повороте, испорченная рамка) раньше проходили
        // coerceIn как NaN - окно исчезало с экрана, и вернуть его было нельзя; теперь - угол области и ширина по умолчанию
        val aw = if (areaW.isFinite()) max(0f, areaW) else 0f
        val ah = if (areaH.isFinite()) max(0f, areaH) else 0f
        val w = (if (f.w.isFinite()) f.w else DEFAULT_W).coerceIn(MIN_W, maxW(aw))
        val (_, h) = size(f.copy(w = w), aspect, aw, ah)
        val x = (if (f.x.isFinite()) f.x else 0f).coerceIn(0f, max(0f, aw - w))
        val y = (if (f.y.isFinite()) f.y else 0f).coerceIn(0f, max(0f, ah - h))
        return f.copy(x = x, y = y, w = w)
    }

    /** Верх окна при первом появлении: под верхней строкой (44 dp) и строкой режимов (38 dp), чтобы не закрывать их кнопки. */
    const val TOP_INSET = 90f

    /**
     * Первое появление: сверху у края, противоположного колонке инструментов - справа, а в режиме левши (инструменты
     * справа) - слева (спринт 60). Ниже верхней строки и строки режимов ([topInset]); всегда внутри области.
     */
    fun initial(aspect: Float, areaW: Float, areaH: Float, leftHanded: Boolean = false, topInset: Float = TOP_INSET): Frame {
        val w = DEFAULT_W.coerceIn(MIN_W, maxW(areaW))
        val x = if (leftHanded) MARGIN else areaW - w - MARGIN
        return clamp(Frame(x, if (topInset.isFinite()) max(0f, topInset) else TOP_INSET, w), aspect, areaW, areaH)
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
