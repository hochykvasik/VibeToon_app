package com.vibetoon

import com.vibetoon.domain.anim.LiquifyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquifyTest {
    private val red = 0xFFFF0000.toInt()

    @Test fun falloffEdges() {
        assertEquals(1f, LiquifyMath.falloff(0f, 10f), 1e-6f)
        assertEquals(0f, LiquifyMath.falloff(10f, 10f), 0f)
    }

    @Test fun pushDragsContentRight() {
        // 20x1: красная точка в x=5, толкаем вправо в центре (5.5, 0.5)
        val w = 20; val h = 1
        val px = IntArray(w); px[5] = red
        LiquifyMath.push(px, w, h, 5.5f, 0.5f, 6f, 3f, 0f)
        val maxX = (0 until w).filter { (px[it] ushr 24) > 40 }.maxOrNull() ?: -1
        assertTrue("content must move right, got $maxX", maxX >= 7)
    }

    @Test fun outsideRadiusUntouched() {
        val w = 30; val h = 1
        val px = IntArray(w); px[25] = red
        LiquifyMath.push(px, w, h, 3f, 0.5f, 5f, 4f, 0f)
        assertEquals(red, px[25])
    }

    @Test fun transparentStaysTransparent() {
        val px = IntArray(100)
        LiquifyMath.push(px, 10, 10, 5f, 5f, 4f, 2f, 1f)
        assertTrue(px.all { it == 0 })
    }
}
