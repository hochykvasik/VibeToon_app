package com.vibetoon.domain.project

/**
 * Изменение холста после создания проекта: одна линейная карта точек (x - ox) * sx, (y - oy) * sy.
 * Resize: ox = oy = 0, sx = новая ширина / старая, sy аналогично. Crop: ox, oy - левый верхний угол рамки, sx = sy = 1.
 * Смещения трансформаций (dx, dy в пикселях) масштабируются без сдвига начала.
 */
class CanvasMap(val ox: Float, val oy: Float, val sx: Float, val sy: Float) {
    fun x(v: Float) = (v - ox) * sx
    fun y(v: Float) = (v - oy) * sy
    fun w(v: Float) = v * sx
    fun h(v: Float) = v * sy
    fun dx(v: Float) = v * sx
    fun dy(v: Float) = v * sy
    /** Размер текста: масштабируется по меньшему коэффициенту, чтобы надпись не выросла за новые границы. */
    fun text(v: Float) = v * minOf(sx, sy)

    companion object {
        fun resize(oldW: Int, oldH: Int, newW: Int, newH: Int) = CanvasMap(0f, 0f, newW.toFloat() / oldW, newH.toFloat() / oldH)
        fun crop(x: Int, y: Int) = CanvasMap(x.toFloat(), y.toFloat(), 1f, 1f)

        /** Рамка обрезки внутри холста; null - рамка выходит за границы или слишком мала. */
        fun validCrop(oldW: Int, oldH: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
            x >= 0 && y >= 0 && w >= ProjectMath.MIN_SIZE && h >= ProjectMath.MIN_SIZE && x + w <= oldW && y + h <= oldH

        /** Новая высота при сохранении пропорций. */
        fun keepRatio(oldW: Int, oldH: Int, newW: Int): Int = ProjectMath.clampSize(Math.round(newW.toFloat() * oldH / oldW))
    }
}
