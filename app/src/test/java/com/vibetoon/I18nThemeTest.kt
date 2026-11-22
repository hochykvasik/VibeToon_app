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
        // в английском файле не должно остаться русских букв (кроме названия языка «Русский»)
        val leaked = en.filter { (_, v) -> v != "Русский" && Regex("[А-Яа-я]").containsMatchIn(v) }
        assertTrue("русский текст в values-en: $leaked", leaked.isEmpty())
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
