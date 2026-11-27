package com.vibetoon

import com.vibetoon.domain.anim.SceneMath
import com.vibetoon.domain.project.SymbolMath
import com.vibetoon.domain.settings.AppLanguage
import com.vibetoon.domain.settings.DefaultNameKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 30: стандартные имена новых объектов («Слой», «Новый проект», «… копия», «Кадр N», «Символ N»…) получают
 * язык приложения в момент создания, а не остаются русскими в английском интерфейсе.
 */
class DefaultNamesTest {
    private fun pairs(): Map<String, String> = listOf("../tools/i18n/pairs.py", "../tools/i18n/pairs2.py")
        .flatMap { File(it).readLines() }.filter { '|' in it && !it.startsWith("#") }
        .associate { it.substringBefore('|') to it.substringAfter('|') }

    @Test fun everyDefaultNameHasEnglishTranslation() {
        val p = pairs()
        for (k in DefaultNameKeys.ALL) {
            val en = p[k]
            assertTrue("Нет перевода стандартного имени «$k»", en != null)
            assertFalse("Перевод «$k» содержит кириллицу: $en", Regex("[А-Яа-яЁё]").containsMatchIn(en!!))
            assertEquals("Число подстановок в «$k»", k.split("{}").size, en.split("{}").size)
        }
        assertEquals("Layer", p["Слой"])
        assertEquals("New project", p["Новый проект"])
        assertEquals("{} (copy)", p["{} (копия)"])
        assertEquals("{} copy", p["{} копия"])
        assertEquals("Frame", p["Кадр"])
    }

    @Test fun appLanguageFollowsSettingThenSystem() {
        assertEquals("en", AppLanguage.effective("en", "ru"))
        assertEquals("ru", AppLanguage.effective("ru", "en"))
        assertEquals("ru", AppLanguage.effective("", "ru"))
        assertEquals("en", AppLanguage.effective(null, "sv"))
        assertEquals("en", AppLanguage.effective("", "de"))
    }

    @Test fun framesPlanUsesProvidedFrameName() {
        val ru = SymbolMath.framesPlan(listOf(1, 2), listOf("", " "))
        assertEquals(listOf("Кадр 1", "Кадр 2"), ru.markers.map { it.name })
        val en = SymbolMath.framesPlan(listOf(1, 2, 3), listOf("", "Mouth", ""), frameName = { "Frame $it" })
        assertEquals(listOf("Frame 1", "Mouth", "Frame 3"), en.markers.map { it.name })
        assertEquals("Сцена", SceneMath.DEFAULT_PREFIX)
    }

    @Test fun creationCodeTranslatesDefaultNames() {
        val vm = File("src/main/java/com/vibetoon/ui/MainViewModel.kt").readText()
        for (raw in listOf("+ \" копия\"", "\"Символ \$n\", \"\"", "name = \"Символ \$n\"", "ifBlank { \"Слой\" }", "ifBlank { \"Группа\" }",
                "MediaKind.CAMERA, \"Камера\"", "MediaKind.TEXT, \"Текст\"", "(звук)\", \"assets", "GROUP, \"Группа \$count\"", "GROUP, \"Группа \$n\"", "IMAGE, \"\$prefix \$count\"")) {
            assertFalse("MainViewModel создаёт непереведённое имя: $raw", vm.contains(raw))
        }
        assertTrue(vm.contains("private fun dn(ru: String): String = com.vibetoon.ui.i18n.defaultName(getApplication<Application>(), ru)"))
        assertTrue(vm.contains("frameName = { \"\${dn(SymbolMath.DEFAULT_FRAME)} \$it\" }"))
        val repo = File("src/main/java/com/vibetoon/data/ProjectRepository.kt").readText()
        assertTrue(repo.contains("defaultName(appContext, \"Новый проект\")"))
        assertTrue(repo.contains("defaultName(appContext, \"\${data.meta.name} (копия)\")"))
        val theme = File("src/main/java/com/vibetoon/ui/theme/AppTheme.kt").readText()
        assertTrue("effectiveLang и defaultName используют одно правило", theme.contains("AppLanguage.effective(lang, Locale.getDefault().language)"))
    }
}
