package com.vibetoon

import com.vibetoon.domain.anim.ClipSpeed
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.FrameMath
import com.vibetoon.domain.project.JsonException
import com.vibetoon.domain.project.MiniJson
import com.vibetoon.domain.project.OnionSettings
import com.vibetoon.domain.project.OnionSkin
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectListMath
import com.vibetoon.domain.project.StarterProject
import com.vibetoon.domain.settings.SettingsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Спринт 61: сквозная проверка - найденные ошибки (длина рисунка, разбор повреждённых файлов, восстановление настроек). */
class Sprint61Test {
    // ---- блок 1: проекты, повреждённые данные ----

    @Test fun deeplyNestedJsonIsAnErrorNotACrash() {
        for (bad in listOf("[".repeat(100_000), "{\"a\":".repeat(100_000), "[" + "{\"x\":[".repeat(5_000))) {
            try { MiniJson.parse(bad); fail("разбор должен отказать") } catch (e: JsonException) { /* ожидаемо */ }
            catch (e: StackOverflowError) { fail("переполнение стека вместо ошибки разбора") }
            assertTrue(ProjectJson.decode(bad).isFailure)
            assertTrue(ProjectJson.decodeMeta(bad).isFailure)
        }
        // нормальная вложенность читается
        val ok = "[".repeat(MiniJson.MAX_DEPTH) + "]".repeat(MiniJson.MAX_DEPTH)
        MiniJson.parse(ok)
        try { MiniJson.parse("[".repeat(MiniJson.MAX_DEPTH + 1) + "]".repeat(MiniJson.MAX_DEPTH + 1)); fail() } catch (e: JsonException) { }
        // пропуск значений в заголовке (topLevel) тоже защищён
        val deepTail = "{\"id\":\"p\",\"x\":" + "[".repeat(50_000) + "}"
        assertTrue(ProjectJson.decodeMeta(deepTail).isFailure)
        assertNull(ProjectJson.withTopLevel(deepTail, mapOf("name" to "n")))
    }

    @Test fun parserIsReusableAfterDepthError() {
        try { MiniJson.parse("[".repeat(1000)) } catch (e: JsonException) { }
        val v = MiniJson.parse("{\"a\":[[1],[2]]}") as Map<*, *>
        assertEquals(2, (v["a"] as List<*>).size)
    }

    @Test fun corruptedProjectFieldsNeverThrow() {
        val text = ProjectJson.encode(StarterProject.data("p1", 1000L))
        val values = listOf("\"x\"", "1", "-1", "[]", "{}", "null", "true", "1e308", "[1,2]", "{\"1\":\"a\"}", "[{}]", "[[\"a\"]]", "{\"-5\":[1,\"a\",{}]}")
        for (m in MiniJson.topLevel(text)) for (v in values) {
            val t = text.substring(0, m.valueStart) + v + text.substring(m.valueEnd)
            ProjectJson.decode(t); ProjectJson.decodeMeta(t)   // Result, без исключений
        }
        // обрезанный файл (запись прервана) - ошибка открытия
        for (cut in listOf(1, text.length / 3, text.length / 2, text.length - 1)) assertTrue(ProjectJson.decode(text.substring(0, cut)).isFailure)
        // исходный читается и совпадает при повторной записи
        val back = ProjectJson.decode(text).getOrThrow()
        assertEquals(text, ProjectJson.encode(back))
    }

    @Test fun projectNamesAreCleanedOnRename() {
        assertEquals("Мой мульт", ProjectListMath.cleanProjectName("  Мой\nмульт\t "))
        assertEquals("", ProjectListMath.cleanProjectName(" \n\t "))
        assertEquals(ProjectListMath.MAX_PROJECT_NAME, ProjectListMath.cleanProjectName("я".repeat(500)).length)
        assertEquals("A B", ProjectListMath.cleanProjectName("A\u0000\u2028B"))
        assertEquals("Сцена 1 (копия)", ProjectListMath.cleanProjectName("Сцена 1 (копия)"))
    }

    @Test fun settingsRestoreKeepsTypesOfCurrentSettings() {
        assertEquals(7, SettingsBackup.restorable(3, 7L))
        assertEquals(7, SettingsBackup.restorable(3, 7.0))
        assertNull("дробное в целую настройку не пишется", SettingsBackup.restorable(3, 7.5))
        assertNull(SettingsBackup.restorable(3, 1e12))
        assertNull(SettingsBackup.restorable(3, Double.NaN))
        assertNull("строка в целую - нет", SettingsBackup.restorable(3, "7"))
        assertEquals(true, SettingsBackup.restorable(false, true))
        assertNull(SettingsBackup.restorable(false, 1))
        assertEquals("ru", SettingsBackup.restorable("en", "ru"))
        assertEquals(0.5f, SettingsBackup.restorable(1f, 0.5) as Float, 0f)
        assertNull(SettingsBackup.restorable(1f, Double.POSITIVE_INFINITY))
        assertEquals(5L, SettingsBackup.restorable(1L, 5))
        // ключа ещё нет (свежая установка): дробное число больше не пишется как Float (getInt потом падал)
        assertNull(SettingsBackup.restorable(null, 0.25))
        assertEquals(16, SettingsBackup.restorable(null, 16L))
        assertEquals(true, SettingsBackup.restorable(null, true))
        assertNull(SettingsBackup.restorable(null, listOf(1)))
    }

    @Test fun settingsBackupRoundTripThenRestore() {
        val cur = mapOf<String, Any>("left" to false, "grid_cells" to 16, "lang" to "", "stabilization" to 3)
        val text = SettingsBackup.encode(mapOf("left" to true, "grid_cells" to 24, "lang" to "en", "stabilization" to 7))
        val values = SettingsBackup.decode(text)!!
        val restored = values.mapNotNull { (k, v) -> SettingsBackup.restorable(cur[k], v)?.let { k to it } }.toMap()
        assertEquals(true, restored["left"]); assertEquals(24, restored["grid_cells"]); assertEquals("en", restored["lang"]); assertEquals(7, restored["stabilization"])
        assertNull(SettingsBackup.decode("{" + "[".repeat(10_000)))
        assertNull(SettingsBackup.decode(""))
    }

    // ---- блок 2: таймлайн ----

    @Test fun drawingLengthNudgeStaysWithin1to48() {
        assertEquals(2, FrameMath.nudgedLength(1, +1))
        assertNull("меньше 1 нельзя", FrameMath.nudgedLength(1, -1))
        assertEquals(48, FrameMath.nudgedLength(47, +1))
        assertNull("больше 48 «+» не растягивает", FrameMath.nudgedLength(48, +1))
        assertNull("длинная экспозиция «+» не укорачивается", FrameMath.nudgedLength(120, +1))
        assertEquals("«−» убавляет по кадру", 119, FrameMath.nudgedLength(120, -1))
        assertEquals(48, FrameMath.nudgedLength(40, +100))
        assertEquals(1, FrameMath.nudgedLength(5, -100))
        assertNull(FrameMath.nudgedLength(5, 0))
        assertEquals(1, FrameMath.nudgedLength(2, Int.MIN_VALUE))
        assertEquals(48, FrameMath.nudgedLength(2, Int.MAX_VALUE))
    }

    @Test fun holdingPlusFrom1ReachesExactly48() {
        var cells = listOf(FrameCell(0, "a.png", 1), FrameCell(100, "b.png", 2))
        var steps = 0
        while (true) {
            val cur = FrameMath.active(cells, 0)!!
            val len = FrameMath.lengthOf(cells, cur, 200)
            val next = FrameMath.nudgedLength(len, +1) ?: break
            cells = FrameMath.withLength(cells, 0, next); steps++
            assertTrue(steps < 100)
        }
        assertEquals(48, FrameMath.lengthOf(cells, FrameMath.active(cells, 0)!!, 200))
        assertEquals(47, steps)
        // упор в соседний рисунок раньше 48
        val tight = listOf(FrameCell(0, "a.png", 1), FrameCell(10, "b.png", 2))
        val t = FrameMath.withLength(tight, 0, FrameMath.nudgedLength(10, +1)!!)
        assertEquals(10, FrameMath.lengthOf(t, t.first(), 200))
    }

    @Test fun insertDuplicateDeleteKeepNeighbours() {
        val cells = listOf(FrameCell(0, "a", 2), FrameCell(2, "b", 2), FrameCell(4, "c", 2))
        // «+ Кадр» в середине рисунка длиной 2 - после его конца; кадр 2 занят следующим рисунком - первый свободный (3)
        assertEquals(3, FrameMath.insertAt(cells, 1, 24))
        assertEquals(1, FrameMath.insertAt(listOf(FrameCell(0, "a", 1)), 0, 24))
        assertNull("за концом сцены места нет", FrameMath.insertAt(listOf(FrameCell(0, "a", 0)), 23, 24))
        val ins = FrameMath.insert(listOf(FrameCell(0, "a", 0), FrameCell(10, "b", 2)), FrameCell(5, "n", 8))
        assertEquals(listOf(0, 5, 10), ins.map { it.start })
        assertTrue("новый не заходит на следующий", FrameMath.end(ins, ins[1], 24) <= 10)
        val after = FrameMath.without(cells, 2)
        assertEquals(listOf("a", "c"), after.map { it.file })
        assertEquals("последний рисунок не удаляется", listOf(cells[0]), FrameMath.without(listOf(cells[0]), 0))
        assertNull("пустой слой - нет активного", FrameMath.active(emptyList(), 3))
        assertEquals("до первого рисунка держится первый", "a", FrameMath.active(listOf(FrameCell(5, "a")), 0)!!.file)
    }

    @Test fun onionRecomputesAfterFrameEditsAndUndo() {
        val s = OnionSettings(before = 1, after = 1)
        val cells = listOf(FrameCell(0, "a", 2), FrameCell(2, "b", 2), FrameCell(4, "c", 2))
        val g1 = OnionSkin.plan(cells, 2, s).map { it.cell.file }
        assertEquals(listOf("a", "c"), g1)
        val edited = FrameMath.without(cells, 4)
        assertEquals(listOf("a"), OnionSkin.plan(edited, 2, s).map { it.cell.file })
        assertEquals("Undo возвращает тех же призраков", g1, OnionSkin.plan(cells, 2, s).map { it.cell.file })
        assertTrue("один рисунок - призраков нет", OnionSkin.plan(listOf(cells[0]), 0, s).isEmpty())
        val bad = OnionSkin.normalized(OnionSettings(before = 99, after = -3, beforeAlpha = Float.NaN))
        assertTrue(bad.before <= OnionSkin.MAX_DEPTH && bad.after == 0 && bad.beforeAlpha.isFinite())
        assertEquals(OnionSettings(), OnionSkin.decode("мусор"))
        val enc = OnionSkin.encode(OnionSettings(before = 3, after = 2, step = 2))
        assertEquals(OnionSettings(before = 3, after = 2, step = 2), OnionSkin.decode(enc))
    }

    @Test fun playbackSpeedRange() {
        assertEquals(0.1f, ClipSpeed.clamp(0f), 0f)
        assertEquals(3f, ClipSpeed.clamp(99f), 0f)
        assertEquals(1f, ClipSpeed.clamp(Float.NaN), 0f)
        assertEquals("0.1x", ClipSpeed.label(0.1f)); assertEquals("0.15x", ClipSpeed.label(0.15f))
        assertEquals("3x", ClipSpeed.label(3f))
        var v = 0.1f; var n = 0
        while (v < 3f && n < 200) { v = ClipSpeed.nudge(v, true); n++ }
        assertEquals(3f, v, 0f)
        assertEquals(3f, ClipSpeed.nudge(3f, true), 0f)
        assertEquals(0.1f, ClipSpeed.nudge(0.1f, false), 0f)
        assertEquals(0.1f, ClipSpeed.fromSlider(0f), 0.001f); assertEquals(3f, ClipSpeed.fromSlider(1f), 0.001f)
        assertFalse(ClipSpeed.toSlider(Float.NaN).isNaN())
    }
}
