package com.vibetoon

import com.vibetoon.domain.model.Affine2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Affine2DTest {
    @Test fun compositionAppliesRightOperandFirst() {
        val m = Affine2D.translate(10f, 0f) * Affine2D.scale(2f, 2f)   // сначала масштаб, потом сдвиг
        assertEquals(12f, m.applyX(1f, 0f), 1e-5f)
    }

    @Test fun rotationByQuarterTurn() {
        val r = Affine2D.rotate((Math.PI / 2).toFloat())
        assertEquals(0f, r.applyX(1f, 0f), 1e-5f)
        assertEquals(1f, r.applyY(1f, 0f), 1e-5f)
    }

    @Test fun inverseRoundTrip() {
        val m = Affine2D.translate(5f, -3f) * Affine2D.rotate(0.7f) * Affine2D.scale(2f, 0.5f)
        val back = m.inverse()!! * m
        assertEquals(1f, back.a, 1e-4f)
        assertEquals(0f, back.b, 1e-4f)
        assertEquals(0f, back.c, 1e-4f)
        assertEquals(1f, back.d, 1e-4f)
        assertEquals(0f, back.tx, 1e-3f)
        assertEquals(0f, back.ty, 1e-3f)
    }

    @Test fun degenerateHasNoInverse() = assertNull(Affine2D.scale(0f, 1f).inverse())
}
