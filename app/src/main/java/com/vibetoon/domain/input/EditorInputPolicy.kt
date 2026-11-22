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
}
