package com.vibetoon

import com.vibetoon.domain.anim.SceneCanvas
import com.vibetoon.domain.anim.SceneMath
import com.vibetoon.domain.anim.SceneRange
import com.vibetoon.domain.anim.SceneTransition
import com.vibetoon.domain.project.ProjectArchive
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 24: отдельные холсты сцен (свои слои, свой фон) и переходы между сценами. */
class Package24SceneCanvasTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    // слой 1 - только сцена A, слой 2 - только сцена B, слой 3 - общий
    private fun two(type: SceneTransition = SceneTransition.CUT, frames: Int = 0) = listOf(
        SceneRange(1, "A", 0, 50, layers = setOf(1)),
        SceneRange(2, "B", 50, 100, layers = setOf(2), transition = type.key, transitionFrames = frames)
    )

    private fun near(e: Float, a: Float) = assertEquals(e, a, 0.02f)

    @Test fun ownLayersAreVisibleOnlyInTheirScene() {
        val sc = two()
        val a = SceneCanvas.look(sc, 10)
        assertEquals(0f, a.alpha[2])
        assertNull(a.alpha[1])
        assertNull(a.alpha[3])   // общий слой не трогается
        val b = SceneCanvas.look(sc, 70)
        assertEquals(0f, b.alpha[1])
        assertNull(b.alpha[2])
        // вне сцен своих слоёв не видно
        val gap = listOf(SceneRange(1, "A", 0, 40, layers = setOf(1)))
        assertEquals(0f, SceneCanvas.look(gap, 60).alpha[1])
        // без холстов и переходов - никакого влияния (старые проекты рисуются как раньше)
        assertTrue(SceneCanvas.look(listOf(SceneRange(1, "A", 0, 50), SceneRange(2, "B", 50, 100)), 10).isNone)
        assertTrue(SceneCanvas.look(emptyList(), 10).isNone)
    }

    @Test fun dissolveCrossfadesOwnLayersAroundTheCut() {
        val sc = two(SceneTransition.DISSOLVE, 10)
        assertEquals(10, SceneCanvas.transitionLength(sc, 1))
        assertEquals(0, SceneCanvas.transitionLength(sc, 0))
        // окно [45, 55): до него A целиком, после - B
        assertEquals(0f, SceneCanvas.look(sc, 44).alpha[2])
        assertNull(SceneCanvas.look(sc, 44).alpha[1])
        val first = SceneCanvas.look(sc, 45)
        near(0.95f, first.alpha[1]!!); near(0.05f, first.alpha[2]!!)
        val mid = SceneCanvas.look(sc, 50)
        near(0.45f, mid.alpha[1]!!); near(0.55f, mid.alpha[2]!!)
        val last = SceneCanvas.look(sc, 54)
        near(0.05f, last.alpha[1]!!); near(0.95f, last.alpha[2]!!)
        assertEquals(0f, SceneCanvas.look(sc, 55).alpha[1])
        assertNull(SceneCanvas.look(sc, 55).alpha[2])
        assertNull(mid.alpha[3])
        // монотонно
        var prev = -1f
        for (f in 45 until 55) { val v = SceneCanvas.look(sc, f).alpha[2]!!; assertTrue(v > prev); prev = v }
    }

    @Test fun fadeThroughColorPeaksAtTheCut() {
        val sc = two(SceneTransition.FADE_BLACK, 8)
        assertEquals(0f, SceneCanvas.look(sc, 45).overlay)
        val o1 = SceneCanvas.look(sc, 46).overlay
        val o2 = SceneCanvas.look(sc, 49).overlay
        val o3 = SceneCanvas.look(sc, 50).overlay
        val o4 = SceneCanvas.look(sc, 53).overlay
        assertTrue(o1 > 0f && o2 > o1)
        near(o2, o3)   // симметрично вокруг стыка
        assertTrue(o4 < o3 && o4 > 0f)
        assertEquals(0f, SceneCanvas.look(sc, 54).overlay)
        assertFalse(SceneCanvas.look(sc, 49).overlayWhite)
        // до стыка - слои A, после - B
        assertEquals(0f, SceneCanvas.look(sc, 49).alpha[2])
        assertEquals(0f, SceneCanvas.look(sc, 50).alpha[1])
        // фон уходит в чёрный
        val bg = SceneCanvas.look(sc, 49, white).background!!
        assertTrue((bg and 0xFF) < 0x40)
        assertTrue(SceneCanvas.look(two(SceneTransition.FADE_WHITE, 8), 50).overlayWhite)
    }

    @Test fun pushSlidesOwnLayers() {
        val sc = two(SceneTransition.PUSH, 4)
        val l = SceneCanvas.look(sc, 48)   // первый кадр окна [48, 52)
        near(-0.125f, l.shiftX[1]!!); near(0.875f, l.shiftX[2]!!)
        assertNull(l.alpha[1]); assertNull(l.alpha[2])   // оба видны, сдвинуты
        val e = SceneCanvas.look(sc, 51)
        near(-0.875f, e.shiftX[1]!!); near(0.125f, e.shiftX[2]!!)
        assertTrue(SceneCanvas.look(sc, 52).shiftX.isEmpty())
    }

    @Test fun transitionLengthIsClampedByScenesAndGaps() {
        val sc = listOf(SceneRange(1, "A", 0, 6), SceneRange(2, "B", 6, 100, transition = "dissolve", transitionFrames = 40))
        assertEquals(6, SceneCanvas.transitionLength(sc, 1))
        val gap = listOf(SceneRange(1, "A", 0, 40), SceneRange(2, "B", 50, 100, transition = "dissolve", transitionFrames = 10))
        assertEquals(0, SceneCanvas.transitionLength(gap, 1))
        val cut = listOf(SceneRange(1, "A", 0, 40), SceneRange(2, "B", 40, 100, transition = "cut", transitionFrames = 10))
        assertEquals(0, SceneCanvas.transitionLength(cut, 1))
        assertEquals(SceneTransition.CUT, SceneTransition.of("неизвестно"))
        assertEquals(SceneTransition.CUT, SceneTransition.of(null))
        val set = SceneCanvas.setTransition(cut, 2, SceneTransition.PUSH, 9999)!!
        assertEquals(SceneCanvas.MAX_TRANSITION, set[1].transitionFrames)
        assertEquals(0, SceneCanvas.setTransition(set, 2, SceneTransition.CUT, 12)!![1].transitionFrames)
        assertNull(SceneCanvas.setTransition(set, 99, SceneTransition.PUSH, 5))
    }

    @Test fun toggleShareAndCleanCanvas() {
        val sc = listOf(SceneRange(1, "A", 0, 50), SceneRange(2, "B", 50, 100), SceneRange(3, "C", 100, 150))
        // общий слой 7 выключен в B -> свой для A и C
        val t = SceneCanvas.toggle(sc, 7, 2, false)!!
        assertEquals(listOf(true, false, true), t.map { 7 in it.layers })
        assertTrue(SceneCanvas.visibleIn(t, 7, 1)); assertFalse(SceneCanvas.visibleIn(t, 7, 2))
        // выключить и в A, и в C нельзя - слой пропал бы совсем
        val t2 = SceneCanvas.toggle(t, 7, 1, false)!!
        assertNull(SceneCanvas.toggle(t2, 7, 3, false))
        // включение обратно
        assertEquals(listOf(false, true, true), SceneCanvas.toggle(t2, 7, 2, true)!!.map { 7 in it.layers })
        assertTrue(SceneCanvas.share(t, 7).none { 7 in it.layers })
        assertTrue(SceneCanvas.visibleIn(sc, 7, 2))   // общий виден везде
        assertNull(SceneCanvas.toggle(sc, 7, 42, false))
        // чистый холст: общие слои 7, 8 остаются в прежних сценах, в новой их нет; уже свой слой 9 не трогается
        val withOwn = sc.map { if (it.id == 1) it.copy(layers = setOf(9)) else it }
        val c = SceneCanvas.cleanCanvas(withOwn, listOf(7, 8, 9), 3)
        assertEquals(setOf(9, 7, 8), c[0].layers)
        assertEquals(setOf(7, 8), c[1].layers)
        assertEquals(emptySet<Int>(), c[2].layers)
        // prune: удалённые слои уходят из сцен
        assertEquals(setOf(7), SceneCanvas.prune(c, setOf(7))[0].layers)
    }

    @Test fun splitAndRemoveKeepTheCanvas() {
        val sc = listOf(SceneRange(1, "A", 0, 50, layers = setOf(1), background = white),
            SceneRange(2, "B", 50, 100, layers = setOf(2), transition = "dissolve", transitionFrames = 10))
        val split = SceneMath.split(sc, 100, 75)!!
        assertEquals(3, split.size)
        assertEquals(setOf(2), split[2].layers)               // вторая половина - тот же холст
        assertEquals("cut", split[2].transition)               // без перехода внутри одной сцены
        assertEquals("dissolve", split[1].transition)
        // удаление сцены: её отрезок и свои слои - соседу, кадры выглядят как раньше
        val removed = SceneMath.remove(sc, 2)
        assertEquals(setOf(1, 2), removed[0].layers)
        val removedFirst = SceneMath.remove(sc, 1)
        assertEquals(setOf(1, 2), removedFirst[0].layers)
        assertEquals("cut", removedFirst[0].transition)
        assertEquals(0, removedFirst[0].start)
    }

    @Test fun sceneBackgroundAndMix() {
        val sc = listOf(SceneRange(1, "A", 0, 50, background = white), SceneRange(2, "B", 50, 100, transition = "dissolve", transitionFrames = 10))
        assertEquals(white, SceneCanvas.look(sc, 10, black).background)
        assertNull(SceneCanvas.look(sc, 80, black).background)   // фон проекта - без подмены
        val mid = SceneCanvas.look(sc, 50, black).background!!
        val r = (mid shr 16) and 0xFF
        assertTrue(r in 90..160)
        assertEquals(white, SceneCanvas.mix(black, white, 1f))
        assertEquals(black, SceneCanvas.mix(black, white, 0f))
        assertEquals(0x80808080.toInt(), SceneCanvas.mix(0, white, 0.5f) or 0)
        assertNull(SceneCanvas.setBackground(sc, 1, white))   // без изменений
        assertEquals(null, SceneCanvas.setBackground(sc, 1, null)!![0].background)
    }

    @Test fun projectJsonRoundTripAndVersion() {
        val meta = ProjectMeta("p24", "Холсты", 1080, 1080, fps = 24, durationFrames = 100)
        val scenes = listOf(SceneRange(1, "A", 0, 50, layers = setOf(3, 100001), background = 0xFF112233.toInt()),
            SceneRange(2, "B", 50, 100, layers = setOf(5), transition = "push", transitionFrames = 12))
        val json = ProjectJson.encode(ProjectData(meta, scenes = scenes))
        assertEquals(7, ProjectArchive.versionOf(json))
        val back = ProjectJson.decode(json).getOrThrow()
        assertEquals(scenes, back.scenes)
        // простые сцены по-прежнему v6 - старые сборки открывают проект
        val plain = ProjectJson.encode(ProjectData(meta, scenes = listOf(SceneRange(1, "A", 0, 50))))
        assertEquals(6, ProjectArchive.versionOf(plain))
        assertFalse(plain.contains("\"ly\"") || plain.contains("\"tr\""))
        // мусор в полях не ломает чтение
        val bad = json.replace("\"push\"", "\"warp9\"")
        val b2 = ProjectJson.decode(bad).getOrThrow().scenes
        assertEquals("cut", b2[1].transition)
        assertNotNull(b2[0].background)
    }
}
