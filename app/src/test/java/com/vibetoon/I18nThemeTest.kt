package com.vibetoon

import androidx.compose.ui.graphics.luminance
import com.vibetoon.ui.theme.ACCENTS
import com.vibetoon.ui.theme.schemeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class I18nThemeTest {
    private fun keys(path: String): Map<String, String> {
        val f = listOf(File(path), File("app/$path")).first { it.exists() }
        return Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>").findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun russianAndEnglishHaveSameKeys() {
        val ru = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")
        assertTrue(ru.size > 250)
        assertEquals(ru.keys, en.keys)
        for (key in ru.keys) {
            assertEquals("Placeholder count differs for $key", Regex("\\{\\}").findAll(ru.getValue(key)).count(), Regex("\\{\\}").findAll(en.getValue(key)).count())
        }
        // в английском файле не должно остаться русских букв (кроме названия языка «Русский»)
        val leaked = en.filter { (_, v) -> v != "Русский" && Regex("\\p{IsCyrillic}").containsMatchIn(v) }
        assertTrue("кириллица в values-en: $leaked", leaked.isEmpty())
        val oldTerms = en.filter { (_, v) -> v.contains("eyedropper", ignoreCase = true) || v.contains("pipette", ignoreCase = true) }
        assertTrue("используйте Color Picker: $oldTerms", oldTerms.isEmpty())
    }

    @Test fun brushAndStarterShapeNamesHaveEnglishResources() {
        val ru = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")
        val byRussian = ru.entries.associate { it.value to it.key }
        val names = com.vibetoon.domain.paint.BrushPresets.all().map { it.title } +
            com.vibetoon.domain.paint.ShapeKind.values().flatMap {
                listOf("Фигура: ${it.title}", "Фигура «${it.title}» добавлена", "Фигура «${it.title}» добавлена в векторный слой")
            } + listOf("Рекомендуем начать с учебного проекта", "Начать урок", "Не удалось создать учебный проект")
        for (name in names) {
            val key = byRussian[name]
            assertTrue("Нет ресурса для $name", key != null)
            val translated = en.getValue(key!!)
            assertTrue("Нет английского перевода для $name", translated != name && !Regex("\\p{IsCyrillic}").containsMatchIn(translated))
        }
    }

    @Test fun toolLabelsAndGeneratedPartSuffixAreTranslatedBeforeFormatting() {
        val ru = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")
        val byRussian = ru.entries.associate { it.value to it.key }
        for (word in listOf("Размер", "Сила", "Непр.", "Жёстк.", "Порог", "Расшир.", "Зазор", "часть")) {
            val key = byRussian[word]
            assertTrue("Нет ресурса для $word", key != null)
            assertTrue("Нет английского перевода для $word", !Regex("\\p{IsCyrillic}").containsMatchIn(en.getValue(key!!)))
        }
        assertEquals("part", en.getValue(byRussian.getValue("часть")))
    }

    @Test fun systemShareTitleUsesSelectedAppLanguageAndCriticalDiagnosticsHaveBothResources() {
        val ru = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")
        val byRussian = ru.entries.associate { it.value to it.key }
        for (word in listOf("Поделиться", "Некорректная сетка Warp", "Плотный Warp требует формат версии 4",
            "Не удалось восстановить текстуру; история не изменена", "Нет активного GL-контекста")) {
            val key = byRussian[word]
            assertTrue("Нет RU для $word", key != null)
            assertTrue("Нет EN для $word", en[key] != null && en[key] != word)
        }
        val f = listOf(File("src/main/java/com/vibetoon/ui/ExportDialog.kt"), File("app/src/main/java/com/vibetoon/ui/ExportDialog.kt")).first { it.exists() }
        val source = f.readText()
        assertTrue(source.contains("L(\"Поделиться\")"))
        assertTrue(source.contains("Intent.createChooser(send, title)"))
        assertTrue(!source.contains("Intent.createChooser(send, \"Поделиться\")"))
    }

    @Test fun eightAccentsAndSchemesDifferByTheme() {
        assertEquals(8, ACCENTS.size)
        for (a in ACCENTS) {
            val light = schemeFor(a.color, dark = false)
            val dark = schemeFor(a.color, dark = true)
            assertTrue(light.background.luminance() > 0.8f)          // светлые панели
            assertTrue(dark.background.luminance() < 0.1f)           // глубокий тёмный фон
            assertTrue(light.onSurface.luminance() < 0.2f)           // тёмный текст на светлом
            assertTrue(dark.onSurface.luminance() > 0.7f)            // светлый текст на тёмном
            assertNotEquals(light.primary, dark.primary)
        }
        assertNotEquals(schemeFor(ACCENTS[0].color, false).primary, schemeFor(ACCENTS[1].color, false).primary)
    }
}
