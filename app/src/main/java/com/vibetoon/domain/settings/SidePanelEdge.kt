package com.vibetoon.domain.settings

/**
 * Спринт 42: край экрана, у которого живёт правая панель (слои/инспектор), её свайп и стрелка-индикатор.
 * В обычном режиме панель справа; в режиме левши раскладка зеркалится и панель уезжает к левому краю -
 * зона свайпа, стрелка и направление жестов зеркалятся вместе с ней.
 */
object SidePanelEdge {
    /** Панель у правого края экрана (иначе - у левого, режим левши). */
    fun atRightEdge(leftHanded: Boolean): Boolean = !leftHanded

    /**
     * Свайп от края открывает панель, как только палец ушёл от края на [thresholdPx] (не дожидаясь отпускания):
     * от правого края - влево (сумма сдвигов отрицательная), от левого (левша) - вправо.
     */
    fun opens(dragSumPx: Float, thresholdPx: Float, leftHanded: Boolean): Boolean {
        if (!dragSumPx.isFinite() || !thresholdPx.isFinite() || thresholdPx <= 0f) return false
        return if (atRightEdge(leftHanded)) dragSumPx <= -thresholdPx else dragSumPx >= thresholdPx
    }

    /** Свайп по язычку открытой панели к её краю закрывает панель (зеркально [opens]). */
    fun closes(dragSumPx: Float, thresholdPx: Float, leftHanded: Boolean): Boolean = opens(-dragSumPx, thresholdPx, leftHanded)

    /**
     * Куда смотрит стрелка: закрытая панель - к центру экрана («<» у правого края, «>» у левого у левши),
     * открытая - к краю, куда панель уедет («>» справа, «<» у левши).
     */
    fun arrowPointsLeft(open: Boolean, leftHanded: Boolean): Boolean = (!open) == atRightEdge(leftHanded)

    /** Размер язычка-индикатора (dp): на телефоне меньше, чтобы не загромождать узкий экран. */
    fun hintWidthDp(phone: Boolean): Int = if (phone) 12 else 16
    fun hintHeightDp(phone: Boolean): Int = if (phone) 36 else 48

    /** Язычок открытой панели нужен только на планшете: на телефоне панель лежит поверх холста и закрывается тапом мимо неё. */
    fun showOpenTab(phone: Boolean, open: Boolean, fullscreen: Boolean): Boolean = !phone && open && !fullscreen

    /** Стрелка у края закрытой панели (в полном экране панели скрыты целиком - стрелки нет). */
    fun showClosedHint(open: Boolean, fullscreen: Boolean): Boolean = !open && !fullscreen
}
