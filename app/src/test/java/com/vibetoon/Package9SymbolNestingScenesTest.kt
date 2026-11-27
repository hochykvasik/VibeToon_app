package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.Effect
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.anim.EffectTracks
import com.vibetoon.domain.anim.SceneMath
import com.vibetoon.domain.anim.SceneRange
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.SceneDomain
import com.vibetoon.domain.model.SceneTree
import com.vibetoon.domain.model.GroupNode
import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.SymbolPlacement
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 9: символ внутри групп (рот в папке головы с «Тряской»), координаты медиа в группах PSD, сцены. */
class Package9SymbolNestingScenesTest {
    private fun leaf(id: Int, x: Int, y: Int, w: Int, h: Int, parent: Int = -1) =
        LayerInfo(id, "n$id", x, y, w, h, 1f, true, 0, parent, "norm", false)
    private fun group(id: Int, parent: Int = -1) = LayerInfo(id, "g$id", 0, 0, 0, 0, 1f, true, 1, parent, "norm", false)

    // ---- иерархия символа ----

    private val tree = mapOf(
        10 to 1,                 // рот-определение (группа 10) в голове (1)
        1 to 0,                  // голова в теле (0)
        0 to -1,
        MEDIA_ID_BASE + 5 to 1,  // экземпляр в голове
        11 to 10                 // слой внутри определения
    )

    @Test fun ancestorsGoBottomUpAndStopOnCycles() {
        assertEquals(listOf(1, 0), SymbolPlacement.ancestors(10) { tree[it] })
        val loop = mapOf(1 to 2, 2 to 1)
        assertEquals(listOf(2, 1), SymbolPlacement.ancestors(1) { loop[it] })
    }

    @Test fun sharedContextIsTheCommonAncestorsOfDefinitionAndInstances() {
        assertEquals(setOf(1, 0), SymbolPlacement.sharedContext(10, listOf(MEDIA_ID_BASE + 5)) { tree[it] })
        // экземпляр в корне: общих предков нет - снимок как раньше
        val rootInst = tree + (MEDIA_ID_BASE + 5 to -1)
        assertEquals(emptySet<Int>(), SymbolPlacement.sharedContext(10, listOf(MEDIA_ID_BASE + 5)) { rootInst[it] })
        assertEquals(emptySet<Int>(), SymbolPlacement.sharedContext(10, emptyList()) { tree[it] })
    }

    @Test fun instanceCannotGoInsideItsOwnDefinition() {
        assertTrue(SymbolPlacement.canPlace(10, 1) { tree[it] })        // в папку головы - можно
        assertTrue(SymbolPlacement.canPlace(10, -1) { tree[it] })       // в корень - можно
        assertFalse(SymbolPlacement.canPlace(10, 10) { tree[it] })      // в своё определение - нельзя
        val nested = tree + (12 to 10)
        assertFalse(SymbolPlacement.canPlace(10, 12) { nested[it] })    // в потомка определения - нельзя
    }

    @Test fun newInstanceKeepsTheGroupOfTheSelectedLayers() {
        assertEquals(1, SymbolPlacement.instanceParent(1))
        assertEquals(-1, SymbolPlacement.instanceParent(-1))
        assertEquals(-1, SymbolPlacement.instanceParent(null))
    }

    @Test fun shakeIsStrippedFromSnapshotSoItAppliesOnceLive() {
        val shake = listOf(Effect(EffectMath.SHAKE, listOf(8f, 12f), 0))
        val blur = listOf(Effect(EffectMath.BLUR, listOf(3f), 0))
        val live = SymbolPlacement.liveChain(listOf(shake, shake))
        assertEquals(shake, live)
        assertEquals(emptyList<Effect>(), SymbolPlacement.liveChain(listOf(shake, blur)))   // разные - ничего не снимаем
        val fx = mapOf(11 to shake, 12 to blur, 1 to shake, 99 to shake)
        val snap = SymbolPlacement.snapshotFx(fx, members = setOf(11, 12), live = live, neutral = setOf(1))
        assertNull(snap[11])            // участник с той же «Тряской» - без неё в форме
        assertEquals(blur, snap[12])    // свой эффект участника остаётся в форме
        assertNull(snap[1])             // эффект общего предка (головы) - живой, не в форме
        assertEquals(shake, snap[99])   // посторонний слой не трогаем
        assertEquals(fx, SymbolPlacement.snapshotFx(fx, setOf(11), emptyList(), emptySet()))
    }

    @Test fun copyLayerTracksCopiesEffectParameterKeys() {
        val e = listOf(Effect(EffectMath.SHAKE, listOf(8f, 12f), 0))
        var anim = Animation()
        anim = anim.withKey(EffectTracks.id(3, 0, 0), 0, LayerTransform(dx = 4f))
        anim = anim.withKey(EffectTracks.id(3, 0, 0), 10, LayerTransform(dx = 20f))
        anim = anim.withKey(EffectTracks.id(7, 0, 1), 5, LayerTransform(dx = 1f))   // старая дорожка цели
        val out = EffectTracks.copyLayerTracks(anim, 3, 7, e)
        val copied = out.tracks[EffectTracks.id(7, 0, 0)]
        assertNotNull(copied)
        assertEquals(listOf(0, 10), copied!!.keys.map { it.frame })
        assertEquals(EffectTracks.id(7, 0, 0), copied.layerId)
        assertNull(out.tracks[EffectTracks.id(7, 0, 1)])   // у источника нет - у цели убрана
        assertTrue(out.tracks.containsKey(EffectTracks.id(3, 0, 0)))   // источник цел
        assertEquals(anim, EffectTracks.copyLayerTracks(anim, 3, 3, e))
    }

    // ---- координаты: медиа в группе PSD ----

    @Test fun groupPivotUsesOnlyChildrenOfItsOwnSpace() {
        val layers = listOf(group(5), leaf(0, 100, 100, 200, 200, 5), leaf(MEDIA_ID_BASE, 2000, 2000, 10, 10, 5))
        val g = SceneTree.build(layers).single() as GroupNode
        assertEquals(200f, g.pivotX, 1e-3f)   // центр PSD-слоя, медиа на 2000 не сдвигает пивот
        assertEquals(200f, g.pivotY, 1e-3f)
        assertTrue(SceneDomain.isMedia(MEDIA_ID_BASE))
        assertFalse(SceneDomain.isMedia(MEDIA_ID_BASE - 1))
    }

    @Test fun mediaInsidePsdGroupStaysInCanvasCoordinatesAndFollowsTheGroup() {
        val fit = Affine2D.scale(0.5f, 0.5f)
        val layers = listOf(group(5), leaf(0, 100, 100, 200, 200, 5), leaf(MEDIA_ID_BASE, 40, 60, 300, 200, 5))
        fun xOf(s: com.vibetoon.domain.render.SceneData, id: Int): Float {
            val i = s.ops.toList().chunked(4).indexOfFirst { it[1] == id }
            return s.params[i * 8 + 4]
        }
        val still = SceneBuilder.buildTree(SceneTree.build(layers), 1000, 1000, emptyMap(), fit)
        assertEquals(50f, xOf(still, 0), 1e-3f)                 // PSD: 100 * 0.5
        assertEquals(40f, xOf(still, MEDIA_ID_BASE), 1e-3f)     // медиа (рот-символ) в папке PSD: не вписывается второй раз
        // группа PSD сдвинута на 20 (в координатах PSD): оба ребёнка смещаются на 10 px холста - рот едет с головой
        val moved = SceneBuilder.buildTree(SceneTree.build(layers), 1000, 1000, mapOf(5 to LayerTransform(dx = 20f)), fit)
        assertEquals(60f, xOf(moved, 0), 1e-3f)
        assertEquals(50f, xOf(moved, MEDIA_ID_BASE), 1e-3f)
    }

    // ---- сцены ----

    @Test fun firstNewSceneWrapsTheTimelineAndAppends() {
        val r = SceneMath.add(emptyList(), 120, 48)!!
        assertEquals(listOf(SceneRange(1, "Сцена 1", 0, 120), SceneRange(2, "Сцена 2", 120, 168)), r.scenes)
        assertEquals(168, r.duration)
        assertEquals(2, r.sceneId)
        val r2 = SceneMath.add(r.scenes, r.duration, 24)!!
        assertEquals(SceneRange(3, "Сцена 3", 168, 192), r2.scenes.last())
    }

    @Test fun splitRenameRemoveAndStep() {
        val split = SceneMath.split(emptyList(), 100, 40)!!
        assertEquals(listOf(SceneRange(1, "Сцена 1", 0, 40), SceneRange(2, "Сцена 2", 40, 100)), split)
        assertNull(SceneMath.split(split, 100, 40))   // на первом кадре сцены разрезать нельзя
        val renamed = SceneMath.rename(split, 2, "  Финал\n ")!!
        assertEquals("Финал", renamed[1].name)
        assertNull(SceneMath.rename(split, 2, "   "))
        assertEquals(renamed[1], SceneMath.at(renamed, 70))
        assertEquals(renamed[0], SceneMath.step(renamed, 70, -1))
        assertEquals(renamed[1], SceneMath.step(renamed, 10, 1))
        assertNull(SceneMath.step(renamed, 70, 1))
        // удаление: кадры не теряются, отрезок уходит соседней сцене
        assertEquals(listOf(SceneRange(1, "Сцена 1", 0, 100)), SceneMath.remove(renamed, 2))
        assertEquals(listOf(SceneRange(2, "Финал", 0, 100)), SceneMath.remove(renamed, 1))
        assertEquals(emptyList<SceneRange>(), SceneMath.remove(listOf(SceneRange(1, "A", 0, 10)), 1))
    }

    @Test fun normalizeClampsOverlapsAndDuration() {
        val n = SceneMath.normalize(listOf(SceneRange(2, "B", 30, 80), SceneRange(1, "A", 0, 40), SceneRange(3, "", 90, 200), SceneRange(1, "dup", 95, 99)), 100)
        assertEquals(listOf(SceneRange(1, "A", 0, 40), SceneRange(2, "B", 40, 80), SceneRange(3, "Сцена 3", 90, 100)), n)
    }

    @Test fun playAndExportStayInsideTheScene() {
        val s = SceneRange(1, "A", 50, 60)
        assertEquals(55, SceneMath.playFrame(0, 55, s, true))
        assertEquals(50, SceneMath.playFrame(5, 55, s, true))      // цикл внутри сцены
        assertEquals(59, SceneMath.playFrame(50, 55, s, false))    // без цикла - стоп на последнем кадре сцены
        assertEquals(50, SceneMath.playFrame(0, 10, s, true))      // старт вне сцены - с её начала
        assertEquals(50, SceneMath.exportFrame(0, 24, 24, s))
        assertEquals(59, SceneMath.exportFrame(9, 24, 24, s))
        assertEquals(59, SceneMath.exportFrame(40, 24, 24, s))     // не выходит за сцену
    }

    @Test fun scenesRoundTripThroughProjectJson() {
        val meta = ProjectMeta("p9", "Сцены", 1080, 1080, fps = 24, durationFrames = 100)
        val scenes = listOf(SceneRange(1, "Интро", 0, 40), SceneRange(4, "Финал", 40, 100))
        val json = ProjectJson.encode(ProjectData(meta, scenes = scenes))
        val back = ProjectJson.decode(json).getOrThrow()
        assertEquals(scenes, back.scenes)
        // без сцен формат прежний (старые сборки открывают проект)
        assertFalse(ProjectJson.encode(ProjectData(meta)).contains("\"scenes\""))
        assertEquals(emptyList<SceneRange>(), ProjectJson.decode(ProjectJson.encode(ProjectData(meta))).getOrThrow().scenes)
    }
}
