package com.vibetoon.domain.input

/** Настройки ограничивают только палец и визуальное/слоёвое взаимодействие; перо и остальные инструменты не меняют. */
object EditorInputPolicy {
    fun canStartStroke(isTouch: Boolean, fingerDrawing: Boolean): Boolean = !isTouch || fingerDrawing
    fun canSelectCanvasLayer(selectLayersOnCanvas: Boolean): Boolean = selectLayersOnCanvas
    /** Quick mode goes straight to the hit leaf instead of stepping through its ancestor groups. */
    fun tapTarget(chain: List<Int>, current: Int?, quick: Boolean): Int? {
        if (chain.isEmpty()) return null
        if (quick) return chain.last()
        val i = if (current == null) -1 else chain.indexOf(current)
        return if (i < 0) chain.first() else chain[minOf(i + 1, chain.lastIndex)]
    }
    /** До порога движения мультитап не должен сдвигать камеру/слой и создавать ключи. */
    fun transformGestureStarted(maxDistance: Float, touchSlop: Float): Boolean = maxDistance > touchSlop
    fun cursorRadius(brushSize: Float, screenWidth: Float, layerWidth: Int): Float =
        (brushSize / 2f * (screenWidth / layerWidth.coerceAtLeast(1))).coerceAtLeast(2f)
    fun showCursor(cursorEnabled: Boolean): Boolean = cursorEnabled

    /**
     * Спринт 44: защита от ладони для рисующих инструментов (кисть, ластик, растровые): касание пальцем не начинает
     * штрих, пока перо касается экрана и [StylusTouchFilter.GUARD_MS] после его подъёма - тот же интервал, что у
     * настройки «Определение касаний». Время назад (смена часов не бывает у uptime, но [last] может быть 0) - не блок.
     */
    fun palmBlocked(penDown: Boolean, lastPenMillis: Long, nowMillis: Long): Boolean =
        penDown || (lastPenMillis > 0L && nowMillis >= lastPenMillis && nowMillis - lastPenMillis < StylusTouchFilter.GUARD_MS)

    /** Индикатор нажима обновляется при первом значении штриха или заметном (≥ 0.5 %) изменении. */
    fun hudChanged(prevRaw: Float?, prevMapped: Float?, raw: Float, mapped: Float): Boolean =
        prevRaw == null || prevMapped == null || kotlin.math.abs(prevRaw - raw) >= HUD_STEP || kotlin.math.abs(prevMapped - mapped) >= HUD_STEP

    const val HUD_STEP = 0.005f

    /** Угол индикатора нажима: напротив рисующей руки (правша - слева сверху, левша - справа сверху). */
    fun hudAtStart(leftHanded: Boolean): Boolean = !leftHanded
}
