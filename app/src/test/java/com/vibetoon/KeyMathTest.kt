package com.vibetoon

import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.KeyMath
import com.vibetoon.domain.anim.Keyframe
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMathTest {
    private fun k(f: Int, dx: Float, e: EasingType = EasingType.LINEAR) = Keyframe(f, dx, 0f, 1f, 1f, 0f, 1f, e)

    @Test fun stepHoldsUntilNextKey() {
        val tr = AnimationTrack(1, listOf(k(0, 0f, EasingType.STEP), k(10, 100f)))
        assertEquals(0f, tr.evaluate(5)!!.dx, 0.001f)
        assertEquals(0f, tr.evaluate(9)!!.dx, 0.001f)
        assertEquals(100f, tr.evaluate(10)!!.dx, 0.001f)
        assertEquals(50f, AnimationTrack(1, listOf(k(0, 0f), k(10, 100f))).evaluate(5)!!.dx, 0.001f)
    }

    @Test fun stretchAndCompress() {
        assertEquals(20, KeyMath.stretchFrame(10, 0, 2f))
        assertEquals(15, KeyMath.stretchFrame(20, 10, 0.5f))
        assertEquals(10, KeyMath.stretchFrame(10, 10, 3f))
    }

    @Test fun reverseMirrorsFramesAndMovesEasing() {
        val r = KeyMath.reverse(listOf(k(10, 1f, EasingType.EASE_IN), k(20, 2f, EasingType.EASE_OUT), k(40, 3f)))
        assertEquals(listOf(10, 30, 40), r.map { it.frame })
        assertEquals(listOf(3f, 2f, 1f), r.map { it.dx })
        assertEquals(EasingType.EASE_OUT, r[0].easing); assertEquals(EasingType.EASE_IN, r[1].easing)
    }
}
