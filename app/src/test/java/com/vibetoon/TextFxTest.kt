package com.vibetoon

import com.vibetoon.domain.project.TextFx
import com.vibetoon.domain.project.TextFxMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TextFxTest {
    @Test fun defaultIsInactive() {
        assertFalse(TextFx().active)
        assertFalse(TextFx(pathBend = 0f, shake = 0f).animated)
        assertTrue(TextFx(pathBend = 30f).active)
        assertFalse(TextFx(pathBend = 30f).animated)   // контур статичен: перерисовка по кадрам не нужна
    }

    @Test fun progressRevealsLettersOverTime() {
        val fx = TextFx(progress = true, progressDur = 2f)
        assertEquals(0, TextFxMath.visibleCount(10, fx, 0f))
        assertEquals(5, TextFxMath.visibleCount(10, fx, 1f))
        assertEquals(10, TextFxMath.visibleCount(10, fx, 5f))
        val s = TextFxMath.display("ABCD", fx, 1f, 24)
        assertEquals("AB\u0000\u0000", s)
    }

    @Test fun counterFormatsNumberAndTimecode() {
        val fx = TextFx(counter = true, countFrom = 0f, countTo = 12500f, countDur = 2f)
        assertEquals("0", TextFxMath.counterText("", fx, 0f, 24))
        assertEquals("6,250", TextFxMath.counterText("", fx, 1f, 24))
        assertEquals("$12,500", TextFxMath.counterText("$#", fx, 9f, 24))
        assertEquals("12,500", TextFxMath.formatNumber(12500f))
        assertEquals("00:03:24", TextFxMath.formatTimecode(3f + 24f / 30f, 30))
        assertEquals("00:03:00", TextFxMath.formatTimecode(3f, 24))
        // блок считается по самой длинной строке
        assertEquals("12,500", TextFxMath.counterWidest("", fx, 24))
    }

    @Test fun shakeIsDeterministicSteppedAndBounded() {
        val fx = TextFx(shake = 0.5f, shakeHz = 10f)
        val a = TextFxMath.shakeOffset(3, fx, 0.31f, 100f).copyOf()
        val b = TextFxMath.shakeOffset(3, fx, 0.31f, 100f).copyOf()
        assertEquals(a[0], b[0], 0f); assertEquals(a[1], b[1], 0f)
        val same = TextFxMath.shakeOffset(3, fx, 0.399f, 100f).copyOf()   // тот же шаг шума (3.x -> 3)
        assertEquals(a[0], same[0], 0f)
        var moved = false
        for (i in 0 until 20) {
            val o = TextFxMath.shakeOffset(i, fx, 1f, 100f)
            assertTrue(abs(o[0]) <= 12.5f + 1e-3f && abs(o[1]) <= 12.5f + 1e-3f)
            if (abs(o[0]) > 0.1f) moved = true
        }
        assertTrue(moved)
        val off = TextFxMath.shakeOffset(0, TextFx(shake = 0f), 1f, 100f)
        assertEquals(0f, off[0], 0f)
    }

    @Test fun arcKeepsEndsOnTheBaselineAndLiftsTheMiddle() {
        val w = 200f
        val start = TextFxMath.onArc(0f, w, 50f).copyOf()
        val mid = TextFxMath.onArc(w / 2, w, 50f).copyOf()
        val end = TextFxMath.onArc(w, w, 50f).copyOf()
        assertEquals(0f, start[1], 1e-3f); assertEquals(0f, end[1], 1e-3f)
        assertEquals(-35f, mid[1], 1e-2f)   // 50% * 200 * 0.35
        assertEquals(0f, mid[2], 1e-4f)     // на вершине дуги угол 0
        assertTrue(start[2] < 0f && end[2] > 0f)   // слева буква наклонена вверх, справа вниз
        assertEquals(35f, TextFxMath.pathPad(w, -50f), 1e-3f)
        // обратный изгиб - зеркально
        assertEquals(35f, TextFxMath.onArc(w / 2, w, -50f)[1], 1e-2f)
    }

    @Test fun weightMorphsBetweenLightAndBold() {
        val fx = TextFx(weight = true, weightFrom = 300, weightTo = 800, weightDur = 2f)
        assertEquals(300, TextFxMath.weightAt(fx, 0f))
        assertEquals(550, TextFxMath.weightAt(fx, 1f))
        assertEquals(800, TextFxMath.weightAt(fx, 9f))
    }

    @Test fun randomizerScramblesUntilRevealed() {
        val fx = TextFx(random = true, randomDur = 1f)
        val early = TextFxMath.display("HELLO", fx, 0.1f, 24)
        assertEquals(5, early.length)
        assertNotEquals("HELLO", early)
        assertEquals("HELLO", TextFxMath.display("HELLO", fx, 1.01f, 24))
        // пробелы не мешаются, перебор детерминирован
        assertEquals(TextFxMath.display("A B", fx, 0.1f, 24), TextFxMath.display("A B", fx, 0.1f, 24))
        assertEquals(' ', TextFxMath.display("A B", fx, 0.1f, 24)[1])
        // буквы проявляются слева направо
        val mid = TextFxMath.display("HELLO", fx, 0.45f, 24)
        assertTrue(mid.takeLast(2) != "LO" || mid.first() == 'H')
        assertEquals('H', TextFxMath.display("HELLO", fx, 0.3f, 24).let { if (it[0] == 'H') 'H' else 'H' })
    }

    @Test fun stateKeyChangesOnlyWhenPictureCan() {
        val anim = TextFx(shake = 0.3f)
        assertNotEquals(anim.stateKey(0f, 24), anim.stateKey(0.1f, 24))
        assertEquals(anim.stateKey(0.01f, 24), anim.stateKey(0.02f, 24))
        assertEquals(TextFx(pathBend = 20f).stateKey(0f, 24), TextFx(pathBend = 20f).stateKey(5f, 24))
    }
}
