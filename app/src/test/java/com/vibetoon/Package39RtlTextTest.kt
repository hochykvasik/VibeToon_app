package com.vibetoon

import com.vibetoon.domain.project.TextBidi
import org.junit.Assert.*
import org.junit.Test

/** Спринт 39: направление письма текста (RTL), выравнивание и порядок букв для текстовых эффектов. */
class Package39RtlTextTest {
    private val hebrew = "שלום"
    private val arabic = "مرحبا"

    @Test
    fun detectsDirectionByFirstStrongLetter() {
        assertEquals(true, TextBidi.firstStrongRtl(hebrew))
        assertEquals(true, TextBidi.firstStrongRtl("123 $arabic"))
        assertEquals(false, TextBidi.firstStrongRtl("Привет $hebrew"))
        assertEquals(false, TextBidi.firstStrongRtl("Hello"))
        assertNull(TextBidi.firstStrongRtl("123 !?"))
        assertTrue(TextBidi.isRtlChar('ש'))
        assertFalse(TextBidi.isRtlChar('Ж'))
    }

    @Test
    fun explicitModesOverrideAuto() {
        assertTrue(TextBidi.resolveRtl(TextBidi.AUTO, hebrew))
        assertFalse(TextBidi.resolveRtl(TextBidi.AUTO, "Текст"))
        assertFalse(TextBidi.resolveRtl(TextBidi.AUTO, "2026"))   // без букв - слева направо
        assertTrue(TextBidi.resolveRtl(TextBidi.RTL, "Текст"))
        assertFalse(TextBidi.resolveRtl(TextBidi.LTR, hebrew))
        assertEquals(TextBidi.AUTO, TextBidi.coerce(7))
        // в режиме «Авто» каждый абзац (строка после Enter) решает сам, как StaticLayout FIRSTSTRONG_LTR
        assertTrue(TextBidi.paragraphRtl(TextBidi.AUTO, hebrew))
        assertFalse(TextBidi.paragraphRtl(TextBidi.AUTO, "Hello"))
    }

    @Test
    fun physicalAlignmentMapsToLayoutAlignment() {
        // 0 NORMAL, 1 CENTER, 2 OPPOSITE: «слева» в RTL-абзаце - это OPPOSITE
        assertEquals(0, TextBidi.layoutAlign(0, rtl = false))
        assertEquals(2, TextBidi.layoutAlign(0, rtl = true))
        assertEquals(2, TextBidi.layoutAlign(2, rtl = false))
        assertEquals(0, TextBidi.layoutAlign(2, rtl = true))
        assertEquals(1, TextBidi.layoutAlign(1, rtl = true))
        assertEquals(0f, TextBidi.lineStartX(0, 100f, 40f), 1e-6f)
        assertEquals(30f, TextBidi.lineStartX(1, 100f, 40f), 1e-6f)
        assertEquals(60f, TextBidi.lineStartX(2, 100f, 40f), 1e-6f)
    }

    @Test
    fun alignmentFollowsLineStartWhenDirectionFlips() {
        assertEquals(2, TextBidi.alignOnDirectionChange(0, wasRtl = false, nowRtl = true))
        assertEquals(0, TextBidi.alignOnDirectionChange(2, wasRtl = true, nowRtl = false))
        assertEquals(1, TextBidi.alignOnDirectionChange(1, wasRtl = false, nowRtl = true))
        assertEquals(0, TextBidi.alignOnDirectionChange(0, wasRtl = false, nowRtl = false))
    }

    @Test
    fun rtlParagraphReversesLettersButKeepsNumbersAndLatin() {
        assertEquals("םולש", TextBidi.visualString(hebrew, rtlBase = true))
        // число внутри иврита читается слева направо
        assertEquals("123 םולש", TextBidi.visualString("$hebrew 123", rtlBase = true))
        // латиница внутри RTL-абзаца остаётся словом слева направо
        assertEquals("ABC םולש", TextBidi.visualString("$hebrew ABC", rtlBase = true))
        // иврит внутри русского (LTR) абзаца: порядок русских слов не меняется
        assertEquals("Привет םולש мир", TextBidi.visualString("Привет $hebrew мир", rtlBase = false))
        // в LTR-тексте без RTL-букв ничего не переставляется (RU/EN)
        assertEquals("Hello, мир 42!", TextBidi.visualString("Hello, мир 42!", rtlBase = false))
        // RU/EN-текст с принудительным RTL: слова не разваливаются, знак в конце уходит к началу строки (справа налево)
        assertEquals("Привет мир", TextBidi.visualString("Привет мир", rtlBase = true))
        assertEquals("!Hello world", TextBidi.visualString("Hello world!", rtlBase = true))
    }

    @Test
    fun mirrorsBracketsInsideRtl() {
        assertEquals("(םולש)", TextBidi.visualString("($hebrew)", rtlBase = true))
        assertEquals('(', TextBidi.mirror(')'))
        assertEquals('»', TextBidi.mirror('«'))
        assertEquals('a', TextBidi.mirror('a'))
    }

    @Test
    fun arabicLettersStayOneUnitForShaping() {
        val units = TextBidi.units(arabic)
        assertEquals(1, units.size)
        assertEquals(0 until arabic.length, units[0])
        val two = TextBidi.visualUnits("$arabic $arabic", rtlBase = true)
        assertEquals(3, two.size)
        assertTrue(two.all { it.rtl })
        // буквы иврита не соединяются - каждая отдельно
        assertEquals(hebrew.length, TextBidi.units(hebrew).size)
        // комбинируемый знак рисуется вместе с буквой
        assertEquals(1, TextBidi.units("е\u0301").size)
    }

    @Test
    fun visualOrderIsPermutation() {
        for (s in listOf("Привет $hebrew 12 ABC", "($arabic) 2026", "", "e\u0301 $hebrew")) for (rtl in listOf(true, false)) {
            val order = TextBidi.visualOrder(s, rtl)
            assertEquals(s.length, order.size)
            assertEquals((0 until s.length).toList(), order.sorted())
        }
    }

    @Test
    fun levelsAreOddForRtlRuns() {
        val lv = TextBidi.levels("ab $hebrew", rtlBase = false)
        assertEquals(0, lv[0]); assertEquals(0, lv[2])
        assertEquals(1, lv[3])
        val lr = TextBidi.levels("$hebrew 12", rtlBase = true)
        assertEquals(1, lr[0]); assertEquals(2, lr[hebrew.length + 1])
    }
}
