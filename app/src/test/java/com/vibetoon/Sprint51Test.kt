package com.vibetoon

import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.ClipTiming
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.anim.LayerClone
import com.vibetoon.domain.anim.MeshMath
import com.vibetoon.domain.anim.PerspectiveMath
import com.vibetoon.domain.anim.PuppetMath
import com.vibetoon.domain.anim.StaggerPlan
import com.vibetoon.domain.anim.TurbMath
import com.vibetoon.domain.anim.WarpMath
import com.vibetoon.domain.anim.EffectTracks
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.FrameMath
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.OnionSettings
import com.vibetoon.domain.project.OnionSkin
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 51: деформеры переходят на отделённую часть и дубликат, дубль «с анимацией / без», покадровая анимация с
 * длиной рисунка (не до конца таймлайна), калька по слоям-рисункам с шагом, плашки к флажкам и плейхеду, точное
 * время, лесенка с шагом и направлением.
 */
class Sprint51Test {
    private val k0 = Keyframe.of(0, LayerTransform(dx = 0.1f, dy = 0f))
    private val k10 = Keyframe.of(10, LayerTransform(dx = 0.3f, dy = 0.2f))

    // ---- деформеры и дубль ----

    @Test fun deformerStateRemapsToCopy() {
        val from = 100_005; val to = 100_042
        val base = mapOf(
            from to LayerTransform(dx = 5f),                                  // трансформация самого слоя - не деформер
            WarpMath.pointId(from, 3) to LayerTransform(dx = 0.05f),
            MeshMath.vertexId(from, 7) to LayerTransform(dy = -0.02f),
            PerspectiveMath.pointId(from, 1) to LayerTransform(dx = 0.1f),
            PuppetMath.pinId(from, 2) to LayerTransform(dx = 0.2f),
            WarpMath.pointId(100_006, 0) to LayerTransform(dx = 9f)           // чужой слой
        )
        val out = LayerClone.base(from, to, base)
        assertEquals(4, out.size)
        assertEquals(0.05f, out.getValue(WarpMath.pointId(to, 3)).dx, 0f)
        assertEquals(-0.02f, out.getValue(MeshMath.vertexId(to, 7)).dy, 0f)
        assertEquals(0.1f, out.getValue(PerspectiveMath.pointId(to, 1)).dx, 0f)
        assertEquals(0.2f, out.getValue(PuppetMath.pinId(to, 2)).dx, 0f)
        assertFalse(out.containsKey(to))
    }

    @Test fun deformerTracksCopiedOnlyWithAnimation() {
        val from = 100_005; val to = 100_042
        val w = WarpMath.pointId(from, 5); val t = TurbMath.paramId(from, 1)
        val tracks = mapOf(
            from to AnimationTrack(from, listOf(k0, k10)),
            w to AnimationTrack(w, listOf(k0, k10)),
            t to AnimationTrack(t, listOf(k0)),
            EffectTracks.id(from, 0, 0) to AnimationTrack(EffectTracks.id(from, 0, 0), listOf(k0))
        )
        val tr = LayerClone.tracks(from, to, tracks)
        assertEquals(setOf(WarpMath.pointId(to, 5), TurbMath.paramId(to, 1)), tr.keys)   // слой и эффекты копируются отдельно
        assertEquals(2, tr.getValue(WarpMath.pointId(to, 5)).keys.size)
        // «без анимации»: форма на кадре = база + ключ (Warp складывает), ключи не переносятся, Turbulent - из настроек
        val baked = LayerClone.baked(from, to, mapOf(w to LayerTransform(dx = 0.01f)), tracks, 5f)
        assertEquals(0.01f + 0.2f, baked.getValue(WarpMath.pointId(to, 5)).dx, 1e-5f)
        assertEquals(0.1f, baked.getValue(WarpMath.pointId(to, 5)).dy, 1e-5f)
        assertFalse(baked.containsKey(TurbMath.paramId(to, 1)))
    }

    @Test fun untouchedDeformerIsNotDeformation() {
        val id = 100_005
        val zero = mapOf(WarpMath.pointId(id, 0) to LayerTransform(), MeshMath.vertexId(id, 3) to LayerTransform())
        assertFalse(LayerClone.deforms(id, zero, emptyMap()))
        assertTrue(LayerClone.deforms(id, zero + (WarpMath.pointId(id, 4) to LayerTransform(dx = 0.01f)), emptyMap()))
        val w = PerspectiveMath.pointId(id, 2)
        assertTrue(LayerClone.deforms(id, zero, mapOf(w to AnimationTrack(w, listOf(k0)))))
        // ключи трансформации - анимация, но не деформация
        assertFalse(LayerClone.deforms(id, zero, mapOf(id to AnimationTrack(id, listOf(k0)))))
        assertTrue(LayerClone.animated(id, mapOf(id to AnimationTrack(id, listOf(k0)))))
        assertFalse(LayerClone.animated(id, mapOf(id to AnimationTrack(id, emptyList()))))
    }

    // ---- покадровая анимация ----

    @Test fun heldCellEndsInsteadOfStretching() {
        val cells = listOf(FrameCell(0, "a"), FrameCell(4, "b", hold = 2))
        assertEquals(6, FrameMath.end(cells, cells[1], 120))
        assertEquals("b", FrameMath.visible(cells, 5, 120)?.file)
        assertNull(FrameMath.visible(cells, 6, 120))                 // дальше пусто, а не «до конца таймлайна»
        assertEquals("a", FrameMath.visible(cells, 3, 120)?.file)    // без длины - до следующего рисунка
        assertEquals(listOf(0 to 4, 4 to 6), FrameMath.spans(cells, 120).map { it[0] to it[1] })
        // старые проекты (без длины) - как раньше
        val legacy = listOf(FrameCell(0, "a"), FrameCell(4, "b"))
        assertEquals("b", FrameMath.visible(legacy, 100, 120)?.file)
        assertEquals(120, FrameMath.end(legacy, legacy[1], 120))
    }

    @Test fun newFrameGoesRightAfterCurrentDrawing() {
        val cells = listOf(FrameCell(0, "a", hold = 2))
        assertEquals(2, FrameMath.insertAt(cells, 0, 120))          // сразу после экспозиции текущего
        assertEquals(2, FrameMath.insertAt(cells, 1, 120))
        assertEquals(7, FrameMath.insertAt(cells, 7, 120))          // в пустом промежутке - на плейхеде
        val legacy = listOf(FrameCell(0, "a"))
        assertEquals(1, FrameMath.insertAt(legacy, 0, 120))         // рисунок без длины: следующий кадр
        assertNull(FrameMath.insertAt(listOf(FrameCell(0, "a", hold = 2)), 1, 2))
        // новый рисунок с длиной не заходит на следующий
        val ins = FrameMath.insert(listOf(FrameCell(0, "a"), FrameCell(3, "c")), FrameCell(2, "b", hold = 4))
        assertEquals(listOf(0, 2, 3), ins.map { it.start })
        assertEquals(1, ins[1].hold)
    }

    @Test fun drawingLengthAndNavigation() {
        val cells = listOf(FrameCell(0, "a", hold = 2), FrameCell(6, "b"))
        val longer = FrameMath.withLength(cells, 1, 10)
        assertEquals(6, longer[0].hold)                              // не длиннее, чем до следующего рисунка
        assertEquals(0, FrameMath.withLength(cells, 1, 0)[0].hold)
        assertEquals(3, FrameMath.lengthOf(FrameMath.withLength(cells, 0, 3), FrameCell(0, "a", 3), 120))
        assertEquals(6, FrameMath.neighborStart(cells, 0, true))
        assertEquals(0, FrameMath.neighborStart(cells, 6, false))
        assertNull(FrameMath.neighborStart(cells, 6, true))
        assertTrue(FrameMath.layerMode(0, explicitClip = true))
        assertFalse(FrameMath.layerMode(0, explicitClip = false))
        assertFalse(FrameMath.layerMode(3, explicitClip = true))
    }

    @Test fun cellHoldSurvivesProjectJson() {
        val meta = ProjectMeta("p", "P", 1920, 1080)
        val m = MediaItem(100_001, MediaKind.IMAGE, "Анимация 1", "assets/a.png", 10, 10, 0f, 0f, 10f, 10f,
            cells = listOf(FrameCell(0, "assets/a.png"), FrameCell(3, "assets/b.png", hold = 2)))
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, media = listOf(m)))).getOrThrow().media.single()
        assertEquals(listOf(0, 2), back.cells.map { it.hold })
    }

    // ---- калька ----

    @Test fun onionAcrossSeparateDrawingLayers() {
        val spans = listOf(OnionSkin.Span(1, 0, 4), OnionSkin.Span(2, 4, 8), OnionSkin.Span(3, 8, 12), OnionSkin.Span(4, 12, 16))
        val g = OnionSkin.planLayers(spans, 9, OnionSettings(before = 2, after = 1))
        assertEquals(listOf(2, 1), g.filter { it.before }.map { it.id })   // ближайший первым
        assertEquals(listOf(4), g.filter { !it.before }.map { it.id })
        assertTrue(g.none { it.id == 3 })                                    // текущий рисунок - не призрак
        assertTrue(g.first { it.id == 2 }.alpha > g.first { it.id == 1 }.alpha)
        assertEquals(OnionSkin.MAX_DEPTH, g.first { it.id == 4 }.slot)
        val stepped = OnionSkin.planLayers(spans, 13, OnionSettings(before = 1, after = 0, step = 2))
        assertEquals(listOf(2), stepped.map { it.id })                      // шаг 2: через один
        assertTrue(OnionSkin.planLayers(spans.take(1), 9, OnionSettings()).isEmpty())
    }

    @Test fun onionStepPlaybackAndPersistence() {
        val cells = (0 until 6).map { FrameCell(it * 2, "c$it") }
        val g = OnionSkin.plan(cells, 10, OnionSettings(before = 2, after = 0, step = 2))
        assertEquals(listOf("c3", "c1"), g.map { it.cell.file })
        assertTrue(OnionSkin.activeNow(true, playing = true, settings = OnionSettings()))
        assertFalse(OnionSkin.activeNow(true, playing = true, settings = OnionSettings(playback = false)))
        assertTrue(OnionSkin.activeNow(true, playing = false, settings = OnionSettings(playback = false)))
        val s = OnionSettings(step = 3, playback = false, layers = false)
        assertEquals(s, OnionSkin.decode(OnionSkin.encode(s)))
        assertEquals(OnionSkin.MAX_STEP, OnionSkin.normalized(OnionSettings(step = 99)).step)
        assertEquals(OnionSettings(), OnionSkin.decode("v1;b=1;a=1;ba=35;aa=35;bc=FF3333;ac=33FF33;f=1;t=1;p=0"))   // старая строка
    }

    // ---- плашки ----

    @Test fun clipToPlayheadAndMarkers() {
        val c = Clip(10, 20)
        assertEquals(Clip(30, 40), ClipTiming.apply(ClipTiming.Op.START_TO_PLAYHEAD, c, 30, emptyList(), 120))
        assertEquals(Clip(21, 31), ClipTiming.apply(ClipTiming.Op.END_TO_PLAYHEAD, c, 30, emptyList(), 120))   // кадр плейхеда - последний видимый
        assertEquals(Clip(15, 20), ClipTiming.apply(ClipTiming.Op.TRIM_START_TO_PLAYHEAD, c, 15, emptyList(), 120))
        assertEquals(Clip(10, 16), ClipTiming.apply(ClipTiming.Op.TRIM_END_TO_PLAYHEAD, c, 15, emptyList(), 120))
        assertNull(ClipTiming.apply(ClipTiming.Op.START_TO_MARKER, c, 15, emptyList(), 120))
        val mk = listOf(24, 48)
        assertEquals(Clip(24, 34), ClipTiming.apply(ClipTiming.Op.START_TO_MARKER, c, 30, mk, 120))
        assertEquals(Clip(38, 48), ClipTiming.apply(ClipTiming.Op.END_TO_MARKER, c, 45, mk, 120))   // флажок - граница
        assertEquals(Clip(10, 24), ClipTiming.apply(ClipTiming.Op.TRIM_END_TO_MARKER, c, 25, mk, 120))
        assertEquals(Clip(24, 48), ClipTiming.apply(ClipTiming.Op.FIT_MARKERS, c, 30, mk, 120))
        assertEquals(Clip(48, 120), ClipTiming.apply(ClipTiming.Op.FIT_MARKERS, c, 60, mk, 120))
        assertEquals(Clip(0, 24), ClipTiming.apply(ClipTiming.Op.FIT_MARKERS, c, 5, mk, 120))
        // обрезка не делает плашку пустой и не выходит за проект; перенос держит длину
        assertEquals(Clip(19, 20), ClipTiming.trimStartTo(c, 50, 120))
        assertEquals(Clip(110, 120), ClipTiming.moveStartTo(c, 115, 120))
        assertEquals(Clip(10, 20, speed = 2f), ClipTiming.moveStartTo(Clip(0, 10, speed = 2f), 10, 120))
    }

    @Test fun magnetSnapsClipEdges() {
        val targets = ClipTiming.snapTargets(listOf(24), 40, 120, listOf(Clip(50, 60)))
        assertTrue(targets.containsAll(listOf(0, 120, 24, 40, 50, 60)))
        assertEquals(24, ClipTiming.snapDrag(1, 22, 30, targets, 3))
        assertEquals(50, ClipTiming.snapDrag(2, 10, 49, targets, 3))
        assertEquals(43, ClipTiming.snapDrag(3, 42, 49, targets, 2))       // конец 49 -> 50 ближе, чем начало 42 -> 40
        assertEquals(40, ClipTiming.snapDrag(3, 41, 49, targets, 1))       // поровну - прилипает начало
        assertEquals(15, ClipTiming.snapDrag(3, 15, 18, targets, 2))       // целей рядом нет
        assertEquals(10, ClipTiming.nextDrawingStart(Clip(4, 10), 6))
        assertEquals(30, ClipTiming.nextDrawingStart(Clip(4, 10), 30))
    }

    @Test fun exactTimeInSecondsOrFrames() {
        assertEquals(48, ClipTiming.parse("2", 24, seconds = true))
        assertEquals(60, ClipTiming.parse("2,5", 24, seconds = true))
        assertEquals(48, ClipTiming.parse("48", 24, seconds = false))
        assertEquals(48, ClipTiming.parse("2с", 24, seconds = false))
        assertEquals(12, ClipTiming.parse("12к", 24, seconds = true))
        assertEquals(12, ClipTiming.parse("12 f", 24, seconds = true))
        assertEquals(24 * 65, ClipTiming.parse("1:05", 24, seconds = false))
        assertNull(ClipTiming.parse("abc", 24, true)); assertNull(ClipTiming.parse("-1", 24, true)); assertNull(ClipTiming.parse("", 24, true))
        assertEquals("2.5", ClipTiming.format(60, 24, true)); assertEquals("2", ClipTiming.format(48, 24, true)); assertEquals("60", ClipTiming.format(60, 24, false))
        // 2с - 5с при 24 fps: кадры 48..119
        assertArrayEquals(intArrayOf(48, 120), ClipTiming.resolve(48, 120, 72, 1, 240))
        assertArrayEquals(intArrayOf(48, 96), ClipTiming.resolve(48, 120, 48, 2, 240))
        assertArrayEquals(intArrayOf(230, 240), ClipTiming.resolve(230, 0, 72, 0, 240))
        assertEquals(Clip(48, 120), ClipTiming.exact(Clip(0, 10), 48, 120, 240))
    }

    // ---- лесенка ----

    @Test fun staggerStepAndDirection() {
        val starts = listOf(0, 0, 0); val lens = listOf(4, 6, 2)
        assertEquals(listOf(0, 4, 10), StaggerPlan.starts(starts, lens, 0, chain = true, reverse = false))     // встык
        assertEquals(listOf(0, 6, 14), StaggerPlan.starts(starts, lens, 2, chain = true, reverse = false))     // промежуток 2
        assertEquals(listOf(0, 3, 6), StaggerPlan.starts(starts, lens, 3, chain = false, reverse = false))     // каскад 3
        assertEquals(listOf(8, 2, 0), StaggerPlan.starts(starts, lens, 0, chain = true, reverse = true))       // снизу вверх
        assertEquals(listOf(5, 0, 0), StaggerPlan.starts(listOf(5, 0, 0), lens, -20, chain = true, reverse = false))   // не уходит за 0
        val st = StaggerPlan.Settings(step = 3, chain = false, reverse = true)
        assertEquals(st, StaggerPlan.decode(StaggerPlan.encode(st)))
        assertEquals(1, StaggerPlan.Settings(step = 0, chain = false).normalized().step)
        assertEquals(StaggerPlan.Settings(), StaggerPlan.decode(null))
    }
}
