package com.vibetoon.domain.project

/**
 * Спринт 39: направление письма текстового слоя (RTL - иврит, арабский) без Android-зависимостей.
 *
 * - [AUTO] - направление каждого абзаца по первой «сильной» букве (как TextDirectionHeuristics.FIRSTSTRONG_LTR),
 *   [LTR] - слева направо, [RTL] - справа налево.
 * - Выравнивание в модели физическое: 0 - слева, 1 - по центру, 2 - справа. StaticLayout выравнивает относительно
 *   направления абзаца (NORMAL/OPPOSITE), поэтому [layoutAlign] переводит одно в другое.
 * - Для текстовых эффектов буквы рисуются по одной: [visualUnits] даёт порядок «единиц» слева направо
 *   (спринт 40: полноценный Unicode Bidi в [UnicodeBidi] - числа с разделителями, парные скобки, изоляты, L1; зеркалирование L4). Арабские буквы,
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

    /**
     * Направление первой сильной буквы: true - RTL, false - LTR, null - сильных букв нет (цифры, знаки).
     * Спринт 40 (правило P2): текст внутри изолятов LRI/RLI/FSI…PDI не влияет на направление абзаца; LRM/RLM учитываются.
     */
    fun firstStrongRtl(text: CharSequence): Boolean? = UnicodeBidi.firstStrongRtl(text)

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

    /**
     * Уровни вложенности (чётный - LTR, нечётный - RTL) для каждого char строки. Спринт 40: полноценный UBA
     * ([UnicodeBidi]): числа с разделителями (1,5 / 10-20 / 25% / $5), арабские цифры, парные скобки (N0),
     * изоляты LRI/RLI/FSI/PDI, встраивания LRE/RLE/LRO/RLO/PDF и хвостовые пробелы (L1).
     */
    fun levels(line: CharSequence, rtlBase: Boolean): IntArray = UnicodeBidi.levels(line, rtlBase)

    /** Логические единицы рисования строки: суррогатные пары и комбинируемые знаки - вместе с буквой, связанные арабские буквы - словом. */
    fun units(line: CharSequence): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        val n = line.length
        while (i < n) {
            val start = i
            var cp = Character.codePointAt(line, i)
            i += Character.charCount(cp)
            if (isFormatControl(cp)) { out += start until i; continue }   // спринт 40: невидимый знак направления - своя пустая единица
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

    private val MIRROR_PAIRS = listOf('(' to ')', '[' to ']', '{' to '}', '<' to '>', '«' to '»', '‹' to '›',
        '\u2045' to '\u2046', '\u207D' to '\u207E', '\u208D' to '\u208E', '\u2208' to '\u220B', '\u2264' to '\u2265',
        '\u226A' to '\u226B', '\u2282' to '\u2283', '\u2286' to '\u2287', '\u2308' to '\u2309', '\u230A' to '\u230B',
        '\u2329' to '\u232A', '\u2768' to '\u2769', '\u276A' to '\u276B', '\u276C' to '\u276D', '\u276E' to '\u276F',
        '\u2770' to '\u2771', '\u2772' to '\u2773', '\u2774' to '\u2775', '\u27E6' to '\u27E7', '\u27E8' to '\u27E9',
        '\u27EA' to '\u27EB', '\u3008' to '\u3009', '\u300A' to '\u300B', '\u3010' to '\u3011', '\u3014' to '\u3015',
        '\u3016' to '\u3017', '\u3018' to '\u3019', '\u301A' to '\u301B', '\uFF08' to '\uFF09', '\uFF1C' to '\uFF1E',
        '\uFF3B' to '\uFF3D', '\uFF5B' to '\uFF5D', '\uFF5F' to '\uFF60', '\uFF62' to '\uFF63')
    private val MIRRORS: Map<Char, Char> = MIRROR_PAIRS.flatMap { (a, b) -> listOf(a to b, b to a) }.toMap()

    /** Зеркальная пара скобки для символа внутри RTL (правило L4). */
    fun mirror(c: Char): Char = MIRRORS[c] ?: c

    /** Невидимые знаки направления (LRM/RLM/ALM, LRE…RLO/PDF, LRI…PDI): влияют на порядок, но не рисуются. */
    fun isFormatControl(cp: Int): Boolean = cp == 0x200E || cp == 0x200F || cp == 0x061C || cp in 0x202A..0x202E || cp in 0x2066..0x2069

    /** Единица - невидимый знак направления (эффекты пропускают её, ширина 0). */
    fun isControlUnit(line: CharSequence, range: IntRange): Boolean =
        range.first in line.indices && range.last - range.first < 2 && isFormatControl(Character.codePointAt(line, range.first))

    /** Арабская письменность (буквы соединяются - рисуем словом). */
    fun isJoining(cp: Int): Boolean =
        Character.isLetter(cp) && (cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF || cp in 0x0700..0x074F)

    /** Комбинируемый знак (огласовки, ударения): рисуется вместе с предыдущей буквой. */
    fun isMark(cp: Int): Boolean {
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() || cp == 0x200D
    }
}
