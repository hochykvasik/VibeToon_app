package com.vibetoon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportMessageLocalizationTest {
    @Test fun importStatusAndErrorsHaveEnglishTemplates() {
        val pairs = listOf("../tools/i18n/pairs.py", "../tools/i18n/pairs2.py").flatMap { File(it).readLines() }
            .filter { '|' in it }.associate { it.substringBefore('|') to it.substringAfter('|') }
        val required = listOf("Добавлено: {}", "Добавлено: {}. Проект продлён до конца звука",
            "Добавлено: {}. Проект продлён до максимума, конец звука не поместился", "Ошибка: {}",
            "Не удалось добавить файл", "Не удалось открыть проект", "Проект импортирован")
        for (s in required) {
            val en = pairs[s]
            assertTrue("Missing translation for $s", en != null && !Regex("[А-Яа-яЁё]").containsMatchIn(en))
            assertTrue("Template arguments changed for $s", en!!.split("{}").size == s.split("{}").size)
        }
    }
}
