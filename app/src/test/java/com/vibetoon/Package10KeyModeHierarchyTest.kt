package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.ClipExtend
import com.vibetoon.domain.anim.EffectTracks
import com.vibetoon.domain.anim.KeyMode
import com.vibetoon.domain.anim.TransformHierarchy
import com.vibetoon.domain.model.TreeRow
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Пакет 10: режим ключей, иерархия трансформаций, расширение клипа, перевод заглушек. */
class Package10KeyModeHierarchyTest {
    private fun row(id: Int, parent: Int = -1, group: Boolean = false) =
        TreeRow(LayerInfo(id, "n$id", 0, 0, 10, 10, 1f, true, if (group) 1 else 0, parent, "norm", false), 0, group, true)

    // ---- режим ключей ----

    @Test fun keyModeShowsKeyedLayersWithTheirGroups() {
        val rows = listOf(row(1, group = true), row(2, 1), row(3, 1), row(4), row(5, group = true), row(6, 5))
        assertEquals(listOf(1, 3), KeyMode.rows(rows, setOf(3)).map { it.layer.id })        // группа слоя с ключами тоже видна
        assertEquals(listOf(1, 3, 4), KeyMode.rows(rows, setOf(3, 4, 99)).map { it.layer.id })   // порядок сохраняется
        assertEquals(emptyList<TreeRow>(), KeyMode.rows(rows, emptySet()))
    }

    @Test fun keyedCountsTransformEffectAndSymbolTimeKeys() {
        var anim = Animation()
        anim = anim.withKey(2, 5, LayerTransform(dx = 1f))
        anim = anim.withKey(EffectTracks.id(3, 0, 0), 7, LayerTransform(dx = 1f))
        val keyed = KeyMode.keyed(listOf(1, 2, 3, 4), anim.tracks, mapOf(4 to listOf("tk"), 1 to emptyList<Any>()))
        assertEquals(setOf(2, 3, 4), keyed)
        val frames = KeyMode.frames(listOf(2, 3, 4), anim.tracks, mapOf(4 to listOf(11)))
        assertEquals(setOf(5, 7, 11), frames)
    }

    @Test fun keyStepStaysInsideTheRange() {
        val frames = setOf(2, 10, 20, 40)
        assertEquals(20, KeyMode.step(frames, 10, true))
        assertEquals(2, KeyMode.step(frames, 10, false))
        assertNull(KeyMode.step(frames, 20, true, 0, 30))   // 40 за границей открытой сцены
        assertEquals(10, KeyMode.step(frames, 20, false, 5, 30))
        assertNull(KeyMode.step(frames, 10, false, 5, 30))
    }

    // ---- иерархия трансформаций ----

    @Test fun hierarchyTreeIsDepthFirstFromRoots() {
        val links = mapOf(2 to 1, 3 to 2, 4 to 1, 7 to 6)
        val tree = TransformHierarchy.tree(links, order = listOf(6, 1, 4, 2, 3, 7))
        assertEquals(listOf(6, 7, 1, 4, 2, 3), tree.map { it.id })
        assertEquals(listOf(0, 1, 0, 1, 1, 2), tree.map { it.depth })
        assertEquals(listOf(null, 6, null, 1, 1, 2), tree.map { it.parent })
        assertEquals(emptyList<TransformHierarchy.Row>(), TransformHierarchy.tree(emptyMap(), emptyList()))
    }

    @Test fun cyclesDoNotHangTheTree() {
        val cyc = mapOf(1 to 2, 2 to 1)
        assertEquals(setOf(1, 2), TransformHierarchy.tree(cyc, listOf(1, 2)).map { it.id }.toSet())
        assertEquals(listOf(2), TransformHierarchy.chain(1, cyc))
        assertEquals(setOf(2), TransformHierarchy.descendants(1, cyc))
    }

    @Test fun chainAndDescendants() {
        val links = mapOf(2 to 1, 3 to 2, 4 to 1)
        assertEquals(listOf(2, 1), TransformHierarchy.chain(3, links))
        assertEquals(setOf(2, 3, 4), TransformHierarchy.descendants(1, links))
        assertEquals(emptySet<Int>(), TransformHierarchy.descendants(3, links))
    }

    // ---- расширение клипа ----

    @Test fun clipExtendsToTheSceneEnd() {
        assertEquals(10 to 100, ClipExtend.extend(10, 40, 0, 100, plain = false))   // ретайминг: начало не трогаем
        assertEquals(0 to 100, ClipExtend.extend(10, 40, 0, 100, plain = true))
        assertEquals(50 to 120, ClipExtend.extend(60, 90, 50, 120, plain = true))  // открытая сцена 50..120
        assertEquals(0 to 200, ClipExtend.extend(0, 200, 50, 120, plain = true))   // уже длиннее - не сжимается
    }

    // ---- резервная копия настроек ----

    @Test fun settingsBackupRoundTrips() {
        val json = com.vibetoon.domain.settings.SettingsBackup.encode(mapOf("lang" to "en", "show_grid" to true, "grid_cells" to 24,
            "alpha" to 0.5f, "onboarded" to true, "junk" to setOf("x")))
        val back = com.vibetoon.domain.settings.SettingsBackup.decode(json)!!
        assertEquals(mapOf("alpha" to 0.5f, "grid_cells" to 24, "lang" to "en", "show_grid" to true), back)   // онбординг и чужие типы не переносятся
        assertNull(com.vibetoon.domain.settings.SettingsBackup.decode("{\"format\":\"other\",\"prefs\":{}}"))
        assertNull(com.vibetoon.domain.settings.SettingsBackup.decode("{\"format\":\"vibetoon-settings\",\"version\":99,\"prefs\":{}}"))
        assertNull(com.vibetoon.domain.settings.SettingsBackup.decode("not json"))
    }

    // ---- RU/EN: заглушки и вложенные значения шаблонов ----

    @Test fun everyStubTitleHasTranslation() {
        val keys = listOf("../tools/i18n/pairs.py", "../tools/i18n/pairs2.py").flatMap { File(it).readLines() }
            .filter { '|' in it }.mapTo(HashSet()) { it.substringBefore('|') }
        assertTrue("{} — в разработке" in keys)
        val stub = Regex("stub\\(\\s*\"((?:[^\"\\\\$]|\\\\.)*)\"\\s*\\)")
        val missing = ArrayList<String>()
        File("src/main/java/com/vibetoon/ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            for (m in stub.findAll(f.readText())) if (m.groupValues[1] !in keys) missing += "${f.name}: ${m.groupValues[1]}"
        }
        assertTrue("Нет перевода заглушки:\n" + missing.joinToString("\n"), missing.isEmpty())
        // заглушек «Переключение сцен», «Режим ключей», «Иерархия трансформаций» на таймлайне больше нет
        val tl = File("src/main/java/com/vibetoon/ui/timeline/TimelinePanel.kt").readText()
        for (s in listOf("stub(\"Переключение сцен\")", "stub(\"Режим ключей\")", "stub(\"Иерархия трансформаций\")", "stub(\"Расширение клипа\")"))
            assertTrue(s, s !in tl)
    }
}
