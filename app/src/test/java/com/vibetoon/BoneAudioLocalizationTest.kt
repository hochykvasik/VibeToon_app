package com.vibetoon

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BoneAudioLocalizationTest {
    @Test fun englishStringsAndSubstitutionHaveNoRussianForStandardBoneAndAudio() {
        val lines = listOf("../tools/i18n/pairs.py", "../tools/i18n/pairs2.py").flatMap { File(it).readLines() }
        val pairs = lines.filter { '|' in it }.associate { it.substringBefore('|') to it.substringAfter('|') }
        val required = listOf("Кость: {}", "Кость {}", "Родитель: {}", "нет (корневая)", "Длина: {} px", "Привязано слоёв: {}",
            "Независимый поворот", "Кость не наследует вращение родителя, но наследует позицию", "Подготовка аудио…", "Время подготовки аудио истекло",
            "Не удалось подготовить аудио", "Не удалось подготовить волну аудио", "Волна не появилась на таймлайне: откройте дорожку; импортированный файл сохранён")
        for (key in required) {
            val en = pairs[key]
            assertNotNull("Missing $key", en)
            assertFalse("Cyrillic in $key", Regex("[А-Яа-яЁё]").containsMatchIn(en!!))
            assertEquals(key.split("{}").size, en.split("{}").size)
        }
        val bone = pairs.getValue("Кость {}").replace("{}", "1")
        val line = pairs.getValue("Кость: {}").replace("{}", bone)
        val root = pairs.getValue("Родитель: {}").replace("{}", pairs.getValue("нет (корневая)"))
        assertEquals("Bone: Bone 1", line)
        assertEquals("Parent: none (root)", root)
    }
    @Test fun nestedNamesAndAudioStatusAreConnectedToLocalization() {
        val shell = File("src/main/java/com/vibetoon/ui/shell/EditorShell.kt").readText()
        val screen = File("src/main/java/com/vibetoon/ui/MainScreen.kt").readText()
        val table = File("src/main/java/com/vibetoon/ui/i18n/I18nTable.kt").readText()
        assertTrue(shell.contains("boneDisplayName(bone.name)"))
        assertTrue(shell.contains("L(\"нет (корневая)\")"))
        assertTrue(screen.contains("Подготовка аудио…"))
        assertTrue(table.contains("\"Подготовка аудио…\" to R.string."))
    }
}
