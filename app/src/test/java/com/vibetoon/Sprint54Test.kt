package com.vibetoon

import com.vibetoon.domain.anim.ClipSpeed
import com.vibetoon.domain.input.HandleDragGate
import com.vibetoon.domain.model.MergePlan
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.SymbolBoneLink
import com.vibetoon.domain.raster.LayerMergeMath
import com.vibetoon.domain.render.PlaySceneCache
import com.vibetoon.domain.render.PlayWarmup
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.ui.TabIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 54: вылет ScrollableTabRow, «Слить в один слой», символы на костях, скорость 0.10x–3.0x, ручка перетаскивания
 * слоёв на таймлайне, разогрев и кэш сцен Play.
 */
class Sprint54Test {
    // ---- 1. индекс вкладки ----

    @Test fun tabIndexNeverLeavesList() {
        // «Index 5 out of bounds for length 5»: открыта 6-я вкладка, а список уже из пяти
        assertEquals(4, TabIndex.clamp(5, 5))
        assertEquals(0, TabIndex.clamp(-1, 5))
        assertEquals(0, TabIndex.clamp(3, 0))
        assertEquals(2, TabIndex.clamp(2, 5))
        assertNull(TabIndex.positionOrNull(listOf(1, 2, 3, 4, 5), 5))
        assertEquals(5, TabIndex.positionOrNull(listOf(1, 2, 3, 4, 5), 4))
    }

    @Test fun tabIndexFollowsNameAndStaysNearWhenTabDisappears() {
        val before = listOf("Слой", "Клип", "Цвет", "Время", "Эффекты")
        val after = listOf("Слой", "Клип", "Цвет", "Эффекты")
        assertEquals(3, TabIndex.indexOf(after, "Эффекты", 4))
        // вкладка «Время» пропала - остаёмся рядом (не прыгаем в начало и не выходим за список)
        assertEquals(3, TabIndex.indexOf(after, "Время", 3))
        assertEquals(3, TabIndex.indexOf(before, "Время", 0))
        assertEquals(0, TabIndex.indexOf(emptyList<String>(), "Время", 7))
    }

    // ---- 2. слияние ----

    private fun px(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            out[i * 4] = (r * a / 255).toByte(); out[i * 4 + 1] = (g * a / 255).toByte()
            out[i * 4 + 2] = (b * a / 255).toByte(); out[i * 4 + 3] = a.toByte()
        }
        return out
    }
    private fun quad(x: Float, y: Float, w: Float, h: Float) = floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
    private fun u(b: Byte) = b.toInt() and 255

    @Test fun mergePlanPixelsIntoBottomKeepsAnimation() {
        // анимированная чёлка снизу, блик сверху: сливаем в пиксели чёлки, её ключи живут
        val plan = MergePlan.plan(listOf(MergePlan.Node(10, animated = true), MergePlan.Node(11)))
        assertTrue(plan is MergePlan.Result.Pixels)
        plan as MergePlan.Result.Pixels
        assertEquals(10, plan.target)
        assertEquals(listOf(11), plan.tops)
        assertTrue(plan.targetAnimated)
        assertFalse(plan.topsAnimated)
    }

    @Test fun mergePlanNeverCreatesGroup() {
        // замок + блик: результат - один слой (пиксели), группа не создаётся; группа в выделении - отказ с подсказкой
        val two = MergePlan.plan(listOf(MergePlan.Node(1), MergePlan.Node(2)))
        assertTrue(two is MergePlan.Result.Pixels)
        val withGroup = MergePlan.plan(listOf(MergePlan.Node(1), MergePlan.Node(2, isGroup = true)))
        assertEquals(MergePlan.Result.Refuse(MergePlan.GROUP), withGroup)
        assertEquals(MergePlan.Result.Refuse(MergePlan.TOO_FEW), MergePlan.plan(listOf(MergePlan.Node(1))))
        assertEquals(MergePlan.Result.Refuse(MergePlan.LOCKED), MergePlan.plan(listOf(MergePlan.Node(1), MergePlan.Node(2, locked = true))))
        assertEquals(MergePlan.Result.Refuse(MergePlan.HIDDEN), MergePlan.plan(listOf(MergePlan.Node(1, visible = false), MergePlan.Node(2))))
    }

    @Test fun mergePlanFallsBackToSnapshotHonestly() {
        // верхний с эффектом (не «чистый») или нижний-вектор: снимок, и план честно говорит, что анимация застынет
        val fx = MergePlan.plan(listOf(MergePlan.Node(1, animated = true), MergePlan.Node(2, plainTop = false)))
        assertEquals(MergePlan.Result.Snapshot(listOf(1, 2), true), fx)
        val vec = MergePlan.plan(listOf(MergePlan.Node(1, pixel = false), MergePlan.Node(2)))
        assertEquals(MergePlan.Result.Snapshot(listOf(1, 2), false), vec)
        val blend = MergePlan.plan(listOf(MergePlan.Node(1, targetOk = false), MergePlan.Node(2)))
        assertTrue(blend is MergePlan.Result.Snapshot)
        assertTrue(MergePlan.clipOutside(targetAnimated = true))
        assertFalse(MergePlan.clipOutside(targetAnimated = false))
    }

    @Test fun mergeManyStacksTopsInOrderWithOneUndoRect() {
        val bottom = px(4, 4, 0, 0, 255, 255)        // синий
        val red = px(2, 2, 255, 0, 0, 255)           // в левом верхнем углу
        val green = px(2, 2, 0, 255, 0, 255)         // в правом нижнем
        val mapRed = LayerMergeMath.pixelMap(quad(0f, 0f, 4f, 4f), 4, 4, quad(0f, 0f, 2f, 2f), 2, 2)!!
        val mapGreen = LayerMergeMath.pixelMap(quad(0f, 0f, 4f, 4f), 4, 4, quad(2f, 2f, 2f, 2f), 2, 2)!!
        val r = LayerMergeMath.mergeMany(bottom, 0, 4, 4, listOf(
            LayerMergeMath.Top(red, 0, 2, 2, mapRed, SceneBuilder.BLEND_NORMAL, 1f, false),
            LayerMergeMath.Top(green, 0, 2, 2, mapGreen, SceneBuilder.BLEND_NORMAL, 1f, false)))
        assertNotNull(r)
        r!!
        // общая рамка изменений покрывает оба слоя - одна пара до/после
        assertEquals(0, r.rect.x); assertEquals(0, r.rect.y); assertEquals(4, r.rect.w); assertEquals(4, r.rect.h)
        assertEquals(255, u(r.after[0])); assertEquals(0, u(r.after[2]))                // (0,0) красный
        val o = (3 * 4 + 3) * 4
        assertEquals(255, u(r.after[o + 1])); assertEquals(0, u(r.after[o + 2]))        // (3,3) зелёный
        val mid = (0 * 4 + 3) * 4
        assertEquals(255, u(r.after[mid + 2]))                                          // (3,0) остался синим
        // «до» - исходные пиксели (Undo возвращает их точно)
        assertEquals(255, u(r.before[2])); assertEquals(0, u(r.before[0]))
    }

    @Test fun mergeManyLaterTopCoversEarlierAndBakesBottomOpacityOnce() {
        val bottom = px(1, 1, 0, 0, 0, 255)
        val map = LayerMergeMath.pixelMap(quad(0f, 0f, 1f, 1f), 1, 1, quad(0f, 0f, 1f, 1f), 1, 1)!!
        val r = LayerMergeMath.mergeMany(bottom, 0, 1, 1, listOf(
            LayerMergeMath.Top(px(1, 1, 255, 0, 0, 255), 0, 1, 1, map, SceneBuilder.BLEND_NORMAL, 1f, false),
            LayerMergeMath.Top(px(1, 1, 0, 255, 0, 255), 0, 1, 1, map, SceneBuilder.BLEND_NORMAL, 1f, false)), bottomOpacity = 0.5f)!!
        assertEquals(0, u(r.after[0])); assertEquals(255, u(r.after[1])); assertEquals(255, u(r.after[3]))
        // прозрачность нижнего запекается: прозрачный верхний над слоем 50%
        val half = LayerMergeMath.mergeMany(px(1, 1, 255, 255, 255, 255), 0, 1, 1, listOf(
            LayerMergeMath.Top(px(1, 1, 0, 0, 0, 0), 0, 1, 1, map, SceneBuilder.BLEND_NORMAL, 1f, false)), bottomOpacity = 0.5f)!!
        assertTrue(u(half.after[3]) in 126..129)
        assertNull(LayerMergeMath.mergeMany(bottom, 0, 1, 1, emptyList()))
        assertNull(LayerMergeMath.mergeMany(bottom, 0, 2, 2, listOf(LayerMergeMath.Top(bottom, 0, 1, 1, map, 0, 1f, false))))
    }

    // ---- 3. символы и кости ----

    @Test fun symbolLinkMovesToInstance() {
        // рот (5, 6) привязан к кости головы 900; символ 50 (участники 5, 6), экземпляр 70
        val links = mapOf(5 to 900, 6 to 900, 7 to 900)
        val out = SymbolBoneLink.migrate(links, mapOf(50 to setOf(5, 6)), mapOf(70 to 50))
        assertEquals(900, out[70])
        assertNull(out[5]); assertNull(out[6])
        assertEquals(900, out[7])   // чужой слой не тронут
        // повторный запуск ничего не меняет (тот же объект)
        assertSame(out, SymbolBoneLink.migrate(out, mapOf(50 to setOf(5, 6)), mapOf(70 to 50)))
    }

    @Test fun symbolLinkKeepsInnerLinksAndRefusesAmbiguity() {
        // внутренняя привязка (6 -> 5) остаётся; два разных внешних родителя - ничего не переносим
        val inner = SymbolBoneLink.migrate(mapOf(5 to 900, 6 to 5), mapOf(50 to setOf(5, 6)), mapOf(70 to 50))
        assertEquals(900, inner[70]); assertEquals(5, inner[6]); assertNull(inner[5])
        val two = mapOf(5 to 900, 6 to 901)
        assertSame(two, SymbolBoneLink.migrate(two, mapOf(50 to setOf(5, 6)), mapOf(70 to 50)))
        assertNull(SymbolBoneLink.sharedOuterParent(setOf(5, 6), two))
        assertEquals(900, SymbolBoneLink.sharedOuterParent(setOf(5, 6), mapOf(5 to 900, 6 to 5)))
        // экземпляр уже привязан к другому - не трогаем
        val busy = mapOf(5 to 900, 70 to 800)
        assertSame(busy, SymbolBoneLink.migrate(busy, mapOf(50 to setOf(5)), mapOf(70 to 50)))
        // без экземпляров - тоже без изменений
        val none = mapOf(5 to 900)
        assertSame(none, SymbolBoneLink.migrate(none, mapOf(50 to setOf(5)), emptyMap()))
    }

    @Test fun symbolLinkNoCycle() {
        // кость 900 сама привязана к экземпляру 70: перенос дал бы цикл - участники сохраняют привязку
        val links = mapOf(5 to 900, 900 to 70)
        val out = SymbolBoneLink.migrate(links, mapOf(50 to setOf(5)), mapOf(70 to 50))
        assertNull(out[70]); assertEquals(900, out[5])
    }

    @Test fun snapshotLinksDropOutsideParents() {
        val links = mapOf(5 to 900, 6 to 5, 8 to 900)
        val snap = SymbolBoneLink.snapshotLinks(links, setOf(5, 6))
        assertNull(snap[5]); assertEquals(5, snap[6]); assertEquals(900, snap[8])
        val other = mapOf(8 to 900)
        assertSame(other, SymbolBoneLink.snapshotLinks(other, setOf(5, 6)))
    }

    @Test fun instanceBoxIsUnionOfForms() {
        // две формы рта в разных местах квадрата экземпляра
        val a = floatArrayOf(0.4f, 0.6f, 0.2f, 0.1f) to floatArrayOf(0f, 0f, 1f, 1f)
        val b = floatArrayOf(0.45f, 0.62f, 0.1f, 0.2f) to floatArrayOf(0f, 0.5f, 1f, 1f)
        val box = SymbolBoneLink.union(listOf(a, b))
        assertEquals(0.4f, box[0], 1e-4f); assertEquals(0.6f, box[1], 1e-4f)
        assertEquals(0.6f, box[2], 1e-4f); assertEquals(0.82f, box[3], 1e-4f)
        assertEquals(0, SymbolBoneLink.union(listOf(a.first to FloatArray(0))).size)
        assertEquals(0, SymbolBoneLink.union(emptyList()).size)
    }

    // ---- 4. скорость ----

    @Test fun speedRangeStepAndLabels() {
        assertEquals(0.1f, ClipSpeed.clamp(0.01f), 1e-6f)
        assertEquals(3f, ClipSpeed.clamp(9f), 1e-6f)
        assertEquals(1f, ClipSpeed.clamp(Float.NaN), 1e-6f)
        assertEquals(1.05f, ClipSpeed.nudge(1f, up = true), 1e-4f)
        assertEquals(0.95f, ClipSpeed.nudge(1f, up = false), 1e-4f)
        assertEquals(1.05f, ClipSpeed.nudge(1.02f, up = true), 1e-4f)
        assertEquals(1f, ClipSpeed.nudge(1.02f, up = false), 1e-4f)
        assertEquals(0.1f, ClipSpeed.nudge(0.1f, up = false), 1e-4f)
        assertEquals(3f, ClipSpeed.nudge(3f, up = true), 1e-4f)
        assertEquals("1x", ClipSpeed.label(1f))
        assertEquals("0.1x", ClipSpeed.label(0.1f))
        assertEquals("2.5x", ClipSpeed.label(2.5f))
        assertEquals("0.25x", ClipSpeed.label(0.25f))
        assertEquals(listOf(0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f, 3f), ClipSpeed.PRESETS)
        assertEquals(ClipSpeed.PRESETS, Clip.SPEEDS)
    }

    @Test fun speedSliderIsLogarithmicAndRoundTrips() {
        assertEquals(0.1f, ClipSpeed.fromSlider(0f), 1e-4f)
        assertEquals(3f, ClipSpeed.fromSlider(1f), 1e-4f)
        val one = ClipSpeed.toSlider(1f)
        assertTrue("1x около 2/3 ползунка: $one", one in 0.6f..0.75f)
        for (s in ClipSpeed.PRESETS) assertEquals(s, ClipSpeed.fromSlider(ClipSpeed.toSlider(s)), 0.011f)
        // монотонно
        var prev = 0f
        for (i in 0..100) { val v = ClipSpeed.fromSlider(i / 100f); assertTrue(v >= prev); prev = v }
    }

    @Test fun slowingDownStretchesKeysNotCrops() {
        // 0.5x: ключи 10, 20, 30 от начала плашки 10 -> 10, 30, 50; плашка 30 -> 60 кадров
        val f = ClipSpeed.stretch(1f, 0.5f)
        assertEquals(2f, f, 1e-6f)
        assertEquals(listOf(10, 30, 50), ClipSpeed.retimeAll(listOf(10, 20, 30), 10, f, 299))
        assertEquals(60, ClipSpeed.length(30, f))
        // ключи до начала плашки не двигаются
        assertEquals(5, ClipSpeed.retime(5, 10, f))
        // повторная смена считается от применённой скорости: 0.5x -> 2x = сжатие в 4 раза
        assertEquals(0.25f, ClipSpeed.stretch(0.5f, 2f), 1e-6f)
        // 0.5x -> 1x возвращает исходные кадры
        assertEquals(listOf(10, 20, 30), ClipSpeed.retimeAll(listOf(10, 30, 50), 10, ClipSpeed.stretch(0.5f, 1f), 299))
    }

    @Test fun speedingUpNeverLosesOrMergesKeys() {
        val keys = listOf(0, 1, 2, 3, 4)
        val out = ClipSpeed.retimeAll(keys, 0, ClipSpeed.stretch(1f, 3f), 100)
        assertEquals(5, out.distinct().size)
        assertEquals(out.sorted(), out)
        // упёрлись в конец проекта - хвост сдвигается назад без потери ключей
        val capped = ClipSpeed.retimeAll(listOf(0, 50, 90, 99), 0, 2f, 100)
        assertEquals(listOf(0, 98, 99, 100), capped)
    }

    @Test fun speedExtendsProjectUpToLimit() {
        assertEquals(300 to true, ClipSpeed.durationFor(0, 120, 300, 10000))
        assertEquals(600 to true, ClipSpeed.durationFor(0, 600, 300, 10000))
        assertEquals(1000 to false, ClipSpeed.durationFor(500, 900, 300, 1000))
    }

    @Test fun symbolWithoutKeysGetsTimedPasses() {
        // символ 8 кадров в Цикле, 0.5x: проход 0..7 за 14 кадров, период 16
        val keys = ClipSpeed.symbolKeys(10, 8, 2f, 40, loop = true)
        assertEquals(Triple(10, 0, true), keys[0])
        assertEquals(Triple(24, 7, false), keys[1])
        assertEquals(Triple(26, 0, true), keys[2])
        assertEquals(6, keys.size)   // 40 кадров / период 16 -> 3 прохода
        assertEquals(2, ClipSpeed.symbolKeys(0, 8, 2f, 40, loop = false).size)
        assertTrue(ClipSpeed.symbolKeys(0, 1, 2f, 40, loop = true).isEmpty())
    }

    @Test fun keySpeedIsSavedAndLoaded() {
        val c = Clip(0, 60, keySpeed = 0.5f)
        assertEquals(0.5f, c.keySpeed, 1e-6f)
        assertEquals(1f, Clip(0, 60).keySpeed, 1e-6f)
        // keySpeed не влияет на animFrame: ключи уже пересчитаны, на кадре нет лишней работы
        assertEquals(Clip(0, 60).animFrame(30), c.animFrame(30))
        val json = java.io.File("src/main/java/com/vibetoon/domain/project/ProjectJson.kt").takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/com/vibetoon/domain/project/ProjectJson.kt")
        val src = json.readText()
        assertTrue(src.contains("\"kspeed\""))
        assertNotNull(ProjectJson)
    }

    // ---- 5. ручка перетаскивания ----

    @Test fun handleDragNeedsHoldScrollPassesThrough() {
        val slop = 8f
        assertEquals(HandleDragGate.Decision.WAIT, HandleDragGate.decide(50, 2f, slop))
        assertEquals(HandleDragGate.Decision.SCROLL, HandleDragGate.decide(50, 20f, slop))       // прокрутка с ручки - не перенос
        assertEquals(HandleDragGate.Decision.DRAG, HandleDragGate.decide(HandleDragGate.HOLD_MS, 3f, slop))
        assertEquals(HandleDragGate.Decision.RELEASED, HandleDragGate.decide(100, 0f, slop, pressed = false))
        assertEquals(HandleDragGate.Decision.SCROLL, HandleDragGate.decide(100, 0f, slop, consumed = true))
        assertTrue(HandleDragGate.HANDLE_DP < 36)   // зона уже прежних 36 dp
        assertTrue(HandleDragGate.GAP_DP >= 8)
    }

    // ---- 6. Play ----

    @Test fun warmupWaitsForTexturesFormsAndScenesButIsBounded() {
        assertFalse(PlayWarmup.ready(10, 1000L, false, 0))
        assertFalse(PlayWarmup.ready(10, 0L, true, 0))
        assertFalse(PlayWarmup.ready(10, 0L, false, 3))
        assertTrue(PlayWarmup.ready(10, 0L, false, 0))
        assertTrue(PlayWarmup.ready(PlayWarmup.MAX_MS, 1000L, true, 5))
        assertTrue(PlayWarmup.MAX_MS <= 1000L)
    }

    @Test fun warmupPrebuildsFirstHalfSecondInRange() {
        assertEquals(12, PlayWarmup.prebuildCount(24, 0, 299))
        assertEquals(15, PlayWarmup.prebuildCount(60, 0, 299))
        assertEquals(3, PlayWarmup.prebuildCount(24, 297, 299))
        assertEquals(listOf(0, 1, 2), PlayWarmup.prebuildFrames(6, 0, 0, 99))
        // у конца диапазона - по циклу с начала сцены
        assertEquals(listOf(98, 99, 10, 11), PlayWarmup.prebuildFrames(8, 98, 10, 99))
        assertTrue(PlayWarmup.prebuildFrames(24, 0, 5, 4).isEmpty())
    }

    @Test fun playSceneCacheIsBoundedLru() {
        val c = PlaySceneCache<String>(10) { it.length.toLong() }
        c.put(1, "aaaa"); c.put(2, "bbbb")
        assertEquals("aaaa", c.get(1))       // 1 стал свежим
        c.put(3, "cccc")                      // 12 > 10: вытесняется давний (2)
        assertNull(c.get(2)); assertEquals("aaaa", c.get(1)); assertEquals("cccc", c.get(3))
        assertEquals(8L, c.bytes)
        c.put(3, "cc"); assertEquals(6L, c.bytes)
        c.put(4, "x".repeat(11)); assertNull(c.get(4))   // больше бюджета - не кладётся
        c.clear(); assertEquals(0, c.size); assertEquals(0L, c.bytes)
    }

    @Test fun sceneShallowCopyKeepsArraysAndExtras() {
        val s = com.vibetoon.domain.render.SceneData(10, 20, intArrayOf(1, 2, 3, 4), FloatArray(8), intArrayOf(7))
        s.backgroundOverride = 0xFF000000.toInt()
        s.cropIds = intArrayOf(7)
        val c = s.shallowCopy()
        assertTrue(c !== s)
        assertSame(s.ops, c.ops); assertSame(s.params, c.params); assertSame(s.textureIds, c.textureIds)
        assertEquals(s.backgroundOverride, c.backgroundOverride)
        assertSame(s.cropIds, c.cropIds)
        assertTrue(PlaySceneCache.sceneBytes(s) > 0L)
    }
}
