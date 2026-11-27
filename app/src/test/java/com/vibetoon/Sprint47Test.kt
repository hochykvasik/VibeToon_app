package com.vibetoon

import com.vibetoon.domain.project.FormBlend
import com.vibetoon.domain.project.FormRef
import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.ProjectListMath
import com.vibetoon.domain.project.SymbolFormCache
import com.vibetoon.domain.project.SymbolForms
import com.vibetoon.domain.project.SymbolPlayback
import com.vibetoon.domain.project.TimeEase
import com.vibetoon.domain.project.TimeKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 47: переходы между формами символа (растворение вместо прохода по чужим формам), длительность старых
 * проектов по маркерам, смешение снимков форм, зона «убрать из папки».
 */
class Sprint47Test {
    // рот из отчёта: закрытый (30), открытый (31..38), «О» (39); маркеры - как на скриншоте «Новый проект 2»
    private val mouth = listOf(FrameMarker(30, "закрытый"), FrameMarker(31, "открытый"), FrameMarker(39, "О"))
    private val custom = listOf(0.3f, 1.6f, 0.6f, 1.4f)   // своя кривая с перелётом

    private fun keysWith(ease: Int, curve: List<Float>? = null) = listOf(
        TimeKey(0, 30, ease, curve), TimeKey(10, 31, ease, curve), TimeKey(20, 39, ease, curve), TimeKey(30, 30)
    )

    private fun ref(keys: List<TimeKey>, f: Int, dur: Int = 40, markers: List<FrameMarker> = mouth) =
        SymbolForms.frameRef(keys, f, dur, true, SymbolPlayback.HOLD, 0, markers)

    // ---------------------------------------------------------------- 1. без «каши» между формами

    @Test fun noForeignFormBetweenKeysWithAnyCurve() {
        for ((ease, curve) in listOf(TimeEase.LINEAR to null, TimeEase.SMOOTH to null, TimeEase.EASE_IN to null, TimeEase.EASE_OUT to null, TimeEase.CUSTOM to custom)) {
            val keys = keysWith(ease, curve)
            for (f in 0..40) {
                val r = ref(keys, f)
                val i = keys.indexOfLast { it.frame <= f }
                val allowed = setOf(keys[i].value, keys.getOrNull(i + 1)?.value ?: keys[i].value)
                assertTrue("ease $ease кадр $f: $r - только формы двух соседних ключей", r.local in allowed && (!r.blended || r.to in allowed))
            }
            // главный случай отчёта: между «О» (39) и закрытым (30) никогда не показывается открытый рот (31..38)
            for (f in 20..30) {
                val r = ref(keys, f)
                assertFalse("кадр $f: открытый рот в переходе О -> закрытый", r.local in 31..38 || r.to in 31..38)
            }
        }
    }

    @Test fun keysShowExactlyTheirFormAndCurveDrivesTheDissolve() {
        val lin = keysWith(TimeEase.LINEAR)
        assertEquals(FormRef(30), ref(lin, 0)); assertEquals(FormRef(31), ref(lin, 10))
        assertEquals(FormRef(39), ref(lin, 20)); assertEquals(FormRef(30), ref(lin, 30))
        assertEquals(FormRef(39, 30, 8), ref(lin, 25))   // посередине - ровно половина
        assertEquals(39, ref(lin, 22).dominant); assertEquals(30, ref(lin, 28).dominant)
        // кривые различимы: Ease In отстаёт, Ease Out опережает
        val easeIn = ref(keysWith(TimeEase.EASE_IN), 25); val easeOut = ref(keysWith(TimeEase.EASE_OUT), 25)
        assertTrue(easeIn.weight < 0.5f); assertTrue(easeOut.weight > 0.5f)
        assertNotEquals(easeIn, easeOut)
        // доля растёт монотонно (Linear)
        val w = (20..30).map { ref(lin, it).let { r -> if (r.blended) r.weight else if (r.local == 39) 0f else 1f } }
        assertTrue(w.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun holdSwitchesSharplyAtTheNextKey() {
        val hold = keysWith(TimeEase.HOLD)
        for (f in 0..9) assertEquals(FormRef(30), ref(hold, f))
        for (f in 10..19) assertEquals(FormRef(31), ref(hold, f))
        for (f in 20..29) assertEquals(FormRef(39), ref(hold, f))
        assertEquals(FormRef(30), ref(hold, 30))
    }

    @Test fun insideOneFormFramesFollowTheCurveButStayInTheForm() {
        // анимированный «открытый» рот 31..38: кадры по кривой, своя кривая с перелётом не уходит в «О» (39)
        val keys = listOf(TimeKey(0, 31, TimeEase.CUSTOM, custom), TimeKey(10, 38))
        val frames = (0..10).map { ref(keys, it) }
        assertTrue(frames.none { it.blended })
        assertTrue(frames.all { it.local in 31..38 })
        assertEquals(31, frames.first().local); assertEquals(38, frames.last().local)
        val lin = listOf(TimeKey(0, 31, TimeEase.LINEAR), TimeKey(10, 37))
        assertEquals(34, ref(lin, 5).local)
    }

    @Test fun symbolWithoutMarkersKeepsTimeRemapping() {
        val keys = listOf(TimeKey(0, 0, TimeEase.LINEAR), TimeKey(10, 10))
        assertEquals(FormRef(5), ref(keys, 5, 20, emptyList()))
    }

    @Test fun formRefBasics() {
        assertFalse(FormRef(3).blended); assertEquals(3, FormRef(3).dominant); assertEquals(0f, FormRef(3).weight)
        assertFalse(FormRef(3, 3, 8).blended); assertFalse(FormRef(3, 5, 0).blended); assertFalse(FormRef(3, 5, 16).blended)
        val m = FormRef(3, 5, 8)
        assertTrue(m.blended); assertEquals(5, m.dominant); assertEquals(0.5f, m.weight)
        assertEquals(3, FormRef(3, 5, 7).dominant)
        val seen = SymbolForms.blendsOn(20..30) { ref(keysWith(TimeEase.LINEAR), it) }
        assertTrue(seen.isNotEmpty() && seen.all { it.blended && it.local == 39 && it.to == 30 })
    }

    @Test fun blendCacheKeyDiffersFromFormKey() {
        val plain = SymbolFormCache.Key(7, 39, true)
        val mixed = SymbolFormCache.Key(7, 39, true, 30, 8)
        assertNotEquals(plain, mixed); assertTrue(mixed.blended); assertFalse(plain.blended)
    }

    // ---------------------------------------------------------------- 2. старые проекты: длительность по маркерам

    @Test fun staleDurationNoLongerHidesTheLastForm() {
        assertEquals(40, SymbolForms.effectiveDuration(31, mouth))
        assertEquals(50, SymbolForms.effectiveDuration(50, mouth))
        assertEquals(1, SymbolForms.effectiveDuration(0, emptyList()))
        assertEquals(13, SymbolForms.effectiveDuration(5, listOf(FrameMarker(4, "a", form = 12))))
        // ключ на «О» (39) в старом проекте с длительностью 31: раньше упирался в 30 (закрытый рот)
        val keys = listOf(TimeKey(0, 39, TimeEase.HOLD), TimeKey(10, 30))
        assertEquals(39, SymbolForms.frameAt(keys, 3, 31, animated = true, loop = false, markers = mouth))
        assertEquals("О", SymbolForms.shownMarker(mouth, 39)!!.name)
    }

    // ---------------------------------------------------------------- смешение снимков форм

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test fun mixPixelIsPremultipliedAndExactAtEnds() {
        assertEquals(red, FormBlend.mixPixel(red, blue, 0))
        assertEquals(blue, FormBlend.mixPixel(red, blue, 256))
        assertEquals(red, FormBlend.mixPixel(red, red, 100))
        assertEquals(0, FormBlend.mixPixel(0, 0, 128))
        // с прозрачным: цвет не темнеет, прозрачность - пополам
        val half = FormBlend.mixPixel(red, 0, 128)
        assertEquals(0xFF0000, half and 0xFFFFFF)
        assertTrue((half ushr 24) in 126..130)
        val mid = FormBlend.mixPixel(red, blue, 128)
        assertEquals(255, mid ushr 24)
        assertTrue(((mid shr 16) and 255) in 126..130 && (mid and 255) in 126..130)
    }

    private fun image(w: Int, h: Int, color: Int, l: Int, t: Int, r: Int, b: Int) =
        IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in l until r && y in t until b) color else 0 }

    @Test fun cropBlendRectAndFullRoundTrip() {
        val w = 40; val h = 30
        val a = FormBlend.crop(image(w, h, red, 10, 10, 14, 12), w, h)
        val b = FormBlend.crop(image(w, h, blue, 20, 5, 25, 8), w, h)
        assertFalse(a.empty)
        assertTrue(a.px.size < w * h)
        assertEquals(red, a.at(11, 11)); assertEquals(0, a.at(0, 0))
        // крайние доли - ровно исходные картинки
        assertArrayEquals(image(w, h, red, 10, 10, 14, 12), FormBlend.toFull(FormBlend.blend(a, b, 0f)))
        assertArrayEquals(image(w, h, blue, 20, 5, 25, 8), FormBlend.toFull(FormBlend.blend(a, b, 1f)))
        val m = FormBlend.blend(a, b, 0.5f)
        assertTrue(m.box[0] <= 10 && m.box[2] >= 25 && m.box[1] <= 5 && m.box[3] >= 12)
        assertTrue((m.at(11, 11) ushr 24) in 120..135); assertTrue((m.at(21, 6) ushr 24) in 120..135)
        val r = FormBlend.rect(m)
        assertEquals(m.box[0].toFloat() / w, r[0]); assertEquals(m.w.toFloat() / w, r[2])
        // пустые формы
        val empty = FormBlend.crop(IntArray(w * h), w, h)
        assertTrue(empty.empty)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), FormBlend.rect(empty), 0f)
        assertArrayEquals(image(w, h, red, 10, 10, 14, 12), FormBlend.toFull(FormBlend.blend(a, empty, 0f)))
        val full = IntArray(4) { red }; val out = IntArray(4)
        FormBlend.blendFull(full, IntArray(4) { blue }, 1f, out)
        assertArrayEquals(IntArray(4) { blue }, out)
    }

    // ---------------------------------------------------------------- 4. зона «убрать из папки»

    @Test fun topDropZoneIsHeaderOrAbove() {
        assertTrue(ProjectListMath.overTopDropZone(20f, 0, 48))
        assertTrue(ProjectListMath.overTopDropZone(-30f, 0, 48))
        assertFalse(ProjectListMath.overTopDropZone(60f, 0, 48))
        assertTrue(ProjectListMath.overTopDropZone(-1f, null, null))   // шапка прокручена - верхний край сетки
        assertFalse(ProjectListMath.overTopDropZone(10f, null, null))
    }
}
