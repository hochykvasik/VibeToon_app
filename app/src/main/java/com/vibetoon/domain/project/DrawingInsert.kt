package com.vibetoon.domain.project

/**
 * Пакет 17: Import → Insert Images into Existing Drawing. Картинка вписывается в рисунок выбранного слоя-картинки
 * (на плейхеде) с сохранением пропорций и по центру, вместо создания нового слоя.
 */
object DrawingInsert {
    /** Прямоугольник (left, top, right, bottom) в пикселях рисунка [dw]×[dh] для картинки [sw]×[sh]: вписать и центрировать. */
    fun fitRect(sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return floatArrayOf(0f, 0f, dw.coerceAtLeast(0).toFloat(), dh.coerceAtLeast(0).toFloat())
        val k = minOf(dw.toFloat() / sw, dh.toFloat() / sh)
        val w = sw * k; val h = sh * k
        val l = (dw - w) / 2f; val t = (dh - h) / 2f
        return floatArrayOf(l, t, l + w, t + h)
    }

    /** Подходит ли слой: картинка (не вектор, не текст, не группа), не заблокирован. */
    fun accepts(kind: MediaKind, vector: Boolean, locked: Boolean): Boolean = kind == MediaKind.IMAGE && !vector && !locked
}
