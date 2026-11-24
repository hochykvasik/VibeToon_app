package com.vibetoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 30: полнота и чистота словаря RU/EN.
 *  - каждая русская строка в коде приложения (не только Text("…"): сообщения ViewModel, ошибки data/domain, подписи
 *    иконок) есть в словаре целиком или подходит под шаблон с {}; склейки вида "Слой: " + имя не проходят;
 *  - строка без подстановок не должна переводиться «по частям» общим шаблоном («Слой {}» для «Слой готовится»);
 *  - подписи иконок для экранного диктора (contentDescription) переводятся через L();
 *  - в словаре нет повторов ключей, кириллицы в английской половине и термина «keyframe» (единый термин - key).
 * Рабочая папка тестов - app/, словарь читается из ../tools/i18n.
 */
class I18nCoverageTest {
    data class Entry(val ru: String, val en: String, val where: String)

    private val cyrillic = Regex("[А-Яа-яЁё]")

    private fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        for ((name, v) in listOf("pairs.py" to "PAIRS", "pairs2.py" to "PAIRS2")) {
            val lines = File("../tools/i18n/$name").readLines()
            var inside = false
            for ((i, line) in lines.withIndex()) {
                if (!inside) { if (line.startsWith("$v = \"\"\"")) inside = true; continue }
                if (line.startsWith("\"\"\"")) break
                val bar = line.indexOf('|')
                assertTrue("$name:${i + 1}: строка без «|»", bar > 0)
                if (line.startsWith("#")) continue
                out += Entry(py(line.substring(0, bar)), py(line.substring(bar + 1)), "$name:${i + 1}")
            }
        }
        return out
    }

    /** Как Python читает строку в тройных кавычках и как gen.py разворачивает перевод строки: двойной обратный слеш + n в файле -> перевод строки. */
    private fun py(s: String) = s.replace("\\\\", "\\").replace("\\n", "\n")

    private class Dict(entries: List<Entry>) {
        val exact: Set<String> = entries.mapTo(HashSet()) { it.ru }
        val templates: List<Regex> = entries.filter { "{}" in it.ru }.map { e ->
            Regex("^" + e.ru.split("{}").joinToString("(.+?)") { Regex.escape(it) } + "$", RegexOption.DOT_MATCHES_ALL)
        }
    }

    // ---------------------------------------------------------------- строки Kotlin: лексер без компилятора

    /** Литерал: строка файла и текст, где подстановки `$x` / `${…}` заменены на {}; соседние "a" + "b" склеены. */
    data class Literal(val line: Int, val text: String)

    object KtStrings {
        fun literals(src: String): List<Literal> {
            val out = ArrayList<Literal>()
            var i = 0
            var line = 1
            var joinTo = -1   // индекс литерала, к которому приклеивается следующий ("a" + "b")
            val n = src.length
            fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

            /** Разбор строки с позиции открывающей кавычки; вложенные литералы из ${…} тоже попадают в [out]. Возвращает конец и текст. */
            fun parseString(start: Int): Pair<Int, String> {
                val raw = src.startsWith("\"\"\"", start)
                var p = start + if (raw) 3 else 1
                val sb = StringBuilder()
                val startLine = line
                while (p < n) {
                    val c = src[p]
                    if (raw && src.startsWith("\"\"\"", p)) {
                        p += 3
                        while (p < n && src[p] == '"') { sb.append('"'); p++ }
                        return p to sb.toString()
                    }
                    if (!raw && c == '"') return (p + 1) to sb.toString()
                    if (!raw && c == '\\' && p + 1 < n) {
                        when (val e = src[p + 1]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                            'u' -> { sb.append(src.substring(p + 2, minOf(n, p + 6)).toIntOrNull(16)?.toChar() ?: '?'); p += 4 }
                            else -> sb.append(e)
                        }
                        p += 2; continue
                    }
                    if (c == '$' && p + 1 < n && src[p + 1] == '{') {
                        var depth = 1
                        p += 2
                        while (p < n && depth > 0) {
                            when (src[p]) {
                                '"' -> {
                                    val ln = line
                                    val (e, t) = parseString(p)
                                    out += Literal(ln, t)
                                    p = e; continue
                                }
                                '{' -> depth++
                                '}' -> depth--
                                '\n' -> line++
                            }
                            p++
                        }
                        sb.append("{}"); continue
                    }
                    if (c == '$' && p + 1 < n && (src[p + 1].isLetter() || src[p + 1] == '_')) {
                        p++
                        while (p < n && isIdent(src[p])) p++
                        sb.append("{}"); continue
                    }
                    if (c == '\n') line++
                    sb.append(c); p++
                }
                error("Незакрытая строка с $startLine")
            }

            while (i < n) {
                val c = src[i]
                when {
                    src.startsWith("//", i) -> { val e = src.indexOf('\n', i); i = if (e < 0) n else e }
                    src.startsWith("/*", i) -> {
                        var depth = 1
                        i += 2
                        while (i < n && depth > 0) {
                            if (src.startsWith("/*", i)) { depth++; i += 2 }
                            else if (src.startsWith("*/", i)) { depth--; i += 2 }
                            else { if (src[i] == '\n') line++; i++ }
                        }
                    }
                    c == '\'' -> {
                        val m = Regex("'(\\\\u[0-9a-fA-F]{4}|\\\\.|[^\\\\'])'").find(src, i)
                        i = if (m != null && m.range.first == i) m.range.last + 1 else i + 1
                    }
                    c == '"' -> {
                        val ln = line
                        val (e, t) = parseString(i)
                        if (joinTo >= 0) out[joinTo] = out[joinTo].copy(text = out[joinTo].text + t)
                        else out += Literal(ln, t)
                        val idx = if (joinTo >= 0) joinTo else out.size - 1
                        i = e
                        val plus = Regex("\\s*\\+\\s*(?=\")").find(src, i)
                        if (plus != null && plus.range.first == i && !src.startsWith("\"\"\"", plus.range.last + 1)) {
                            line += plus.value.count { it == '\n' }
                            i = plus.range.last + 1
                            joinTo = idx
                        } else joinTo = -1
                    }
                    else -> { if (c == '\n') line++; i++ }
                }
            }
            return out
        }
    }

    /** Служебные строки, которые не показываются как текст: регулярные выражения, id подсказок обучения, префикс разбора. */
    private fun isService(s: String) = s.startsWith("^") || s.startsWith("chip:") || s == "Повреждённый файл проекта: "

    private fun covered(s: String, d: Dict): Boolean {
        if (!cyrillic.containsMatchIn(s) || s in d.exact || isService(s)) return true
        if (d.templates.none { it.matches(s) }) return false
        // целая фраза без подстановок, найденная только общим шаблоном, перевелась бы наполовину
        return "{}" in s || '%' in s
    }

    private fun sources() = File("src/main/java/com/vibetoon").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name != "I18nTable.kt" }.toList()

    @Test fun lexerJoinsConcatenationAndMarksSubstitutions() {
        val src = "val a = \"Слой: \" + name\nval b = \"Кадр \${x + 1} из \$total\" +\n    \" готов\" // \"коммент\"\nval c = '\"'\nval d = \"\${if (q) \"Да\" else \"Нет\"}\""
        val t = KtStrings.literals(src).map { it.text }
        assertEquals(listOf("Слой: ", "Кадр {} из {} готов", "Да", "Нет", "{}"), t)
    }

    @Test fun everyRussianLiteralInAppCodeIsTranslated() {
        val d = Dict(entries())
        val missing = ArrayList<String>()
        for (f in sources()) for (l in KtStrings.literals(f.readText())) {
            if (!covered(l.text, d)) missing += "${f.path.substringAfter("com/vibetoon/")}:${l.line}: ${l.text.take(120)}"
        }
        assertTrue("Нет перевода (${missing.size}):\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun iconContentDescriptionsGoThroughL() {
        val call = Regex("(?<![A-Za-z0-9_])(?:androidx\\.compose\\.material3\\.|androidx\\.compose\\.foundation\\.)?(Icon|Image)\\(")
        val bad = ArrayList<String>()
        var checked = 0
        for (f in File("src/main/java/com/vibetoon/ui").walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val s = f.readText()
            for (m in call.findAll(s)) {
                val lineStart = s.lastIndexOf('\n', m.range.first) + 1
                val before = s.substring(lineStart, m.range.first)
                if (before.trimStart().startsWith("*") || before.trimStart().startsWith("//") || before.trimEnd().endsWith("fun")) continue
                val args = topLevelArgs(s, m.range.last + 1)
                if (args.size < 2) continue
                val a = args[1].trim()
                if (Regex("^[A-Za-z]+\\s*=").containsMatchIn(a)) continue   // именованный аргумент - не подпись
                checked++
                if (a != "null" && !a.startsWith("L(") && !a.startsWith("com.vibetoon.ui.i18n.L(")) {
                    bad += "${f.name}:${s.substring(0, m.range.first).count { it == '\n' } + 1}: $a"
                }
            }
        }
        assertTrue("Подписи иконок найдены: $checked", checked >= 80)
        assertTrue("contentDescription без L():\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** Аргументы вызова верхнего уровня, начиная сразу после «(». */
    private fun topLevelArgs(s: String, from: Int): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var i = from
        var start = from
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> { i = skipString(s, i); continue }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { if (depth == 0) { out += s.substring(start, i); return out }; depth-- }
                ',' -> if (depth == 0) { out += s.substring(start, i); start = i + 1 }
                else -> if (c == '\'' && i + 2 < s.length) { i = s.indexOf('\'', i + if (s[i + 1] == '\\') 3 else 2) + 1; continue }
            }
            i++
        }
        return out
    }

    private fun skipString(s: String, at: Int): Int {
        if (s.startsWith("\"\"\"", at)) return s.indexOf("\"\"\"", at + 3) + 3
        var i = at + 1
        while (s[i] != '"') {
            if (s[i] == '\\') { i += 2; continue }
            if (s[i] == '$' && s[i + 1] == '{') {
                var d = 1
                i += 2
                while (d > 0) {
                    when (s[i]) { '"' -> { i = skipString(s, i); continue }; '{' -> d++; '}' -> d-- }
                    i++
                }
                continue
            }
            i++
        }
        return i + 1
    }

    @Test fun dictionaryHasNoDuplicatesConflictsOrMixedTerms() {
        val e = entries()
        val dups = e.groupBy { it.ru }.filterValues { it.size > 1 }
            .map { (ru, list) -> "$ru: " + list.joinToString { "${it.where}=${it.en}" } }
        assertTrue("Повторы ключей (gen.py берёт только первый):\n" + dups.joinToString("\n"), dups.isEmpty())
        val endonyms = setOf("Русский")
        val cyr = e.filter { cyrillic.containsMatchIn(it.en) && it.en !in endonyms }.map { "${it.where}: ${it.en}" }
        assertTrue("Кириллица в английском переводе:\n" + cyr.joinToString("\n"), cyr.isEmpty())
        val keyframe = e.filter { Regex("key\\s*-?frames?", RegexOption.IGNORE_CASE).containsMatchIn(it.en) }.map { "${it.where}: ${it.en}" }
        assertTrue("Термин keyframe - в EN используется key:\n" + keyframe.joinToString("\n"), keyframe.isEmpty())
    }

    @Test fun textEffectRangeUsesFromToAndRuUiHasNoEnglishFlow() {
        val en = entries().associate { it.ru to it.en }
        assertEquals("From", en["От"])
        assertEquals("To", en["До"])
        assertEquals("Flow", en["Поток"])
        assertFalse("«Из» -> «Of» убран: в эффектах текста диапазон «От»/«До»", "Из" in en)
        val inspector = File("src/main/java/com/vibetoon/ui/inspector/InspectorPanel.kt").readText()
        assertTrue(inspector.contains("ScrubSlider(\"От\", fx.weightFrom") && inspector.contains("ScrubSlider(\"До\", fx.weightTo"))
        val shell = File("src/main/java/com/vibetoon/ui/shell/EditorShell.kt").readText()
        assertFalse("Подпись кисти «Flow» в русском интерфейсе", shell.contains("ScrubBox(\"Flow\""))
        assertTrue(shell.contains("ScrubBox(\"Поток\", p.flow"))
    }
}
