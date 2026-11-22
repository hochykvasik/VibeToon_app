package com.vibetoon.domain.render

import kotlin.math.min

/**
 * Камера холста. Экранная точка = C + (p - C) * zoom + pan, где C - центр вида,
 * p - положение точки при вписывании холста. Те же формулы в C++ (Renderer::viewTransform).
 */
data class Camera(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {

    /** Zoom вокруг точки (gx, gy) на dz раз плюс сдвиг (dx, dy); (cx, cy) - центр вида. */
    fun transformed(gx: Float, gy: Float, cx: Float, cy: Float, dz: Float, dx: Float, dy: Float): Camera {
        val z2 = (zoom * dz).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = z2 / zoom
        return Camera(
            zoom = z2,
            panX = gx + dx - cx - (gx - cx - panX) * k,
            panY = gy + dy - cy - (gy - cy - panY) * k
        )
    }

    companion object {
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 20f

        /** Масштаб вписывания холста в вид (поля 5%). */
        fun fitScale(viewW: Float, viewH: Float, canvasW: Int, canvasH: Int): Float =
            0.95f * min(viewW / canvasW, viewH / canvasH)
    }
}
