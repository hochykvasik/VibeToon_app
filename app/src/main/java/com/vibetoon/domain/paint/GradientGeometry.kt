package com.vibetoon.domain.paint

import kotlin.math.hypot

/** Screen-space affine layer quad and gesture for the GPU gradient. */
object GradientGeometry {
    fun create(quad: FloatArray, startX: Float, startY: Float, endX: Float, endY: Float): FloatArray? {
        if (quad.size != 8 || quad.any { !it.isFinite() } ||
            listOf(startX, startY, endX, endY).any { !it.isFinite() } ||
            hypot(endX - startX, endY - startY) < 2f) return null
        return floatArrayOf(quad[0], quad[1], quad[2] - quad[0], quad[3] - quad[1],
            quad[6] - quad[0], quad[7] - quad[1], startX, startY, endX, endY)
            .takeIf { it.all(Float::isFinite) }
    }
}
