package com.vibetoon.domain.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Аффинное преобразование 2D (матрица 2x3):
 *   x' = a*x + c*y + tx
 *   y' = b*x + d*y + ty
 * Столбцы (a, b) и (c, d) - образы осей X и Y. Именно этот формат уходит в C++ для каждого слоя:
 * он выражает и перекос (shear), который появляется у детей повёрнутой группы с неравномерным масштабом.
 */
data class Affine2D(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f
) {
    /** Композиция: сначала применяется [o], затем this (this * o). */
    operator fun times(o: Affine2D) = Affine2D(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        tx = a * o.tx + c * o.ty + tx,
        ty = b * o.tx + d * o.ty + ty
    )

    fun applyX(x: Float, y: Float) = a * x + c * y + tx
    fun applyY(x: Float, y: Float) = b * x + d * y + ty

    /** Обратная матрица или null, если преобразование вырождено (|det| ~ 0). */
    fun inverse(): Affine2D? {
        val det = a * d - b * c
        if (abs(det) < 1e-9f) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine2D(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    companion object {
        val IDENTITY = Affine2D()
        fun translate(x: Float, y: Float) = Affine2D(tx = x, ty = y)
        fun scale(sx: Float, sy: Float) = Affine2D(a = sx, d = sy)
        fun rotate(rad: Float): Affine2D {
            val cs = cos(rad)
            val sn = sin(rad)
            return Affine2D(cs, sn, -sn, cs, 0f, 0f)
        }
    }
}
