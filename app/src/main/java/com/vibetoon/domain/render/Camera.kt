package com.vibetoon.domain.render

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Камера холста. Экранная точка = C + A * (zoom * (fit * p + f - C)) + pan, где C - центр вида,
 * p - точка холста, fit - вписывание, f - левый верх холста при вписывании,
 * A = R(rotDeg) * F(flipX, flipY) - поворот и отражение вида (только вид, экспорт не меняется).
 * Те же формулы в C++ (Renderer::viewAffine).
 */
data class Camera(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val rotDeg: Float = 0f,
    val flipX: Boolean = false,
    val flipY: Boolean = false
) {
    /** Zoom вокруг точки (gx, gy) на dz раз, поворот на rotDeg вокруг той же точки и сдвиг (dx, dy); (cx, cy) - центр вида. */
    fun transformed(gx: Float, gy: Float, cx: Float, cy: Float, dz: Float, dx: Float, dy: Float, rotDeg: Float = 0f): Camera {
        val z2 = (zoom * dz).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = z2 / zoom
        val rad = Math.toRadians(rotDeg.toDouble())
        val c = kotlin.math.cos(rad).toFloat(); val sn = kotlin.math.sin(rad).toFloat()
        // Точка жеста остаётся под пальцами: поворачиваем вектор от центра вида и масштабируем его.
        val rx = gx - cx - panX; val ry = gy - cy - panY
        val qx = (rx * c - ry * sn) * k; val qy = (rx * sn + ry * c) * k
        return copy(
            zoom = z2,
            rotDeg = this.rotDeg + rotDeg,
            panX = gx + dx - cx - qx,
            panY = gy + dy - cy - qy
        )
    }

    /** Центрирует точку холста, не меняя масштаб/поворот. Неверная геометрия не меняет вид. */
    fun follow(x: Float, y: Float, viewW: Float, viewH: Float, canvasW: Int, canvasH: Int): Camera {
        if (!x.isFinite() || !y.isFinite() || !viewW.isFinite() || !viewH.isFinite() ||
            viewW <= 0f || viewH <= 0f || canvasW <= 0 || canvasH <= 0 || !zoom.isFinite() || zoom <= 0f) return this
        val (sx, sy) = toScreen(x, y, viewW, viewH, canvasW, canvasH)
        // Сдвигаем панораму так, чтобы точка оказалась в центре вида.
        val px = panX + (viewW / 2f - sx)
        val py = panY + (viewH / 2f - sy)
        return if (px.isFinite() && py.isFinite()) copy(panX = px, panY = py) else this
    }

    /** Точка холста -> экран. */
    fun toScreen(x: Float, y: Float, viewW: Float, viewH: Float, canvasW: Int, canvasH: Int): Pair<Float, Float> {
        val fit = fitScale(viewW, viewH, canvasW, canvasH)
        val fx = (viewW - canvasW * fit) / 2f
        val fy = (viewH - canvasH * fit) / 2f
        val cx = viewW / 2f
        val cy = viewH / 2f
        val qx = zoom * (fit * x + fx - cx)
        val qy = zoom * (fit * y + fy - cy)
        val (a00, a01, a10, a11) = rotFlip()
        return (cx + a00 * qx + a01 * qy + panX) to (cy + a10 * qx + a11 * qy + panY)
    }

    /** Экранная точка -> холст (обратное к [toScreen]). */
    fun toCanvas(sx: Float, sy: Float, viewW: Float, viewH: Float, canvasW: Int, canvasH: Int): Pair<Float, Float> {
        // Неверный ввод/геометрия: точка заведомо вне холста, без NaN (касание ничего не задевает).
        if (!sx.isFinite() || !sy.isFinite() || !viewW.isFinite() || !viewH.isFinite() || viewW <= 0f || viewH <= 0f ||
            canvasW <= 0 || canvasH <= 0 || !zoom.isFinite() || zoom <= 0f || !panX.isFinite() || !panY.isFinite()) return OUTSIDE
        val fit = fitScale(viewW, viewH, canvasW, canvasH)
        val fx = (viewW - canvasW * fit) / 2f
        val fy = (viewH - canvasH * fit) / 2f
        val cx = viewW / 2f
        val cy = viewH / 2f
        val dx = sx - cx - panX
        val dy = sy - cy - panY
        // A^-1 = [[sx*c, sx*s], [-sy*s, sy*c]]
        val th = Math.toRadians(rotDeg.toDouble())
        val c = cos(th).toFloat()
        val s = sin(th).toFloat()
        val fx_ = if (flipX) -1f else 1f
        val fy_ = if (flipY) -1f else 1f
        val qx = (fx_ * c * dx + fx_ * s * dy) / zoom
        val qy = (-fy_ * s * dx + fy_ * c * dy) / zoom
        return ((qx + cx - fx) / fit) to ((qy + cy - fy) / fit)
    }

    /** Матрица A = R * F (поворот на rotDeg по часовой стрелке на экране, затем отражение). */
    private fun rotFlip(): FloatArray {
        val th = Math.toRadians(rotDeg.toDouble())
        val c = cos(th).toFloat()
        val s = sin(th).toFloat()
        val sx = if (flipX) -1f else 1f
        val sy = if (flipY) -1f else 1f
        return floatArrayOf(c * sx, -s * sy, s * sx, c * sy)
    }

    /** Прилипание угла к 0/90/180/270 в пределах [threshold] градусов; иначе как есть. */
    fun snappedRot(threshold: Float = SNAP_DEG): Float {
        val norm = ((rotDeg % 360f) + 360f) % 360f
        val cardinals = floatArrayOf(0f, 90f, 180f, 270f)
        for (card in cardinals) {
            var d = kotlin.math.abs(norm - card)
            if (d > 180f) d = 360f - d
            if (d <= threshold) return card
        }
        return rotDeg
    }

    companion object {
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 20f
        const val SNAP_DEG = 6f
        private val OUTSIDE = -1f to -1f

        /** Масштаб вписывания холста в вид (поля 5%). */
        fun fitScale(viewW: Float, viewH: Float, canvasW: Int, canvasH: Int): Float =
            0.95f * min(viewW / canvasW, viewH / canvasH)
    }
}
