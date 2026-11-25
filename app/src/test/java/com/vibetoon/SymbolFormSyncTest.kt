package com.vibetoon

import com.vibetoon.domain.anim.SelectedKeyCurves
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.SymbolFormCache
import com.vibetoon.domain.project.SymbolForms
import com.vibetoon.domain.project.SymbolMath
import com.vibetoon.domain.project.TimeEase
import com.vibetoon.domain.project.TimeKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пакет 5: на любом кадре сцены символ показывает ровно одну форму, ту же, что подсвечена в панели маркеров;
 * переходы между разными формами не проигрывают чужие формы; кэш готовых форм; компактные подписи; «Общая кривая»
 * для ключей времени.
 */
class SymbolFormSyncTest {
    // «Рот» из «Слоёв как кадров»: 6 слоёв -> кадры 0..5, у каждого слоя клип на свой кадр и маркер.
    private val layerIds = (1..6).toList()
    private val plan = SymbolMath.framesPlan(layerIds, layerIds.map { "Обычный слой ${29 + it}" })
    private val clips = plan.clips.associate { (id, s, e) -> id to Clip(s, e) }
    private val markers = plan.markers
    private val dur = plan.duration

    private fun visibleForms(local: Int) = layerIds.filter { clips.getValue(it).visibleAt(local) }

    @Test fun everySceneFrameShowsExactlyOneForm() {
        // 6 ключей с чередованием форм слева направо, со всеми видами переходов
        val eases = listOf(TimeEase.HOLD, TimeEase.LINEAR, TimeEase.SMOOTH, TimeEase.EASE_IN, TimeEase.EASE_OUT, TimeEase.LINEAR)
        val values = listOf(0, 3, 1, 5, 2, 4)
        val keys = values.mapIndexed { i, v -> TimeKey(i * 10, v, eases[i]) }
        for (f in 0 until 80) {
            val local = SymbolForms.frameAt(keys, f, dur, animated = false, loop = false, markers = markers)
            assertEquals("кадр $f: ровно одна форма", 1, visibleForms(local).size)
            // спринт 46: переход ключа действует как у обычных ключей - Hold держит форму, кривые ведут кадр к следующему ключу
            val expected = SymbolMath.timeAt(keys, f)!!.coerceIn(0, dur - 1)
            assertEquals("кадр $f: форма выбранного ключа", expected, local)
            // панель подсвечивает ту же форму, что видна на холсте
            assertEquals(local, SymbolForms.shownMarker(markers, local)!!.frame)
        }
    }

    @Test fun smoothInsideOneFormStillInterpolates() {
        // анимированная форма: маркеры на 0 и 10, форма «A» занимает кадры 0..9 - плавный ключ 0 -> 8 проигрывает кадры
        val m = listOf(FrameMarker(0, "A"), FrameMarker(10, "B"))
        val keys = listOf(TimeKey(0, 0, TimeEase.LINEAR), TimeKey(8, 8))
        assertEquals(4, SymbolForms.frameAt(keys, 4, 20, animated = true, loop = false, markers = m))
        // спринт 46: A -> B с Linear тоже интерполирует (кривые ключей символа работают), Hold - резкая смена на ключе
        val cross = listOf(TimeKey(0, 0, TimeEase.LINEAR), TimeKey(8, 10))
        assertEquals(5, SymbolForms.frameAt(cross, 4, 20, animated = true, loop = false, markers = m))
        assertEquals(10, SymbolForms.frameAt(cross, 8, 20, animated = true, loop = false, markers = m))
        val hold = listOf(TimeKey(0, 0, TimeEase.HOLD), TimeKey(8, 10))
        for (f in 0..7) assertEquals(0, SymbolForms.frameAt(hold, f, 20, animated = true, loop = false, markers = m))
        // без маркеров - прежняя интерполяция
        assertEquals(5, SymbolForms.frameAt(cross, 4, 20, animated = true, loop = false, markers = emptyList()))
    }

    @Test fun shownMarkerUsesFormSpanAndIgnoresAliases() {
        val m = listOf(FrameMarker(0, "Rest"), FrameMarker(3, "O"), FrameMarker(6, "Rest", form = 0))
        assertEquals("Rest", SymbolForms.shownMarker(m, 2)!!.name)
        assertEquals("O", SymbolForms.shownMarker(m, 7)!!.name)   // ссылка не начинает форму
        assertNull(SymbolForms.shownMarker(listOf(FrameMarker(4, "X")), 1))
    }

    @Test fun compactLabelsDropCommonWords() {
        val c = SymbolForms.compactLabels(listOf("Обычный слой 30", "Обычный слой 31", "Обычный слой 39"))
        assertEquals("30", c["Обычный слой 30"]); assertEquals("39", c["Обычный слой 39"])
        val mixed = SymbolForms.compactLabels(listOf("Rest", "O", "E"))
        assertEquals("O", mixed["O"])
        assertEquals("Обычный", SymbolForms.compactLabels(listOf("Обычный"))["Обычный"])
        // одно имя - префикс другого: у каждого остаётся слово
        val pre = SymbolForms.compactLabels(listOf("Рот", "Рот открыт"))
        assertEquals("Рот", pre["Рот"]); assertEquals("Рот открыт", pre["Рот открыт"])
    }

    @Test fun opaqueBoundsTrimForm() {
        val w = 10; val h = 8
        val px = IntArray(w * h)
        px[3 * w + 4] = 0xFF000000.toInt(); px[5 * w + 6] = 0x80000000.toInt()
        assertArrayEquals(intArrayOf(4, 3, 7, 6), SymbolForms.opaqueBounds(px, w, h, pad = 0))
        assertArrayEquals(intArrayOf(2, 1, 9, 8), SymbolForms.opaqueBounds(px, w, h, pad = 2))
        assertNull(SymbolForms.opaqueBounds(IntArray(w * h), w, h))
    }

    @Test fun formCacheKeepsStaleAsFallbackAndHonorsBudget() {
        val cache = SymbolFormCache(budgetBytes = 100)
        val a = SymbolFormCache.Key(7, 0, true); val b = SymbolFormCache.Key(7, 1, true); val c = SymbolFormCache.Key(7, 2, true)
        val r = floatArrayOf(0f, 0f, 1f, 1f)
        assertTrue(cache.put(a, SymbolFormCache.Entry(1, r, 40, cache.gen), emptySet()).isEmpty())
        cache.put(b, SymbolFormCache.Entry(2, r, 40, cache.gen), emptySet())
        assertNotNull(cache.fresh(a))
        cache.invalidate()                                   // символ правили
        assertNull(cache.fresh(a)); assertNotNull(cache.any(a))   // устаревшая - только запасная
        // новая форма той же пары заменяет старую текстуру
        assertEquals(listOf(1), cache.put(a, SymbolFormCache.Entry(3, r, 40, cache.gen), emptySet()))
        // бюджет: вытесняется устаревшая b, а не свежая a и не закреплённая
        assertEquals(listOf(2), cache.put(c, SymbolFormCache.Entry(4, r, 40, cache.gen), setOf(a)))
        assertNull(cache.any(b))
        assertTrue(cache.bytes <= 100)
        assertEquals(3, cache.anyOf(7, true)!!.second.texId)   // кадр 0 предпочтительнее
        assertEquals(setOf(3, 4), cache.clear().toSet())
        assertEquals(0, cache.size)
    }

    @Test fun sharedCurveFindsSymbolTimeKeys() {
        val sel = setOf(100 to 0, 100 to 10, 100 to 5, 200 to 3, 300 to 1)
        val tk = mapOf(100 to listOf(TimeKey(0, 0), TimeKey(10, 2)), 200 to listOf(TimeKey(3, 1)))
        val t = SelectedKeyCurves.timeKeyTargets(sel, tk)
        assertEquals(mapOf(100 to setOf(0, 10), 200 to setOf(3)), t)
        assertTrue(SelectedKeyCurves.timeKeyTargets(setOf(300 to 1), tk).isEmpty())
        // выделены маркеры-формы 2 и 1: ключи, выбирающие эти формы
        assertEquals(mapOf(100 to setOf(10), 200 to setOf(3)), SelectedKeyCurves.markerTimeKeyTargets(setOf(1, 2), tk))
    }
}
