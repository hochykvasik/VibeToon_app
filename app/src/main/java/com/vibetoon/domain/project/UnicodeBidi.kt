package com.vibetoon.domain.project

/**
 * Спринт 40: уровни Unicode Bidi (UAX #9) для одной строки без Android-зависимостей - основа [TextBidi.levels].
 *
 * Реализовано: классы символов (L, R, AL, EN, ES, ET, AN, CS, NSM, BN, B, S, WS, ON и управляющие),
 * явные встраивания и переопределения LRE/RLE/LRO/RLO/PDF, изоляты LRI/RLI/FSI/PDI (правила X1-X10,
 * последовательности изолированных ранов), слабые типы W1-W7, парные скобки N0 (BD16, стек 63),
 * нейтральные N1/N2, неявные уровни I1/I2 и сброс хвостовых пробелов L1. Разворот (L2) и зеркалирование (L4) -
 * в [TextBidi.visualUnits]. Не реализовано: каноническая эквивалентность скобок U+2329/U+3008 и шейпинг (его делает шрифт).
 */
object UnicodeBidi {
    enum class BC { L, R, AL, EN, ES, ET, AN, CS, NSM, BN, B, S, WS, ON, LRE, LRO, RLE, RLO, PDF, LRI, RLI, FSI, PDI }

    const val MAX_DEPTH = 125
    private const val MAX_BRACKET_STACK = 63

    /** Класс Bidi кодовой точки; управляющие символы направления распознаются явно (не зависят от версии JDK/Android). */
    fun classOf(cp: Int): BC = when (cp) {
        0x202A -> BC.LRE; 0x202B -> BC.RLE; 0x202C -> BC.PDF; 0x202D -> BC.LRO; 0x202E -> BC.RLO
        0x2066 -> BC.LRI; 0x2067 -> BC.RLI; 0x2068 -> BC.FSI; 0x2069 -> BC.PDI
        0x200E -> BC.L; 0x200F -> BC.R; 0x061C -> BC.AL
        0x200D, 0x200C, 0x200B, 0xFEFF, 0x00AD, 0x2060 -> BC.BN
        0x0009, 0x001F, 0x000B -> BC.S
        0x000A, 0x000D, 0x001C, 0x001D, 0x001E, 0x0085, 0x2029 -> BC.B
        else -> when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> BC.L
            Character.DIRECTIONALITY_RIGHT_TO_LEFT -> BC.R
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> BC.AL
            Character.DIRECTIONALITY_EUROPEAN_NUMBER -> BC.EN
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR -> BC.ES
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR -> BC.ET
            Character.DIRECTIONALITY_ARABIC_NUMBER -> BC.AN
            Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR -> BC.CS
            Character.DIRECTIONALITY_NONSPACING_MARK -> BC.NSM
            Character.DIRECTIONALITY_BOUNDARY_NEUTRAL -> BC.BN
            Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR -> BC.B
            Character.DIRECTIONALITY_SEGMENT_SEPARATOR -> BC.S
            Character.DIRECTIONALITY_WHITESPACE -> BC.WS
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING -> BC.LRE
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> BC.LRO
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING -> BC.RLE
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> BC.RLO
            Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT -> BC.PDF
            else -> unassigned(cp)
        }
    }

    /** Неназначенные точки в блоках иврита/арабского по умолчанию R/AL (DerivedBidiClass), иначе нейтральные. */
    private fun unassigned(cp: Int): BC = when (cp) {
        in 0x0590..0x05FF, in 0x07C0..0x085F, in 0xFB1D..0xFB4F, in 0x10800..0x10FFF, in 0x1E800..0x1EFFF -> BC.R
        in 0x0600..0x07BF, in 0x0860..0x08FF, in 0xFB50..0xFDCF, in 0xFDF0..0xFDFF, in 0xFE70..0xFEFF -> BC.AL
        else -> BC.ON
    }

    private fun isIsolateInit(t: BC) = t == BC.LRI || t == BC.RLI || t == BC.FSI
    private fun isRemoved(t: BC) = t == BC.LRE || t == BC.RLE || t == BC.LRO || t == BC.RLO || t == BC.PDF || t == BC.BN
    private fun isNI(t: BC) = t == BC.B || t == BC.S || t == BC.WS || t == BC.ON || isIsolateInit(t) || t == BC.PDI

    /** Классы по char (обе половины суррогатной пары получают класс кодовой точки). */
    fun classes(line: CharSequence): Array<BC> {
        val n = line.length
        val out = Array(n) { BC.ON }
        var i = 0
        while (i < n) {
            val cp = Character.codePointAt(line, i)
            val c = classOf(cp)
            val cc = Character.charCount(cp)
            for (j in i until minOf(n, i + cc)) out[j] = c
            i += cc
        }
        return out
    }

    /** Индекс парного PDI для изолята в [i] (BD9) или -1. */
    private fun matchingPdi(t: Array<BC>, i: Int): Int {
        var depth = 1
        for (j in i + 1 until t.size) {
            if (isIsolateInit(t[j])) depth++
            else if (t[j] == BC.PDI) { depth--; if (depth == 0) return j }
        }
        return -1
    }

    /** P2/P3: первая сильная буква в [from, to), изоляты пропускаются; true - RTL, false - LTR, null - нет. */
    fun firstStrong(t: Array<BC>, from: Int, to: Int): Boolean? {
        var j = from
        while (j < to) {
            when {
                t[j] == BC.L -> return false
                t[j] == BC.R || t[j] == BC.AL -> return true
                isIsolateInit(t[j]) -> { val m = matchingPdi(t, j); if (m < 0) return null; j = m }
                t[j] == BC.PDI -> Unit
            }
            j++
        }
        return null
    }

    fun firstStrongRtl(text: CharSequence): Boolean? { val t = classes(text); return firstStrong(t, 0, t.size) }

    private class Entry(val level: Int, val override: BC?, val isolate: Boolean)

    /** Уровни для каждого char строки при базовом направлении [rtlBase]. */
    fun levels(line: CharSequence, rtlBase: Boolean): IntArray {
        val n = line.length
        val para = if (rtlBase) 1 else 0
        if (n == 0) return IntArray(0)
        val orig = classes(line)
        val t = orig.copyOf()
        val lv = IntArray(n) { para }
        // ---- X1-X8: явные уровни
        val stack = ArrayList<Entry>()
        stack += Entry(para, null, false)
        var overflowIsolate = 0; var overflowEmbed = 0; var validIsolate = 0
        fun nextOdd(l: Int) = if (l % 2 == 0) l + 1 else l + 2
        fun nextEven(l: Int) = if (l % 2 == 0) l + 2 else l + 1
        for (i in 0 until n) {
            val top = stack.last()
            when (val c = orig[i]) {
                BC.RLE, BC.LRE, BC.RLO, BC.LRO -> {
                    val nl = if (c == BC.RLE || c == BC.RLO) nextOdd(top.level) else nextEven(top.level)
                    lv[i] = top.level
                    if (nl <= MAX_DEPTH && overflowIsolate == 0 && overflowEmbed == 0)
                        stack += Entry(nl, when (c) { BC.RLO -> BC.R; BC.LRO -> BC.L; else -> null }, false)
                    else if (overflowIsolate == 0) overflowEmbed++
                }
                BC.RLI, BC.LRI, BC.FSI -> {
                    lv[i] = top.level
                    top.override?.let { t[i] = it }
                    val rtl = when (c) {
                        BC.RLI -> true
                        BC.LRI -> false
                        else -> { val m = matchingPdi(orig, i); firstStrong(orig, i + 1, if (m < 0) n else m) ?: false }
                    }
                    val nl = if (rtl) nextOdd(top.level) else nextEven(top.level)
                    if (nl <= MAX_DEPTH && overflowIsolate == 0 && overflowEmbed == 0) { validIsolate++; stack += Entry(nl, null, true) }
                    else overflowIsolate++
                }
                BC.PDI -> {
                    if (overflowIsolate > 0) overflowIsolate--
                    else if (validIsolate > 0) {
                        overflowEmbed = 0
                        while (!stack.last().isolate) stack.removeAt(stack.size - 1)
                        stack.removeAt(stack.size - 1)
                        validIsolate--
                    }
                    val cur = stack.last()
                    lv[i] = cur.level
                    cur.override?.let { t[i] = it }
                }
                BC.PDF -> {
                    lv[i] = top.level
                    if (overflowIsolate > 0) Unit
                    else if (overflowEmbed > 0) overflowEmbed--
                    else if (!top.isolate && stack.size >= 2) stack.removeAt(stack.size - 1)
                }
                BC.B -> lv[i] = para
                BC.BN -> lv[i] = top.level
                else -> { lv[i] = top.level; top.override?.let { t[i] = it } }
            }
        }
        // ---- X9/X10: раны уровней без удалённых символов и последовательности изолированных ранов
        val kept = (0 until n).filter { !isRemoved(orig[it]) }
        val explicit = lv.copyOf()   // sos/eos считаются по явным уровням, а не по уже разрешённым соседям
        if (kept.isNotEmpty()) {
            val runs = ArrayList<IntArray>()
            var s = 0
            for (k in 1..kept.size) {
                if (k == kept.size || lv[kept[k]] != lv[kept[s]]) { runs += IntArray(k - s) { kept[s + it] }; s = k }
            }
            val runOfStart = HashMap<Int, Int>()
            for ((ri, r) in runs.withIndex()) runOfStart[r[0]] = ri
            val used = BooleanArray(runs.size)
            // ран, который начинается с PDI закрытого изолята, продолжает последовательность изолята (обходится из неё)
            for (ri in runs.indices) {
                if (used[ri]) continue
                if (orig[runs[ri][0]] == BC.PDI && isMatchedPdi(orig, runs[ri][0]) && chainStart(runs, ri, orig, runOfStart)) continue
                val seq = ArrayList<Int>()
                var cur = ri
                while (true) {
                    used[cur] = true
                    runs[cur].forEach { seq += it }
                    val last = runs[cur].last()
                    if (!isIsolateInit(orig[last])) break
                    val m = matchingPdi(orig, last)
                    if (m < 0) break
                    val next = runOfStart[m] ?: break
                    if (used[next]) break
                    cur = next
                }
                resolveSequence(line, seq.toIntArray(), t, orig, lv, explicit, para, kept)
            }
        }
        // удалённые X9 символы - уровень предыдущего символа
        for (i in 0 until n) if (isRemoved(orig[i])) lv[i] = if (i > 0) lv[i - 1] else para
        // ---- L1: сегментные разделители и хвостовые пробелы (и изоляты/BN рядом с ними) - на уровень абзаца
        var trailing = true
        for (i in n - 1 downTo 0) {
            val c = orig[i]
            when {
                c == BC.S || c == BC.B -> { lv[i] = para; trailing = true }
                trailing && (c == BC.WS || isIsolateInit(c) || c == BC.PDI || isRemoved(c)) -> lv[i] = para
                else -> trailing = false
            }
        }
        return lv
    }

    /** Есть ли ран, который заканчивается изолятом с PDI в начале рана [ri] (тогда [ri] войдёт в его последовательность). */
    private fun chainStart(runs: List<IntArray>, ri: Int, orig: Array<BC>, runOfStart: Map<Int, Int>): Boolean {
        val pdi = runs[ri][0]
        for (r in runs) { val last = r.last(); if (isIsolateInit(orig[last]) && matchingPdi(orig, last) == pdi) return true }
        return false
    }

    private fun isMatchedPdi(t: Array<BC>, pdi: Int): Boolean {
        var depth = 0
        for (j in pdi - 1 downTo 0) {
            if (t[j] == BC.PDI) depth++
            else if (isIsolateInit(t[j])) { if (depth == 0) return true; depth-- }
        }
        return false
    }

    /** W1-W7, N0-N2, I1-I2 для одной последовательности изолированных ранов [seq] (индексы в логическом порядке). */
    private fun resolveSequence(line: CharSequence, seq: IntArray, t: Array<BC>, orig: Array<BC>, lv: IntArray, ex: IntArray, para: Int, kept: List<Int>) {
        val m = seq.size
        if (m == 0) return
        val level = ex[seq[0]]
        val e = if (level % 2 == 0) BC.L else BC.R
        // sos/eos по соседним сохранённым символам вне последовательности
        val firstPos = kept.binarySearch(seq[0])
        val prevLevel = if (firstPos > 0) ex[kept[firstPos - 1]] else para
        val lastIdx = seq[m - 1]
        val lastPos = kept.binarySearch(lastIdx)
        val nextLevel = if (isIsolateInit(orig[lastIdx]) || lastPos < 0 || lastPos + 1 >= kept.size) para else ex[kept[lastPos + 1]]
        val sos = if (maxOf(prevLevel, level) % 2 == 0) BC.L else BC.R
        val eos = if (maxOf(nextLevel, level) % 2 == 0) BC.L else BC.R
        val ty = Array(m) { t[seq[it]] }
        // W1
        for (k in 0 until m) if (ty[k] == BC.NSM) ty[k] = if (k == 0) sos else if (isIsolateInit(ty[k - 1]) || ty[k - 1] == BC.PDI) BC.ON else ty[k - 1]
        // W2
        var strong = sos
        for (k in 0 until m) when (ty[k]) {
            BC.L, BC.R, BC.AL -> strong = ty[k]
            BC.EN -> if (strong == BC.AL) ty[k] = BC.AN
            else -> Unit
        }
        // W3
        for (k in 0 until m) if (ty[k] == BC.AL) ty[k] = BC.R
        // W4
        for (k in 1 until m - 1) {
            if (ty[k] == BC.ES && ty[k - 1] == BC.EN && ty[k + 1] == BC.EN) ty[k] = BC.EN
            else if (ty[k] == BC.CS && ty[k - 1] == BC.EN && ty[k + 1] == BC.EN) ty[k] = BC.EN
            else if (ty[k] == BC.CS && ty[k - 1] == BC.AN && ty[k + 1] == BC.AN) ty[k] = BC.AN
        }
        // W5
        var k = 0
        while (k < m) {
            if (ty[k] != BC.ET) { k++; continue }
            var end = k
            while (end < m && ty[end] == BC.ET) end++
            val adj = (k > 0 && ty[k - 1] == BC.EN) || (end < m && ty[end] == BC.EN)
            if (adj) for (q in k until end) ty[q] = BC.EN
            k = end
        }
        // W6
        for (q in 0 until m) if (ty[q] == BC.ES || ty[q] == BC.ET || ty[q] == BC.CS) ty[q] = BC.ON
        // W7
        strong = sos
        for (q in 0 until m) when (ty[q]) {
            BC.L, BC.R -> strong = ty[q]
            BC.EN -> if (strong == BC.L) ty[q] = BC.L
            else -> Unit
        }
        // N0: парные скобки
        fun strongDir(x: BC): BC? = when (x) { BC.L -> BC.L; BC.R, BC.EN, BC.AN -> BC.R; else -> null }
        val pairs = bracketPairs(line, seq, ty)
        for ((open, close) in pairs) {
            var found: BC? = null
            for (q in open + 1 until close) {
                val d = strongDir(ty[q]) ?: continue
                if (d == e) { found = e; break }
                found = d
            }
            if (found == null) continue
            val res = if (found == e) e else {
                var ctx = sos
                for (q in open - 1 downTo 0) { val d = strongDir(ty[q]); if (d != null) { ctx = d; break } }
                if (ctx != e) found else e
            }
            ty[open] = res; ty[close] = res
            for (b in intArrayOf(open, close)) {
                var q = b + 1
                while (q < m && orig[seq[q]] == BC.NSM) { ty[q] = res; q++ }
            }
        }
        // N1/N2
        k = 0
        while (k < m) {
            if (!isNI(ty[k])) { k++; continue }
            var end = k
            while (end < m && isNI(ty[end])) end++
            val before = if (k == 0) sos else strongDir(ty[k - 1]) ?: e
            val after = if (end >= m) eos else strongDir(ty[end]) ?: e
            val res = if (before == after) before else e
            for (q in k until end) ty[q] = res
            k = end
        }
        // I1/I2
        for (q in 0 until m) {
            val x = ty[q]
            lv[seq[q]] = if (level % 2 == 0) when (x) { BC.R -> level + 1; BC.AN, BC.EN -> level + 2; else -> level }
            else when (x) { BC.L, BC.EN, BC.AN -> level + 1; else -> level }
        }
    }

    /** BD16: пары скобок (позиции в последовательности), отсортированные по открывающей. Скобка участвует, только если её тип ON. */
    private fun bracketPairs(line: CharSequence, seq: IntArray, ty: Array<BC>): List<Pair<Int, Int>> {
        val stack = ArrayList<Pair<Char, Int>>()
        val out = ArrayList<Pair<Int, Int>>()
        for (q in seq.indices) {
            if (ty[q] != BC.ON) continue
            val ch = line[seq[q]]
            val close = OPEN_TO_CLOSE[ch]
            if (close != null) {
                if (stack.size >= MAX_BRACKET_STACK) break
                stack += close to q
            } else if (ch in CLOSE_SET) {
                for (s in stack.indices.reversed()) {
                    if (stack[s].first == ch) {
                        out += stack[s].second to q
                        while (stack.size > s) stack.removeAt(stack.size - 1)
                        break
                    }
                }
            }
        }
        return out.sortedBy { it.first }
    }

    val OPEN_TO_CLOSE: Map<Char, Char> = mapOf(
        '(' to ')', '[' to ']', '{' to '}', '\u0F3A' to '\u0F3B', '\u0F3C' to '\u0F3D', '\u169B' to '\u169C',
        '\u2045' to '\u2046', '\u207D' to '\u207E', '\u208D' to '\u208E', '\u2308' to '\u2309', '\u230A' to '\u230B',
        '\u2329' to '\u232A', '\u2768' to '\u2769', '\u276A' to '\u276B', '\u276C' to '\u276D', '\u276E' to '\u276F',
        '\u2770' to '\u2771', '\u2772' to '\u2773', '\u2774' to '\u2775', '\u27C5' to '\u27C6', '\u27E6' to '\u27E7',
        '\u27E8' to '\u27E9', '\u27EA' to '\u27EB', '\u2983' to '\u2984', '\u2985' to '\u2986', '\u3008' to '\u3009',
        '\u300A' to '\u300B', '\u300C' to '\u300D', '\u300E' to '\u300F', '\u3010' to '\u3011', '\u3014' to '\u3015',
        '\u3016' to '\u3017', '\u3018' to '\u3019', '\u301A' to '\u301B', '\uFF08' to '\uFF09', '\uFF3B' to '\uFF3D',
        '\uFF5B' to '\uFF5D', '\uFF5F' to '\uFF60', '\uFF62' to '\uFF63'
    )
    private val CLOSE_SET: Set<Char> = OPEN_TO_CLOSE.values.toSet()
}
