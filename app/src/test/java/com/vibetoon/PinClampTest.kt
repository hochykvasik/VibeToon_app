package com.vibetoon

import com.vibetoon.domain.anim.PuppetMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PinClampTest {
    @Test fun clampLimitsRunawayVerticesAndRepairsNaN() {
        val w = 100f; val h = 100f
        val base = FloatArray(6)
        val out = floatArrayOf(5f, 0f,  0.001f, 0f,  Float.NaN, 0f)   // 500 px, 0.1 px, NaN
        PuppetMath.clampMoves(base, out, 10f, w, h)
        val lim = 10f * PuppetMath.CLAMP_K + PuppetMath.CLAMP_PAD
        assertEquals(lim, hypot(out[0] * w, out[1] * h), 1e-3f)   // улетевшая вершина прижата к пределу
        assertEquals(0.001f, out[2], 1e-6f)                        // обычное смещение не тронуто
        assertEquals(0f, out[4], 0f)                               // NaN откатился к исходному
        assertTrue(out.all { it.isFinite() })
    }
}
