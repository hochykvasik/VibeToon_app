package com.vibetoon.domain.input

/**
 * Автопрокрутка списка при перетаскивании у края (пакет 8): список слоёв справа и имена слоёв на таймлайне.
 * Палец в полосе [edgePx] у края окна (или за его пределами) прокручивает список со скоростью, растущей к краю,
 * до [maxStepPx] за тик (~16 мс). Чистые функции - проверяются unit-тестом.
 */
object EdgeAutoScroll {
    /**
     * Шаг прокрутки за тик: < 0 - вверх/влево, > 0 - вниз/вправо, 0 - палец не у края или дальше некуда.
     * [pos] - положение пальца в окне списка (0 - верхний край), [viewport] - высота окна.
     */
    fun velocity(pos: Float, viewport: Int, edgePx: Float, maxStepPx: Float, canBack: Boolean, canForward: Boolean): Float {
        if (viewport <= 0 || edgePx <= 0f || !pos.isFinite()) return 0f
        // короткое окно: полосы края не перекрываются
        val edge = minOf(edgePx, viewport / 3f)
        return when {
            pos < edge && canBack -> -((edge - pos) / edge).coerceIn(0f, 1f) * maxStepPx
            pos > viewport - edge && canForward -> ((pos - (viewport - edge)) / edge).coerceIn(0f, 1f) * maxStepPx
            else -> 0f
        }
    }

    /** Положение пальца в содержимом списка: строка начала, точка касания внутри строки и накопленный сдвиг. */
    fun contentPos(startIndex: Int, rowPx: Float, downInRow: Float, dragDy: Float): Float = startIndex * rowPx + downInRow + dragDy

    /** Строка, над которой окажется перетаскиваемый слой (в пределах списка). */
    fun targetIndex(startIndex: Int, dragDy: Float, rowPx: Float, count: Int): Int {
        if (count <= 0 || rowPx <= 0f) return startIndex
        return (startIndex + Math.round(dragDy / rowPx)).coerceIn(0, count - 1)
    }
}
