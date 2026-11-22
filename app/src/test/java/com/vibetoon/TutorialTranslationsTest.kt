package com.vibetoon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TutorialTranslationsTest {
    @Test fun allStaticTutorialTextHasEnglishTranslation() {
        val source = File("src/main/java/com/vibetoon/ui/projects/TutorialsScreen.kt").readText()
            .substringAfter("val TUTORIALS: List<Tutorial> = listOf(")
            .substringBefore("/** Ряд карточек")
        val translations = listOf("../tools/i18n/pairs.py", "../tools/i18n/pairs2.py")
            .flatMap { File(it).readLines() }
            .filter { '|' in it }.associate { it.substringBefore('|') to it.substringAfter('|') }
        val literals = Regex("\"([^\"\\n]*)\"").findAll(source).map { it.groupValues[1] }
            .filter { Regex("[А-Яа-яЁё]").containsMatchIn(it) }.toSet()
        assertTrue("Нет строк уроков для проверки", literals.isNotEmpty())
        for (text in literals) {
            val en = translations[text]
            assertTrue("Нет английского перевода: $text", en != null && en != text && !Regex("[А-Яа-яЁё]").containsMatchIn(en))
        }
    }
}
