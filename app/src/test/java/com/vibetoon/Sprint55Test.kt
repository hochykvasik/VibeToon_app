package com.vibetoon

import com.vibetoon.domain.anim.ClipSpeed
import com.vibetoon.domain.anim.Effect
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.model.MergePlan
import com.vibetoon.domain.perf.EditorTrimPolicy
import com.vibetoon.domain.project.SymbolBoneLink
import com.vibetoon.domain.raster.LayerMergeMath
import com.vibetoon.domain.render.PlaySceneCache
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.SceneSectionTracker
import com.vibetoon.domain.ui.TabIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 55: производительность и память - время только у анимированных эффектов (кэш эффектов и групп живёт весь
 * Play), разделы сцены без повторной отправки в native, отдача памяти по onTrimMemory; регрессия инвариантов
 * спринта 54 (индексы вкладок, слияние, символы на костях, скорость 0.10x–3.0x).
 */
class Sprint55Test {
    // ---- время в записях эффектов ----

    @Test fun staticEffectsHaveNoTimeSoCachesSurvivePlay() {
        val static = listOf(EffectMath.CHROMA, EffectMath.BLUR, EffectMath.COLOR, EffectMath.PIXELATE, EffectMath.GLOW,
            EffectMath.SHADOW, EffectMath.INVERT, EffectMath.VIGNETTE, EffectMath.STROKE, EffectMath.MIRROR, EffectMath.TILES,
            EffectMath.MOTION, EffectMath.ZOOM, EffectMath.INNER_GLOW, EffectMath.HALFTONE)
        for (t in static) {
            val e = EffectMath.create(t) ?: continue
            assertFalse("$t", EffectMath.usesTime(t))
            val a = EffectMath.records(listOf(e), 0.5f).single()
            val b = EffectMath.records(listOf(e), 7.25f).single()
            assertEquals("время статичного эффекта $t", 0f, a[9], 0f)
            // запись на разных кадрах Play побайтно одинакова - ключ кэша не меняется
            assertTrue("$t", a.contentEquals(b))
        }
    }

    @Test fun animatedEffectsKeepTime() {
        for (t in listOf(EffectMath.WAVE, EffectMath.NOISE, EffectMath.SHAKE, EffectMath.RGB_SPLIT, EffectMath.PULSE, EffectMath.DISPLACE)) {
            assertTrue("$t", EffectMath.usesTime(t))
            val e = EffectMath.create(t)!!
            assertEquals(1.5f, EffectMath.records(listOf(e), 1.5f).single()[9], 0f)
            assertFalse(EffectMath.records(listOf(e), 1.5f).single().contentEquals(EffectMath.records(listOf(e), 1.6f).single()))
        }
        assertTrue(EffectMath.usesTime(EffectMath.LINE_BOIL))
        // цветокоррекция слоя (неявный первый эффект) - тоже статична
        val color = Effect(EffectMath.COLOR, listOf(0.2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        assertEquals(0f, EffectMath.records(listOf(color), 3f).single()[9], 0f)
    }

    @Test fun shaderTimeModesMatchKotlinList() {
        // шейдер эффектов читает время (uView.w) ровно в тех режимах, что EffectMath.usesTime
        val cpp = java.io.File("src/main/cpp/render/Renderer.cpp").takeIf { it.isFile } ?: java.io.File("app/src/main/cpp/render/Renderer.cpp")
        val src = cpp.readText()
        val modes = HashSet<Int>()
        var mode = -1
        for (line in src.lines()) {
            Regex("uMode == (\\d+)").find(line)?.let { mode = it.groupValues[1].toInt() }
            if ("uView.w" in line && mode >= 0) modes += mode
        }
        assertEquals(setOf(9, 12, 13, 17, 18, 19, 24), modes)
        for (m in modes) assertTrue("$m", EffectMath.usesTime(m))
    }

    // ---- разделы сцены в native ----

    @Test fun sectionTrackerSkipsSameArraysAndEmpties() {
        val t = SceneSectionTracker()
        val ops = intArrayOf(0, 1, 0, 0); val params = FloatArray(8)
        assertTrue(t.changed(SceneSectionTracker.SCENE, ops, params))
        assertFalse(t.changed(SceneSectionTracker.SCENE, ops, params))            // повтор кадра из кэша Play
        assertTrue(t.changed(SceneSectionTracker.SCENE, ops.copyOf(), params))     // новый массив - отправить
        assertTrue(t.changed(SceneSectionTracker.WARPS, IntArray(0), FloatArray(0)))
        assertFalse(t.changed(SceneSectionTracker.WARPS, IntArray(0), FloatArray(0)))   // пустые - как в прошлый раз
        assertTrue(t.changed(SceneSectionTracker.WARPS, intArrayOf(3), FloatArray(32)))
        assertTrue(t.changed(SceneSectionTracker.CANVAS, 1920, 1080))
        assertFalse(t.changed(SceneSectionTracker.CANVAS, 1920, 1080))             // числа - по значению
        assertTrue(t.changed(SceneSectionTracker.CANVAS, 1080, 1920))
        // пустой IntArray и пустой FloatArray - разные типы
        assertTrue(t.changed(SceneSectionTracker.TURB, IntArray(0)))
        assertTrue(t.changed(SceneSectionTracker.TURB, FloatArray(0)))
        t.reset()   // новый native-рендерер: всё отправляется заново
        assertTrue(t.changed(SceneSectionTracker.SCENE, ops, params))
        assertTrue(t.changed(SceneSectionTracker.CANVAS, 1080, 1920))
    }

    @Test fun cachedPlaySceneReusesSectionArrays() {
        // повтор кадра из PlaySceneCache - shallowCopy с теми же массивами: трекер не отправляет их заново
        val s = com.vibetoon.domain.render.SceneData(10, 10, intArrayOf(0, 1, 0, 0), FloatArray(8), intArrayOf(1))
        val cache = PlaySceneCache<com.vibetoon.domain.render.SceneData>(1 shl 20, PlaySceneCache.Companion::sceneBytes)
        cache.put(5, s)
        val again = cache.get(5)!!.shallowCopy()
        val t = SceneSectionTracker()
        assertTrue(t.changed(SceneSectionTracker.SCENE, s.ops, s.params))
        assertFalse(t.changed(SceneSectionTracker.SCENE, again.ops, again.params))
    }

    // ---- память ----

    @Test fun trimPolicyLevels() {
        assertFalse(EditorTrimPolicy.dropPlayScenes(5))
        assertTrue(EditorTrimPolicy.dropPlayScenes(10))
        assertFalse(EditorTrimPolicy.dropDeformCaches(10))
        assertTrue(EditorTrimPolicy.dropDeformCaches(15))
        assertTrue(EditorTrimPolicy.dropDeformCaches(20))   // фон
        assertTrue(EditorTrimPolicy.dropDeformCaches(80))
        assertEquals(setOf(3, 9), EditorTrimPolicy.stale(mapOf(1 to "a", 3 to "b", 9 to "c"), setOf(1, 2)))
        assertTrue(EditorTrimPolicy.stale(emptyMap<Int, String>(), setOf(1)).isEmpty())
    }

    @Test fun playSceneCacheClearsCompletely() {
        val c = PlaySceneCache<String>(100) { it.length.toLong() }
        for (i in 0 until 50) c.put(i, "xx")
        assertEquals(50, c.size); assertEquals(100L, c.bytes)
        c.put(99, "x".repeat(10))
        assertTrue(c.bytes <= 100L)
        c.clear()
        assertEquals(0, c.size); assertEquals(0L, c.bytes); assertNull(c.get(99))
    }

    // ---- регрессия инвариантов спринта 54 ----

    @Test fun tabIndexStaysInsideShrinkingLists() {
        for (size in 0..8) for (index in -3..12) {
            val c = TabIndex.clamp(index, size)
            if (size == 0) assertEquals(0, c) else assertTrue(c in 0 until size)
            assertEquals(List(size) { it }.getOrNull(index), TabIndex.positionOrNull(List(size) { it }, index))
        }
    }

    @Test fun legacyClipSpeedRetimesAllTracksConsistently() {
        // обычный случай: старой скорости нет - ключи и плашка одним множителем
        val plain = ClipSpeed.factors(1f, 1f, 0.5f)
        assertEquals(2f, plain.keys, 1e-6f); assertEquals(2f, plain.length, 1e-6f)
        // старый проект: клип 2x (только трансформация шла вдвое быстрее, остальные дорожки - 1x) -> 2x:
        // ключи всех дорожек сжимаются вдвое (теперь и эффекты/сетка идут 2x), длина плашки не меняется
        val legacy = ClipSpeed.factors(1f, 2f, 2f)
        assertEquals(0.5f, legacy.keys, 1e-6f); assertEquals(1f, legacy.length, 1e-6f)
        // уже применённая 0.5x -> 1x: ключи и плашка назад вдвое
        val back = ClipSpeed.factors(0.5f, 1f, 1f)
        assertEquals(0.5f, back.keys, 1e-6f); assertEquals(0.5f, back.length, 1e-6f)
        // диапазон 0.10x–3.0x: множители конечны и положительны на всех пресетах
        for (a in ClipSpeed.PRESETS) for (b in ClipSpeed.PRESETS) {
            val f = ClipSpeed.factors(a, 1f, b)
            assertTrue(f.keys.isFinite() && f.keys > 0f && f.length > 0f)
            assertEquals(a / b, f.keys, 1e-4f)
        }
        // ключи после замедления и обратного ускорения возвращаются на свои кадры (без порчи)
        val keys = listOf(4, 10, 17, 30, 31)
        val slow = ClipSpeed.retimeAll(keys, 4, ClipSpeed.factors(1f, 1f, 0.25f).keys, 10_000)
        assertEquals(keys, ClipSpeed.retimeAll(slow, 4, ClipSpeed.factors(0.25f, 1f, 1f).keys, 10_000))
    }

    @Test fun mergeKeepsBlendModesAndNeverGroups() {
        // любые слои без групп - один слой (пиксели нижнего), группа - отказ, а не новая группа
        for (n in 2..6) {
            val nodes = (1..n).map { MergePlan.Node(it, animated = it == 1) }
            val plan = MergePlan.plan(nodes) as MergePlan.Result.Pixels
            assertEquals(1, plan.target); assertEquals((2..n).toList(), plan.tops)
        }
        assertTrue(MergePlan.plan(listOf(MergePlan.Node(1), MergePlan.Node(2, isGroup = true))) is MergePlan.Result.Refuse)
        // Multiply и Screen в mergeMany - те же формулы, что у рендерера
        fun px(v: Int) = byteArrayOf(v.toByte(), v.toByte(), v.toByte(), 255.toByte())
        val q = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val map = LayerMergeMath.pixelMap(q, 1, 1, q, 1, 1)!!
        val mul = LayerMergeMath.mergeMany(px(128), 0, 1, 1, listOf(LayerMergeMath.Top(px(128), 0, 1, 1, map, SceneBuilder.BLEND_MULTIPLY, 1f, false)))!!
        assertTrue((mul.after[0].toInt() and 255) in 63..65)
        val scr = LayerMergeMath.mergeMany(px(128), 0, 1, 1, listOf(LayerMergeMath.Top(px(128), 0, 1, 1, map, SceneBuilder.BLEND_SCREEN, 1f, false)))!!
        assertTrue((scr.after[0].toInt() and 255) in 190..193)
        // одна пара до/после: Undo возвращает исходные пиксели
        assertEquals(128, mul.before[0].toInt() and 255)
    }

    @Test fun symbolBoneLinkSurvivesReloadAndNesting() {
        // два экземпляра одного символа: оба получают кость; повторная загрузка ничего не меняет
        val links = mapOf(5 to 900, 6 to 900)
        val out = SymbolBoneLink.migrate(links, mapOf(50 to setOf(5, 6)), mapOf(70 to 50, 71 to 50))
        assertEquals(900, out[70]); assertEquals(900, out[71]); assertNull(out[5]); assertNull(out[6])
        assertTrue(out === SymbolBoneLink.migrate(out, mapOf(50 to setOf(5, 6)), mapOf(70 to 50, 71 to 50)))
        // снимок формы - без внешних привязок участников, экспорт сцены - с привязкой экземпляра
        assertEquals(mapOf(70 to 900, 71 to 900), SymbolBoneLink.snapshotLinks(out, setOf(5, 6)))
    }
}
