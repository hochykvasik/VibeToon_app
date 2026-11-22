package com.vibetoon.domain.render

import com.vibetoon.domain.model.Affine2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Камера вида редактора (не путать со слоем-камерой проекта):
 *   экран = (центр вида + pan) + k * L * (p - центр холста),  k = вписывание * zoom,
 *   L = R(rotation) * diag(flipX ? -1 : 1, flipY ? -1 : 1).
 * Поворот [rotation] (радианы, по часовой на экране) и отражения нужны только для удобства рисования:
 * экспорт рисуется без них. Те же формулы в C++ (render/ViewMath.h, makeView).
 */
data class Camera(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val rotation: Float = 0f,
    val flipX: Boolean = false,
    val flipY: Boolean = false
) {
    /** Матрица холст -> экран для вида [viewW] x [viewH] и холста [canvasW] x [canvasH]. */
    fun viewMatrix(viewW: Float, viewH: Float, canvasW: Int, canvasH: Int): Affine2D {
        val k = fitScale(viewW, viewH, canvasW.coerceAtLeast(1), canvasH.coerceAtLeast(1)).coerceAtLeast(1e-4f) * zoom
        val cs = snap(cos(rotation)); val sn = snap(sin(rotation))
        val fx = if (flipX) -1f else 1f; val fy = if (flipY) -1f else 1f
        val la = cs * fx; val lb = sn * fx; val lc = -sn * fy; val ld = cs * fy
        val ccx = canvasW / 2f; val ccy = canvasH / 2f
        return Affine2D(
            k * la, k * lb, k * lc, k * ld,
            viewW / 2f + panX - k * (la * ccx + lc * ccy),
            viewH / 2f + panY - k * (lb * ccx + ld * ccy)
        )
    }

    /** Знак поворота экран -> холст: при одном отражении жест «по часовой» поворачивает слой против часовой в осях холста. */
    val handedness: Float get() = if (flipX != flipY) -1f else 1f

    /** Поворот вида в градусах, приведённый к (-180, 180]. */
    val rotationDeg: Float get() = normalizeDeg(Math.toDegrees(rotation.toDouble()).toFloat())

    /**
     * Жест двумя пальцами: zoom в [dz] раз и поворот на [drDeg] градусов вокруг точки жеста (gx, gy) плюс сдвиг (dx, dy).
     * Точка холста под пальцами остаётся под пальцами. Отражения не меняются.
     */
    fun transformed(
        gx: Float, gy: Float, viewW: Float, viewH: Float, canvasW: Int, canvasH: Int,
        dz: Float, dx: Float, dy: Float, drDeg: Float = 0f
    ): Camera {
        val inv = viewMatrix(viewW, viewH, canvasW, canvasH).inverse() ?: return this
        val px = inv.applyX(gx, gy); val py = inv.applyY(gx, gy)   // точка холста под пальцами
        val z2 = (zoom * dz).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val r2 = rotation + Math.toRadians(drDeg.toDouble()).toFloat()
        val moved = copy(zoom = z2, rotation = r2, panX = 0f, panY = 0f)
        val m = moved.viewMatrix(viewW, viewH, canvasW, canvasH)
        // при pan = 0 точка p окажется в m(p); сдвигаем так, чтобы она легла в (gx + dx, gy + dy)
        return moved.copy(panX = gx + dx - m.applyX(px, py), panY = gy + dy - m.applyY(px, py))
    }

    /** Старый вариант без поворота (вид без учёта размеров холста): zoom вокруг точки (gx, gy), (cx, cy) - центр вида. */
    fun transformed(gx: Float, gy: Float, cx: Float, cy: Float, dz: Float, dx: Float, dy: Float): Camera {
        val z2 = (zoom * dz).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = z2 / zoom
        return copy(
            zoom = z2,
            panX = gx + dx - cx - (gx - cx - panX) * k,
            panY = gy + dy - cy - (gy - cy - panY) * k
        )
    }

    /**
     * Поворот вида на [rotationRad] с сохранением точки холста в центре вида (кнопки «0°», «±90°»).
     */
    fun withRotation(rotationRad: Float, viewW: Float, viewH: Float, canvasW: Int, canvasH: Int): Camera {
        val cx = viewW / 2f; val cy = viewH / 2f
        val drDeg = Math.toDegrees((rotationRad - rotation).toDouble()).toFloat()
        return transformed(cx, cy, viewW, viewH, canvasW, canvasH, 1f, 0f, 0f, drDeg)
    }

    /** Отражение вида по горизонтали/вертикали вокруг центра вида. */
    fun withFlip(fx: Boolean, fy: Boolean, viewW: Float, viewH: Float, canvasW: Int, canvasH: Int): Camera {
        val cx = viewW / 2f; val cy = viewH / 2f
        val inv = viewMatrix(viewW, viewH, canvasW, canvasH).inverse() ?: return copy(flipX = fx, flipY = fy)
        val px = inv.applyX(cx, cy); val py = inv.applyY(cx, cy)
        val moved = copy(flipX = fx, flipY = fy, panX = 0f, panY = 0f)
        val m = moved.viewMatrix(viewW, viewH, canvasW, canvasH)
        return moved.copy(panX = cx - m.applyX(px, py), panY = cy - m.applyY(px, py))
    }

    companion object {
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 20f
        /** Притяжение поворота к 0/90/180/270 градусам (± градусов). */
        const val SNAP_DEG = 4f
        /** Порог жеста: пока пальцы повернулись меньше, чем на столько градусов, вид не крутится (щипок без случайного поворота). */
        const val ROTATE_DEADZONE_DEG = 7f

        /** Масштаб вписывания холста в вид (поля 5%). */
        fun fitScale(viewW: Float, viewH: Float, canvasW: Int, canvasH: Int): Float =
            0.95f * min(viewW / canvasW, viewH / canvasH)

        /** Угол в градусах -> (-180, 180]. */
        fun normalizeDeg(d: Float): Float {
            var a = d % 360f
            if (a <= -180f) a += 360f
            if (a > 180f) a -= 360f
            return a
        }

        /** Угол (градусы) притягивается к ближайшему кратному 90°, если ближе [SNAP_DEG]. */
        fun snapDeg(d: Float, snap: Float = SNAP_DEG): Float {
            val k = (d / 90f).roundToInt() * 90f
            return if (abs(d - k) <= snap) k else d
        }

        fun degToRad(d: Float): Float = (d * PI / 180.0).toFloat()

        private fun snap(v: Float) = if (abs(v) < 1e-6f) 0f else v
    }
}
