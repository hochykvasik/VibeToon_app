package com.vibetoon

import com.vibetoon.domain.project.TextBidi
import com.vibetoon.domain.project.UnicodeBidi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 40: полноценный Unicode Bidi для текстовых эффектов - числа, скобки, изоляты, хвостовые пробелы. */
class Package40BidiTest {
    private val he = "\u05E9\u05DC\u05D5\u05DD"          // шалом
    private val heV = "\u05DD\u05D5\u05DC\u05E9"         // визуально (справа налево)
    private val ar = "\u0645\u0631\u062D\u0628\u0627"

    @Test fun numbersWithSeparatorsStayTogether() {
        // 1,5 / 10-20 / 25% / $5.00 - число читается слева направо целиком, внутри RTL
        assertEquals("1,5 $heV", TextBidi.visualString("$he 1,5", true))
        assertEquals("10-20 $heV", TextBidi.visualString("$he 10-20", true))
        assertEquals("25% $heV", TextBidi.visualString("$he 25%", true))
        assertEquals("$heV price \$5.00", TextBidi.visualString("price \$5.00 $he", true))
        // в LTR-абзаце: иврит и число после него
        assertEquals("10-20 $heV kg", TextBidi.visualString("$he 10-20 kg", false))
    }

    @Test fun bracketsPairAcrossDirections() {
        // латиница в скобках внутри иврита: скобки остаются вокруг слова, порядок предсказуем
        assertEquals("5 (abc) $heV", TextBidi.visualString("$he (abc) 5", true))
        assertEquals("abc ($heV) def", TextBidi.visualString("abc ($he) def", false))
        assertEquals("def ($heV) abc", TextBidi.visualString("abc ($he) def", true))
        assertEquals("c ($heV b) a", TextBidi.visualString("a (b $he) c", true))
        // вложенные и непарные скобки
        assertEquals("$heV ((abc)) $heV", TextBidi.visualString("$he ((abc)) $he", true))
        assertEquals("$heV)", TextBidi.visualString("($he", true))
    }

    @Test fun isolatesKeepInnerTextFromReorderingOuter() {
        val rli = "\u2067"; val lri = "\u2066"; val pdi = "\u2069"
        // изолят с ивритом внутри английского: внешние слова не меняются местами
        val v = TextBidi.visualString("abc $rli$he 1$pdi def", false).filter { it.code !in 0x2066..0x2069 }
        assertEquals("abc 1 $heV def", v)
        // первая сильная буква внутри изолята не задаёт направление абзаца (P2)
        assertEquals(false, TextBidi.firstStrongRtl("$rli$he$pdi abc"))
        assertEquals(true, TextBidi.firstStrongRtl("$lri abc$pdi $he"))
        assertEquals(null, TextBidi.firstStrongRtl("$rli$he$pdi 123"))
        // невидимые знаки направления - отдельные пустые единицы (эффекты их не рисуют)
        val line = "a$lri b$pdi"
        val ctrl = TextBidi.units(line).filter { TextBidi.isControlUnit(line, it) }
        assertEquals(2, ctrl.size)
        assertFalse(TextBidi.isControlUnit(line, 0..0))
    }

    @Test fun trailingSpacesStayAtLineEnd() {
        val lv = TextBidi.levels("$he   ", false)
        assertEquals(0, lv[he.length]); assertEquals(0, lv[he.length + 2])
        // LRM/RLM - сильные буквы нулевой ширины
        assertEquals(true, TextBidi.firstStrongRtl("\u200F abc"))
        assertEquals(UnicodeBidi.BC.LRI, UnicodeBidi.classOf(0x2066))
        assertEquals(UnicodeBidi.BC.AN, UnicodeBidi.classOf(0x0661))
    }

    @Test fun arabicDigitsAfterArabicLetters() {
        // европейские цифры после арабских букв становятся арабскими числами (W2), числа идут слева направо
        val lv = TextBidi.levels("$ar 12 34", true)
        assertEquals(2, lv[ar.length + 1]); assertEquals(1, lv[ar.length + 3]); assertEquals(2, lv[ar.length + 4])
        assertEquals("34 12 $ar".length, TextBidi.visualString("$ar 12 34", true).length)
    }

    /** Визуальный порядок символов по уровням (L2), без невидимых знаков направления. */
    private fun visual(s: String, lv: IntArray): String {
        val idx = (s.indices).toMutableList()
        var k = lv.maxOrNull() ?: 0
        while (k >= 1) {
            var a = 0
            while (a < idx.size) {
                if (lv[idx[a]] < k) { a++; continue }
                var b = a
                while (b < idx.size && lv[idx[b]] >= k) b++
                idx.subList(a, b).reverse(); a = b
            }
            k--
        }
        return idx.map { s[it] }.filter { it.code !in 0x2066..0x2069 }.joinToString("")
    }

    private fun javaLevels(s: String, rtl: Boolean): IntArray {
        val b = java.text.Bidi(s, if (rtl) java.text.Bidi.DIRECTION_RIGHT_TO_LEFT else java.text.Bidi.DIRECTION_LEFT_TO_RIGHT)
        return IntArray(s.length) { b.getLevelAt(it) }
    }

    @Test fun matchesReferenceBidiOnMixedText() {
        val cases = listOf("$he 123", "$he (abc) 5", "abc ($he) def", "$he 1,5 $he", "$he 10-20 kg", "price \$5.00 $he", "$he 25% off",
            "a (b $he) c", "$he [abc] $he", "$ar \u0661\u0662\u0663 abc", "$ar 12 34", "abc \u2067$he 1\u2069 def", "$he \u2066abc!\u2069 $he",
            "\u2068$he\u2069 abc", "$he: abc, def.", "1. $he", "$he   ", "($he", "abc ) $he (", "$he ((abc)) $he", "a\t$he b", "x [$he] y",
            "$he (1) $he", "$he-$he", "AB(CD[$he]EF)", "Привет, $he! 2026", "«$he» и «abc»", "$he {a [b] c} $he")
        for (s in cases) for (rtl in listOf(false, true))
            assertEquals("$s rtl=$rtl", visual(s, javaLevels(s, rtl)), visual(s, TextBidi.levels(s, rtl)))
    }

    @Test fun matchesReferenceBidiOnRandomText() {
        // случайные строки из букв обоих направлений, цифр, разделителей, скобок и изолятов (без NSM после скобок -
        // там старые версии java.text.Bidi отступают от правила N0, а наш алгоритм следует UAX #9)
        val alpha = listOf("a", "Z", "\u05D0", "\u05E9", "\u0645", "\u0628", "1", "2", "\u0661", "\u06F1", " ", "(", ")", "[", "]", "{", "}",
            ",", ".", "-", "+", "\$", "%", ":", "/", "!", "\t", "\u2066", "\u2067", "\u2068", "\u2069", "\u00A0", "#")
        val rnd = java.util.Random(40)
        var bad = 0
        repeat(3000) {
            val s = (0 until 1 + rnd.nextInt(12)).joinToString("") { alpha[rnd.nextInt(alpha.size)] }
            for (rtl in listOf(false, true)) if (visual(s, javaLevels(s, rtl)) != visual(s, TextBidi.levels(s, rtl))) bad++
        }
        assertTrue("расхождений с эталоном: $bad", bad <= 3)
    }

    @Test fun visualOrderIsAlwaysPermutation() {
        val rnd = java.util.Random(7)
        val alpha = listOf("a", "\u05D0", "\u0645", "1", " ", "(", ")", "\u2067", "\u2069", "\u202B", "\u202C", "\u0301", "e")
        repeat(500) {
            val s = (0 until rnd.nextInt(10)).joinToString("") { alpha[rnd.nextInt(alpha.size)] }
            for (rtl in listOf(false, true)) assertEquals(s.indices.toList(), TextBidi.visualOrder(s, rtl).sorted())
        }
    }
}
