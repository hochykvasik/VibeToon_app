package com.vibetoon.domain.project

/**
 * Спринт 39: направление письма текстового слоя (RTL - иврит, арабский) без Android-зависимостей.
 *
 * - [AUTO] - направление каждого абзаца по первой «сильной» букве (как TextDirectionHeuristics.FIRSTSTRONG_LTR),
 *   [LTR] - слева направо, [RTL] - справа налево.
 * - Выравнивание в модели физическое: 0 - слева, 1 - по центру, 2 - справа. StaticLayout выравнивает относительно
 *   направления абзаца (NORMAL/OPPOSITE), поэтому [layoutAlign] переводит одно в другое.
 * - Для текстовых эффектов буквы рисуются по одной: [visualUnits] даёт порядок «единиц» слева направо
 *   (упрощённый Unicode Bidi: уровни, нейтральные символы, цифры, зеркальные скобки). Арабские буквы,
 *   которые соединяются между собой, рисуются одной единицей (словом), чтобы не терялись формы букв.
 */
object TextBidi {
    const val AUTO = 0
    const val LTR = 1
    const val RTL = 2
    val MODES = listOf(AUTO, LTR, RTL)

    fun coerce(dir: Int): Int = if (dir in MODES) dir else AUTO

    /** Класс символа для упрощённого Bidi: сильный L, сильный R, цифра, нейтральный. */
    enum class Kind { L, R, DIGIT, NEUTRAL, NEUTRAL_L, NEUTRAL_R }

    fun kindOf(cp: Int): Kind = when (Character.getDirectionality(cp)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING, Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> Kind.R
        Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> Kind.L
        Character.DIRECTIONALITY_EUROPEAN_NUMBER, Character.DIRECTIONALITY_ARABIC_NUMBER -> Kind.DIGIT
        else -> Kind.NEUTRAL
    }

    fun isRtlChar(c: Char): Boolean = kindOf(c.code) == Kind.R

    /** Направление первой сильной буквы: true - RTL, false - LTR, null - сильных букв нет (цифры, знаки). */
    fun firstStrongRtl(text: CharSequence): Boolean? {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            when (kindOf(cp)) { Kind.R -> return true; Kind.L -> return false; else -> Unit }
            i += Character.charCount(cp)
        }
        return null
    }

    /** Направление абзаца (строки между Enter): для [AUTO] - по первой сильной букве, без букв - слева направо. */
    fun paragraphRtl(dir: Int, paragraph: CharSequence): Boolean = when (coerce(dir)) {
        RTL -> true
        LTR -> false
        else -> firstStrongRtl(paragraph) ?: false
    }

    /** Направление всего текста (для поля ввода и смены выравнивания): по первому абзацу с буквами. */
    fun resolveRtl(dir: Int, text: CharSequence): Boolean = when (coerce(dir)) {
        RTL -> true
        LTR -> false
        else -> firstStrongRtl(text) ?: false
    }

    /** Выравнивание StaticLayout (0 NORMAL, 1 CENTER, 2 OPPOSITE) для физического выравнивания [align] в абзаце [rtl]. */
    fun layoutAlign(align: Int, rtl: Boolean): Int = when (align.coerceIn(0, 2)) {
        1 -> 1
        0 -> if (rtl) 2 else 0
        else -> if (rtl) 0 else 2
    }

    /** Левый край строки шириной [lineW] в блоке [blockW] для физического выравнивания (эффекты рисуют буквы сами). */
    fun lineStartX(align: Int, blockW: Float, lineW: Float): Float = when (align.coerceIn(0, 2)) {
        1 -> (blockW - lineW) / 2f
        2 -> blockW - lineW
        else -> 0f
    }

    /**
     * Смена направления: выравнивание «по началу строки» переезжает к новому началу (слева <-> справа),
     * по центру остаётся. Если фактическое направление не изменилось - выравнивание не трогаем.
     */
    fun alignOnDirectionChange(align: Int, wasRtl: Boolean, nowRtl: Boolean): Int {
        val a = align.coerceIn(0, 2)
        if (wasRtl == nowRtl || a == 1) return a
        return 2 - a
    }

    /** Уровни вложенности (чётный - LTR, нечётный - RTL) для каждого char строки, упрощённый UBA (правила W, N, I). */
    fun levels(line: CharSequence, rtlBase: Boolean): IntArray {
        val n = line.length
        val kinds = Array(n) { Kind.NEUTRAL }
        var i = 0
        while (i < n) {
            val cp = Character.codePointAt(line, i)
            val k = kindOf(cp)
            val cc = Character.charCount(cp)
            for (j in i until minOf(n, i + cc)) kinds[j] = k
            i += cc
        }
        // W7: цифры после сильной L (или в начале LTR-абзаца) ведут себя как L
        var lastStrongL = !rtlBase
        for (j in 0 until n) {
            when (kinds[j]) {
                Kind.L -> lastStrongL = true
                Kind.R -> lastStrongL = false
                Kind.DIGIT -> if (lastStrongL) kinds[j] = Kind.L
                else -> Unit
            }
        }
        // N1/N2: нейтральные между одинаковыми сильными берут их направление (цифры = R), иначе - направление абзаца
        fun strongOf(k: Kind): Kind? = when (k) { Kind.L -> Kind.L; Kind.R, Kind.DIGIT -> Kind.R; else -> null }
        val base = if (rtlBase) Kind.R else Kind.L
        var j = 0
        while (j < n) {
            if (kinds[j] != Kind.NEUTRAL) { j++; continue }
            var e = j
            while (e < n && kinds[e] == Kind.NEUTRAL) e++
            val before = if (j == 0) base else strongOf(kinds[j - 1]) ?: base
            val after = if (e >= n) base else strongOf(kinds[e]) ?: base
            val res = if (before == after) before else base
            for (q in j until e) kinds[q] = if (res == Kind.R) Kind.NEUTRAL_R else Kind.NEUTRAL_L
            j = e
        }
        val baseLevel = if (rtlBase) 1 else 0
        return IntArray(n) { q ->
            val k = kinds[q]
            if (baseLevel == 0) when (k) { Kind.R, Kind.NEUTRAL_R -> 1; Kind.DIGIT -> 2; else -> 0 }
            else when (k) { Kind.L, Kind.NEUTRAL_L, Kind.DIGIT -> 2; else -> 1 }
        }
    }

    /** Логические единицы рисования строки: суррогатные пары и комбинируемые знаки - вместе с буквой, связанные арабские буквы - словом. */
    fun units(line: CharSequence): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        val n = line.length
        while (i < n) {
            val start = i
            var cp = Character.codePointAt(line, i)
            i += Character.charCount(cp)
            if (isJoining(cp)) {
                while (i < n) {
                    cp = Character.codePointAt(line, i)
                    if (isJoining(cp) || isMark(cp)) i += Character.charCount(cp) else break
                }
            } else {
                while (i < n) {
                    cp = Character.codePointAt(line, i)
                    if (isMark(cp)) i += Character.charCount(cp) else break
                }
            }
            out += start until i
        }
        return out
    }

    /** Единица рисования: логический диапазон и уровень (нечётный - внутри RTL, скобки зеркалятся). */
    data class VisualUnit(val range: IntRange, val level: Int) { val rtl: Boolean get() = level % 2 == 1 }

    /** Единицы строки в визуальном порядке слева направо (правило L2: разворот последовательностей с уровнем >= k). */
    fun visualUnits(line: CharSequence, rtlBase: Boolean): List<VisualUnit> {
        if (line.isEmpty()) return emptyList()
        val lv = levels(line, rtlBase)
        val us = units(line).map { VisualUnit(it, lv[it.first]) }.toMutableList()
        val maxL = us.maxOf { it.level }
        val minOdd = us.filter { it.level % 2 == 1 }.minOfOrNull { it.level } ?: return us
        var k = maxL
        while (k >= minOdd) {
            var a = 0
            while (a < us.size) {
                if (us[a].level < k) { a++; continue }
                var b = a
                while (b < us.size && us[b].level >= k) b++
                us.subList(a, b).reverse()
                a = b
            }
            k--
        }
        return us
    }

    /** Логические индексы char в визуальном порядке единиц (внутри единицы - логический порядок). */
    fun visualOrder(line: CharSequence, rtlBase: Boolean): IntArray =
        visualUnits(line, rtlBase).flatMap { it.range.toList() }.toIntArray()

    /** Строка, переставленная в визуальный порядок (арабские слова остаются словами в логическом порядке - их форму собирает шрифт). */
    fun visualString(line: CharSequence, rtlBase: Boolean): String {
        val sb = StringBuilder()
        for (u in visualUnits(line, rtlBase)) {
            val s = line.subSequence(u.range.first, u.range.last + 1).toString()
            sb.append(if (u.rtl && s.length == 1) mirror(s[0]).toString() else s)
        }
        return sb.toString()
    }

    private val MIRRORS = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«', '‹' to '›', '›' to '‹')

    /** Зеркальная пара скобки для символа внутри RTL (правило L4). */
    fun mirror(c: Char): Char = MIRRORS[c] ?: c

    /** Арабская письменность (буквы соединяются - рисуем словом). */
    fun isJoining(cp: Int): Boolean =
        Character.isLetter(cp) && (cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF || cp in 0x0700..0x074F)

    /** Комбинируемый знак (огласовки, ударения): рисуется вместе с предыдущей буквой. */
    fun isMark(cp: Int): Boolean {
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() || cp == 0x200D
    }
}
