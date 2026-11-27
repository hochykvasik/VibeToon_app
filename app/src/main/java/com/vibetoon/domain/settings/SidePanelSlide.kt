package com.vibetoon.domain.settings

/**
 * Спринт 57: выезд правой панели на планшете без «голого» серого поля и скачка таймлайна.
 *
 * Раньше панель жила в общей строке с холстом и таймлайном: при закрытии она уезжала, а её место в строке оставалось
 * пустым до конца анимации (сквозь него была видна серая GL-поверхность), потом место исчезало разом, и таймлайн
 * скачком растягивался. Теперь панель - выезжающий слой поверх края, а место в строке занято, только пока панель
 * открыта полностью:
 *  - закрытие: место освобождается сразу (таймлайн уже во всю ширину под панелью), панель уезжает поверх него;
 *  - открытие: панель въезжает поверх таймлайна, место занимается, когда она встала (под ней, невидимо).
 * Холст при этом отодвигается от края панели плавно, по тому же ходу [progress] (0 - закрыта, 1 - открыта), и не
 * перекомпоновывает таймлайн на каждом кадре.
 */
object SidePanelSlide {
    private const val EPS = 1e-3f

    /** Место панели в строке занято (таймлайн и холст сужены) - только у полностью открытой панели. */
    fun slotReserved(open: Boolean, progress: Float): Boolean = open && progress >= 1f - EPS

    /** Панель нарисована (въезжает, открыта или ещё уезжает). */
    fun composed(open: Boolean, progress: Float): Boolean = open || progress > EPS

    /** Сдвиг панели от её места, px: закрытая уходит за край экрана целиком (вправо или влево у левши). */
    fun offsetPx(progress: Float, widthPx: Int, atRight: Boolean): Int {
        val hidden = ((1f - progress.coerceIn(0f, 1f)) * widthPx.coerceAtLeast(0)).toInt()
        return if (atRight) hidden else -hidden
    }

    /**
     * Видимая область холста по горизонтали [start, end] (px окна): область между панелями, но не заходит под
     * выезжающую панель. [rowStart]/[rowEnd] - края строки (где край панели при progress = 0).
     */
    fun canvasSpan(colStart: Float, colEnd: Float, rowStart: Float, rowEnd: Float, progress: Float, widthPx: Int, atRight: Boolean): Pair<Float, Float> {
        if (rowEnd - rowStart < 1f) return colStart to colEnd   // строка ещё не измерена
        val shown = progress.coerceIn(0f, 1f) * widthPx.coerceAtLeast(0)
        return if (atRight) {
            val end = minOf(colEnd, rowEnd - shown)
            colStart to maxOf(colStart, end)
        } else {
            val start = maxOf(colStart, rowStart + shown)
            minOf(start, colEnd) to colEnd
        }
    }
}
