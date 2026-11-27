package com.vibetoon

import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.OnionSettings
import com.vibetoon.domain.project.OnionSkin
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.SymbolForms
import com.vibetoon.domain.project.SymbolPlayback
import com.vibetoon.domain.project.TimeKey
import com.vibetoon.domain.settings.SettingsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 37: расширенная калька (до/после, Out of Pegs) и режим символа Play Once. */
class Package37OnionSymbolTest {
    private val cells = (0 until 8).map { FrameCell(it * 2, "c$it.png") }   // рисунки на кадрах 0,2,..14

    @Test fun defaultOnionMatchesOldBehaviour() {
        val g = OnionSkin.plan(cells, 6, OnionSettings())
        assertEquals(listOf("c2.png", "c4.png"), g.map { it.cell.file })
        assertEquals(listOf(0, OnionSkin.MAX_DEPTH), g.map { it.slot })
        assertEquals(1f, g[0].r, 0.01f); assertEquals(0.2f, g[0].g, 0.01f)   // до - красный
        assertEquals(0.2f, g[1].r, 0.01f); assertEquals(1f, g[1].g, 0.01f)   // после - зелёный
        assertEquals(0.35f, g[0].alpha, 1e-6f)
        assertTrue(g.all { it.tint })
    }

    @Test fun beforeAndAfterHaveOwnDepthAlphaAndColor() {
        val s = OnionSettings(before = 3, after = 1, beforeAlpha = 0.8f, afterAlpha = 0.2f, beforeColor = OnionSkin.BLUE, afterColor = OnionSkin.ORANGE)
        val g = OnionSkin.plan(cells, 8, s)
        assertEquals(listOf("c3.png", "c2.png", "c1.png", "c5.png"), g.map { it.cell.file })
        assertEquals(listOf(0, 1, 2, 4), g.map { it.slot })
        assertEquals(0.8f, g[0].alpha, 1e-6f)
        assertEquals(0.48f, g[1].alpha, 1e-5f)        // затухание ×0.6
        assertEquals(0.288f, g[2].alpha, 1e-5f)
        assertEquals(0.2f, g[3].alpha, 1e-6f)
        assertEquals(0x3D / 255f, g[0].r, 1e-6f); assertEquals(0xFF / 255f, g[0].b, 1e-6f)
        assertEquals(1f, g[3].r, 1e-6f); assertEquals(0x98 / 255f, g[3].g, 1e-6f)
        // без затухания все рисунки стороны одной непрозрачности
        assertTrue(OnionSkin.plan(cells, 8, s.copy(falloff = false)).filter { it.before }.all { it.alpha == 0.8f })
        // сторону можно выключить глубиной 0
        assertTrue(OnionSkin.plan(cells, 8, s.copy(before = 0)).none { it.before })
        assertTrue(OnionSkin.plan(cells, 8, s.copy(before = 0, after = 0)).isEmpty())
        assertTrue(OnionSkin.plan(cells.take(1), 0, s).isEmpty())
    }

    @Test fun normalizedClampsDepthAlphaAndSlotsStayInsideLayer() {
        val n = OnionSkin.normalized(OnionSettings(before = 9, after = -2, beforeAlpha = 5f, afterAlpha = Float.NaN, beforeColor = 0x123456))
        assertEquals(OnionSkin.MAX_DEPTH, n.before); assertEquals(0, n.after)
        assertEquals(OnionSkin.MAX_ALPHA, n.beforeAlpha, 0f); assertEquals(OnionSkin.DEFAULT_ALPHA, n.afterAlpha, 0f)
        assertEquals(0xFF123456.toInt(), n.beforeColor)
        val all = OnionSkin.plan(cells, 8, OnionSettings(before = 9, after = 9))
        assertTrue(all.all { it.slot in 0 until OnionSkin.SLOTS_PER_LAYER })
        assertEquals(all.size, all.map { it.slot }.distinct().size)
    }

    @Test fun settingsSurviveEncodeAndSettingsBackup() {
        val s = OnionSettings(2, 4, 0.5f, 0.25f, OnionSkin.PURPLE, OnionSkin.CYAN, falloff = false, tint = false, outOfPegs = true)
        val text = OnionSkin.encode(s)
        assertEquals(s, OnionSkin.decode(text))
        // строка проходит через резервную копию настроек без изменений
        val back = SettingsBackup.decode(SettingsBackup.encode(mapOf("onion_settings" to text)))!!
        assertEquals(s, OnionSkin.decode(back["onion_settings"] as String))
        assertEquals(OnionSettings(), OnionSkin.decode(null))
        assertEquals(OnionSettings(), OnionSkin.decode("garbage"))
        assertEquals(OnionSettings().copy(after = 3), OnionSkin.decode("v1;a=3;b=x;bc=ZZZ"))
    }

    @Test fun outOfPegsUsesGhostOwnFrame() {
        val g = OnionSkin.plan(cells, 8, OnionSettings())[0]
        assertEquals(6, OnionSkin.transformFrame(g, 8, true))
        assertEquals(8, OnionSkin.transformFrame(g, 8, false))
        assertFalse(OnionSkin.plan(cells, 8, OnionSettings(tint = false)).any { it.tint })
    }

    @Test fun playOnceHoldsLastFrameFromInstanceStart() {
        val dur = 10
        // от начала плашки (кадр 5): до неё первый кадр, потом 0..9, дальше держит 9
        assertEquals(0, SymbolPlayback.instanceFrame(emptyList(), 0, dur, true, SymbolPlayback.ONCE, 5))
        assertEquals(0, SymbolPlayback.instanceFrame(emptyList(), 5, dur, true, SymbolPlayback.ONCE, 5))
        assertEquals(4, SymbolPlayback.instanceFrame(emptyList(), 9, dur, true, SymbolPlayback.ONCE, 5))
        assertEquals(9, SymbolPlayback.instanceFrame(emptyList(), 14, dur, true, SymbolPlayback.ONCE, 5))
        assertEquals(9, SymbolPlayback.instanceFrame(emptyList(), 500, dur, true, SymbolPlayback.ONCE, 5))
        assertTrue(SymbolPlayback.finished(SymbolPlayback.ONCE, 14, dur, 5))
        assertFalse(SymbolPlayback.finished(SymbolPlayback.ONCE, 13, dur, 5))
        assertFalse(SymbolPlayback.finished(SymbolPlayback.LOOP, 99, dur, 5))
        // Loop и Hold как раньше; ключи времени главнее режима; неанимированный символ - кадр 0
        assertEquals(7, SymbolPlayback.instanceFrame(emptyList(), 17, dur, true, SymbolPlayback.LOOP, 5))
        assertEquals(0, SymbolPlayback.instanceFrame(emptyList(), 17, dur, true, SymbolPlayback.HOLD, 5))
        assertEquals(3, SymbolPlayback.instanceFrame(listOf(TimeKey(0, 3)), 17, dur, true, SymbolPlayback.ONCE, 5))
        assertEquals(0, SymbolPlayback.instanceFrame(emptyList(), 17, dur, false, SymbolPlayback.ONCE, 5))
    }

    @Test fun symbolFormsFrameAtKeepsOldOverloadAndSupportsOnce() {
        val marks = listOf(FrameMarker(0, "A"), FrameMarker(5, "B"))
        for (f in 0..30) {
            assertEquals(SymbolForms.frameAt(emptyList(), f, 10, true, true, marks), SymbolForms.frameAt(emptyList(), f, 10, true, SymbolPlayback.LOOP, 0, marks))
            assertEquals(SymbolForms.frameAt(emptyList(), f, 10, true, false, marks), SymbolForms.frameAt(emptyList(), f, 10, true, SymbolPlayback.HOLD, 0, marks))
        }
        assertEquals(9, SymbolForms.frameAt(emptyList(), 25, 10, true, SymbolPlayback.ONCE, 0, marks))
        assertEquals(3, SymbolForms.frameAt(emptyList(), 23, 10, true, SymbolPlayback.ONCE, 20, marks))
    }

    @Test fun modeFlagsAndNeedsAllFrames() {
        for (m in SymbolPlayback.ALL) { val (l, o) = SymbolPlayback.flags(m); assertEquals(m, SymbolPlayback.mode(l, o)) }
        assertEquals(SymbolPlayback.ONCE, SymbolPlayback.mode(true, true))
        assertEquals(false to false, SymbolPlayback.flags(42))
        assertTrue(SymbolPlayback.needsAllFrames(SymbolPlayback.ONCE, false, true))
        assertTrue(SymbolPlayback.needsAllFrames(SymbolPlayback.LOOP, false, true))
        assertFalse(SymbolPlayback.needsAllFrames(SymbolPlayback.HOLD, false, true))
        assertFalse(SymbolPlayback.needsAllFrames(SymbolPlayback.ONCE, true, true))
        assertFalse(SymbolPlayback.needsAllFrames(SymbolPlayback.ONCE, false, false))
    }

    @Test fun playOnceSurvivesJsonAndOldProjectsStayUnchanged() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val once = MediaItem(100001, MediaKind.SYMBOL, "S", "", w = 10f, h = 10f, symRef = 99999, symOnce = true)
        val loop = MediaItem(100002, MediaKind.SYMBOL, "S2", "", w = 10f, h = 10f, symRef = 99999, symLoop = true)
        val text = ProjectJson.encode(ProjectData(meta, media = listOf(once, loop)))
        val back = ProjectJson.decode(text).getOrThrow().media
        assertTrue(back.first { it.id == 100001 }.symOnce)
        assertFalse(back.first { it.id == 100001 }.symLoop)
        assertTrue(back.first { it.id == 100002 }.symLoop)
        assertFalse(back.first { it.id == 100002 }.symOnce)
        assertEquals(1, Regex("\"so\"").findAll(text).count())   // флаг пишется только у Play Once
    }
}
