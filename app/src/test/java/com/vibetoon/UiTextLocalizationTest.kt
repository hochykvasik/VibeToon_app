package com.vibetoon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Каждая постоянная русская надпись Text("...") интерфейса имеет английский перевод: при смене языка не остаётся
 * «зависших» русских кнопок и подсказок. Строки с подстановками ($) проверяются шаблонами и сюда не входят.
 */
class UiTextLocalizationTest {
    private fun norm(s: String) = s.replace(Regex("\\\\+n"), "\n").replace("\\\"", "\"")

    @Test fun everyConstantUiTextHasTranslation() {
        val keys = listOf("../tools/i18n/pairs.py", "../tools/i18n/pairs2.py").flatMap { File(it).readLines() }
            .filter { '|' in it }.mapTo(HashSet()) { norm(it.substringBefore('|')) }
        val cyrillic = Regex("[А-Яа-яЁё]")
        val text = Regex("(?<![A-Za-z])Text\\(\\s*\"((?:[^\"\\\\$]|\\\\.)*)\"\\s*[,)]")
        val missing = ArrayList<String>()
        File("src/main/java/com/vibetoon/ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            for (m in text.findAll(f.readText())) {
                val s = norm(m.groupValues[1])
                if (cyrillic.containsMatchIn(s) && s !in keys) missing += "${f.name}: $s"
            }
        }
        assertTrue("Нет перевода:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
