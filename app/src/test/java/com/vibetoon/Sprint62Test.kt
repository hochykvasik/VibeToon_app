package com.vibetoon

import com.vibetoon.domain.Hotkeys
import com.vibetoon.domain.input.EditorInputPolicy
import com.vibetoon.domain.paint.BrushSizePreview
import com.vibetoon.domain.paint.ColorMemory
import com.vibetoon.domain.paint.ColorModels
import com.vibetoon.domain.ui.QuickClear
import com.vibetoon.domain.ui.ScrubGesture
import com.vibetoon.domain.ui.TutorialLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

/**
 * Спринт 62: поведение пяти UX-улучшений - кружок размера кисти, быстрая очистка слоя, память цвета при яркости 0,
 * защита полей размера/прозрачности от случайной протяжки, раскладка уроков - и исправления аудита.
 */
class Sprint62Test {
    private fun center(p: FloatArray): Pair<Float, Float> {
        var x = 0f; var y = 0f
        for (i in 0 until p.size / 2) { x += p[i * 2]; y += p[i * 2 + 1] }
        return x / (p.size / 2) to y / (p.size / 2)
    }
    private fun maxDist(p: FloatArray, c: Pair<Float, Float>) = (0 until p.size / 2).maxOf { hypot(p[it * 2] - c.first, p[it * 2 + 1] - c.second) }
    private fun minDist(p: FloatArray, c: Pair<Float, Float>) = (0 until p.size / 2).minOf { hypot(p[it * 2] - c.first, p[it * 2 + 1] - c.second) }

    // ---- 1. кружок размера кисти ----

    @Test fun previewRadiusFollowsZoom() {
        // слой 1000 px показан на экране шириной 500 px (зум 50 %): кисть 100 px -> кружок радиуса 25 px
        val a = BrushSizePreview.axes(500f, 0f, 0f, 500f, 1000, 1000)!!
        val p = BrushSizePreview.outline(200f, 300f, 100f, 1f, a)
        assertEquals(BrushSizePreview.SEGMENTS * 2, p.size)
        val c = center(p)
        assertEquals(200f, c.first, 0.01f); assertEquals(300f, c.second, 0.01f)
        assertEquals(25f, maxDist(p, c), 0.01f); assertEquals(25f, minDist(p, c), 0.01f)
        // зум 200 % - вчетверо больше
        val z = BrushSizePreview.axes(2000f, 0f, 0f, 2000f, 1000, 1000)!!
        assertEquals(100f, BrushSizePreview.radiusPx(100f, 1f, z), 0.01f)
    }

    @Test fun previewRotationAndFlipKeepRoundBrushRound() {
        for (deg in listOf(0f, 33f, 90f, 180f, 271f)) for (fx in listOf(false, true)) for (fy in listOf(false, true)) {
            val a = BrushSizePreview.canvasAxes(1.5f, (deg * PI / 180).toFloat(), fx, fy)!!
            val p = BrushSizePreview.outline(0f, 0f, 40f, 1f, a)
            val c = center(p)
            assertEquals(30f, maxDist(p, c), 0.05f)
            assertEquals(30f, minDist(p, c), 0.05f)
            assertEquals("отражение одной оси - зеркальные оси", fx != fy, a.mirrored)
        }
    }

    @Test fun flatPenEllipseTurnsWithCanvas() {
        // «Плоское перо» (roundness 0.25): длинная ось вдоль X картинки; холст повёрнут на 90° - длинная ось вертикальна
        val a = BrushSizePreview.canvasAxes(1f, (PI / 2).toFloat(), false, false)!!
        val p = BrushSizePreview.outline(0f, 0f, 80f, 0.25f, a)
        var maxAbsX = 0f; var maxAbsY = 0f
        for (i in 0 until p.size / 2) { maxAbsX = maxOf(maxAbsX, kotlin.math.abs(p[i * 2])); maxAbsY = maxOf(maxAbsY, kotlin.math.abs(p[i * 2 + 1])) }
        assertEquals(40f, maxAbsY, 0.05f)
        assertEquals(10f, maxAbsX, 0.05f)
        assertEquals(40f, BrushSizePreview.radiusPx(80f, 0.25f, a), 0.05f)
    }

    @Test fun previewSkipsDegenerateInput() {
        assertNull(BrushSizePreview.axes(0f, 0f, 0f, 0f, 100, 100))
        assertNull(BrushSizePreview.axes(Float.NaN, 0f, 0f, 1f, 100, 100))
        assertNull(BrushSizePreview.axes(1f, 0f, 0f, 1f, 0, 100))
        assertNull(BrushSizePreview.canvasAxes(0f, 0f, false, false))
        val a = BrushSizePreview.canvasAxes(1f, 0f, false, false)!!
        assertEquals(0, BrushSizePreview.outline(0f, 0f, Float.NaN, 1f, a).size)
        assertEquals(0, BrushSizePreview.outline(0f, 0f, -5f, 1f, a).size)
        assertEquals("меньше 2 px - не рисуем", 0, BrushSizePreview.outline(0f, 0f, 3f, 1f, a).size)
        assertEquals(1f, BrushSizePreview.roundnessOf(Float.NaN), 0f)
        assertEquals(0.05f, BrushSizePreview.roundnessOf(0f), 0f)
        assertNull(BrushSizePreview.center(0f, 100f))
        assertEquals(150f to 200f, BrushSizePreview.center(300f, 400f))
    }

    @Test fun previewLeavesNoTrailAfterRelease() {
        assertTrue(BrushSizePreview.visible(true, 0L, 1000L))
        assertFalse("отпустили - сразу нет", BrushSizePreview.visible(false, 0L, 1000L))
        assertTrue(BrushSizePreview.visible(false, 1000L, 1000L + BrushSizePreview.PULSE_MS - 1))
        assertFalse(BrushSizePreview.visible(false, 1000L, 1000L + BrushSizePreview.PULSE_MS))
        assertFalse("часы назад - не залипает", BrushSizePreview.visible(false, 5000L, 1000L))
    }

    // ---- 2. быстрая очистка ----

    @Test fun quickClearStates() {
        assertEquals(QuickClear.State.READY, QuickClear.state(QuickClear.Kind.RASTER, false, false))
        assertEquals(QuickClear.State.READY, QuickClear.state(QuickClear.Kind.VECTOR, false, false, 3))
        assertEquals(QuickClear.State.EMPTY, QuickClear.state(QuickClear.Kind.VECTOR, false, false, 0))
        assertEquals(QuickClear.State.LOCKED, QuickClear.state(QuickClear.Kind.RASTER, true, false))
        assertEquals(QuickClear.State.PLAYING, QuickClear.state(QuickClear.Kind.RASTER, true, true))
        assertEquals(QuickClear.State.NO_LAYER, QuickClear.state(QuickClear.Kind.NONE, false, false))
        assertEquals(QuickClear.State.NOT_PAINTABLE, QuickClear.state(QuickClear.Kind.OTHER, false, false))
        for (s in QuickClear.State.entries) {
            assertEquals(s == QuickClear.State.READY, QuickClear.enabled(s))
            assertTrue(QuickClear.reason(s).isNotBlank())
        }
    }

    // ---- 3. цвет при яркости 0 ----

    @Test fun brightnessZeroAndBackRestoresColor() {
        var m = ColorMemory.open(0xFF3366CCL, null)
        val h0 = m.h; val s0 = m.s
        m = ColorMemory.withValue(m, 0f)
        assertEquals(0xFF000000L, m.color and 0xFFFFFFFFL)
        assertEquals("маркер не уходит в центр", h0, m.h, 0.01f); assertEquals(s0, m.s, 0.001f)
        m = ColorMemory.withValue(m, 1f)
        val back = ColorModels.hsbOf(m.color)
        assertEquals(h0, back.h, 1f); assertEquals(s0, back.s, 0.01f)
    }

    @Test fun reopeningOnBlackKeepsMarkerAndLitValue() {
        val start = ColorMemory.open(0xFFCC3366L, null)
        val dark = ColorMemory.withValue(ColorMemory.withValue(start, 0.4f), 0f)
        val saved = ColorMemory.decode(ColorMemory.encode(dark))
        assertNotNull(saved)
        val re = ColorMemory.open(0xFF000000L, saved)
        assertEquals(start.h, re.h, 0.01f); assertEquals(start.s, re.s, 0.001f)
        assertEquals(0f, re.v, 0f)
        assertEquals("яркость до обнуления", 0.4f, re.lit, 0.001f)
        // тап по кругу на чёрном - прежняя яркость, а не 100 %
        val picked = ColorMemory.pickWheel(re, 120f, 0.5f)
        assertEquals(0.4f, picked.v, 0.001f)
        // без записи тап по кругу на чёрном - 100 %
        assertEquals(1f, ColorMemory.pickWheel(ColorMemory.open(0xFF000000L, null), 10f, 1f).v, 0f)
    }

    @Test fun paletteAndSlidersKeepHueOfGrey() {
        var m = ColorMemory.open(0xFF2080FFL, null)
        val h = m.h
        m = ColorMemory.withArgb(m, 0xFF808080L)   // серый из палитры: насыщенность 0, оттенок остаётся
        assertEquals(h, m.h, 0.01f); assertEquals(0f, m.s, 0f)
        m = ColorMemory.withArgb(m, 0xFF000000L)   // чёрный: и оттенок, и (нулевая) насыщенность
        assertEquals(h, m.h, 0.01f)
        m = ColorMemory.withSat(m, 1f)
        m = ColorMemory.withValue(m, 1f)
        assertEquals(h, ColorModels.hsbOf(m.color).h, 1f)
        // насыщенный цвет из палитры берётся как есть
        val red = ColorMemory.withArgb(m, 0xFFFF0000L)
        assertEquals(0f, red.h, 0.01f); assertEquals(1f, red.s, 0f); assertEquals(1f, red.v, 0f)
    }

    @Test fun reopeningWithAnotherColorUsesThatColor() {
        val memo = ColorMemory.withValue(ColorMemory.open(0xFF00FF00L, null), 0f)
        val m = ColorMemory.open(0xFFFF0000L, memo)
        assertEquals(0f, m.h, 0.01f); assertEquals(1f, m.v, 0f)
        // точные значения записи при том же цвете (без потерь 8-битного округления)
        val exact = ColorMemory.of(123.456f, 0.4321f, 0.7f)
        assertEquals(123.456f, ColorMemory.open(exact.argb, exact).h, 0.001f)
    }

    @Test fun colorMemoRejectsGarbage() {
        for (bad in listOf(null, "", "v2;FF000000;0;0;0;1", "v1;zz;0;0;0;1", "v1;FF000000;NaN;0;0;1", "v1;FFFF0000;120;1;1;1", "v1;1;2"))
            assertNull(bad, ColorMemory.decode(bad))
        val m = ColorMemory.of(400f, 2f, -1f, 0f)
        assertEquals(40f, m.h, 0.01f); assertEquals(1f, m.s, 0f); assertEquals(0f, m.v, 0f); assertEquals(1f, m.lit, 0f)
    }

    // ---- 4. поля «потяни» в прокручиваемой строке ----

    @Test fun phoneSwipeScrollsRowWithoutChangingValue() {
        val hold = ScrubGesture.Mode.HOLD
        assertEquals(ScrubGesture.Decision.SCROLL, ScrubGesture.decide(hold, 40f, 2f, 60L, false, 8f))
        assertEquals(ScrubGesture.Decision.SCROLL, ScrubGesture.decide(hold, -40f, 0f, 60L, false, 8f))
        assertEquals("свайп и после удержания, но до порога - всё равно прокрутка", ScrubGesture.Decision.SCROLL, ScrubGesture.decide(hold, 30f, 0f, 500L, false, 8f))
        assertEquals(ScrubGesture.Decision.PENDING, ScrubGesture.decide(hold, 3f, 1f, 100L, false, 8f))
        assertEquals(ScrubGesture.Decision.SCRUB, ScrubGesture.decide(hold, 3f, 1f, ScrubGesture.HOLD_MS, false, 8f))
        assertEquals(ScrubGesture.Decision.TAP, ScrubGesture.decide(hold, 1f, 1f, 90L, true, 8f))
    }

    @Test fun verticalScrollNeverScrubs() {
        for (mode in ScrubGesture.Mode.entries) {
            assertEquals(ScrubGesture.Decision.SCROLL, ScrubGesture.decide(mode, 5f, 30f, 50L, false, 8f))
            assertEquals(ScrubGesture.Decision.SCROLL, ScrubGesture.decide(mode, -2f, -30f, 900L, false, 8f))
            assertEquals(ScrubGesture.Decision.SCROLL, ScrubGesture.decide(mode, Float.NaN, 0f, 50L, false, 8f))
        }
    }

    @Test fun tabletDragsImmediately() {
        val d = ScrubGesture.Mode.DIRECT
        assertEquals(ScrubGesture.Decision.SCRUB, ScrubGesture.decide(d, 20f, 3f, 30L, false, 8f))
        assertEquals("без порога не регулирует", ScrubGesture.Decision.PENDING, ScrubGesture.decide(d, 4f, 0f, 5000L, false, 8f))
        assertEquals(ScrubGesture.Mode.HOLD, ScrubGesture.modeFor(true))
        assertEquals(ScrubGesture.Mode.DIRECT, ScrubGesture.modeFor(false))
    }

    @Test fun tapThirdsStepValue() {
        assertEquals(-1, ScrubGesture.tapStep(5f, 100f))
        assertEquals(0, ScrubGesture.tapStep(50f, 100f))
        assertEquals(1, ScrubGesture.tapStep(95f, 100f))
        assertEquals(0, ScrubGesture.tapStep(10f, 0f))
        // размер (лог): шаг заметен и на 1 px, и на 1000 px; на краях стоит
        assertEquals(2f, ScrubGesture.stepValue(1f, 1, 1f, 1500f, true), 0.001f)
        assertEquals(1100f, ScrubGesture.stepValue(1000f, 1, 1f, 1500f, true), 0.01f)
        assertEquals(1f, ScrubGesture.stepValue(1f, -1, 1f, 1500f, true), 0f)
        assertEquals(1500f, ScrubGesture.stepValue(1500f, 1, 1f, 1500f, true), 0f)
        // прозрачность 1..100: по 1 %
        assertEquals(51f, ScrubGesture.stepValue(50f, 1, 1f, 100f, false), 0.001f)
        assertEquals(1f, ScrubGesture.stepValue(1f, -1, 1f, 100f, false), 0.001f)
    }

    @Test fun dragValueIsBoundedAndRelative() {
        assertEquals(60f, ScrubGesture.dragValue(50f, 100f, 1f, 100f, false, 0.1f), 0.001f)
        assertEquals(100f, ScrubGesture.dragValue(50f, 1e6f, 1f, 100f, false, 0.1f), 0f)
        assertEquals(1f, ScrubGesture.dragValue(50f, -1e6f, 1f, 100f, false, 0.1f), 0f)
        assertEquals(50f, ScrubGesture.dragValue(50f, Float.NaN, 1f, 100f, false, 0.1f), 0f)
        val bigger = ScrubGesture.dragValue(24f, 100f, 1f, 1500f, true, 1f)
        assertTrue(bigger > 24f && bigger < 1500f)
        assertEquals(1500f, ScrubGesture.dragValue(24f, 1e5f, 1f, 1500f, true, 1f), 0f)
    }

    // ---- 5. уроки ----

    @Test fun narrowPhoneStacksTutorial() {
        val p = TutorialLayout.plan(360f, 780f, false)
        assertTrue(p.stacked); assertTrue(p.bottomAction)
        assertEquals(171.6f, p.diagramHeightDp, 0.01f)
        assertEquals(328f, p.textWidthDp, 0.01f)
        assertFalse(TutorialLayout.plateStacked(p.textWidthDp))
        val tiny = TutorialLayout.plan(280f, 400f, true)
        assertEquals(TutorialLayout.MIN_DIAGRAM_DP, tiny.diagramHeightDp, 0f)
        assertTrue(TutorialLayout.plateStacked(tiny.textWidthDp))
        assertEquals(TutorialLayout.MAX_DIAGRAM_DP, TutorialLayout.plan(500f, 2000f, false).diagramHeightDp, 0f)
    }

    @Test fun wideTutorialMirrorsForLeftHand() {
        val r = TutorialLayout.plan(1200f, 800f, false)
        assertFalse(r.stacked); assertFalse(r.diagramRight); assertFalse(r.bottomAction)
        assertTrue(TutorialLayout.plan(1200f, 800f, true).diagramRight)
        assertTrue("телефон в ландшафте - кнопки внизу", TutorialLayout.plan(800f, 360f, false).bottomAction)
        assertTrue(r.textWidthDp > 700f)
        val bad = TutorialLayout.plan(Float.NaN, Float.NaN, false)
        assertTrue(bad.stacked); assertEquals(0f, bad.textWidthDp, 0f)
    }

    // ---- аудит: исправления ----

    @Test fun cursorRadiusNeverNaN() {
        assertEquals(2f, EditorInputPolicy.cursorRadius(Float.NaN, 100f, 100), 0f)
        assertEquals(2f, EditorInputPolicy.cursorRadius(20f, Float.POSITIVE_INFINITY, 100), 0f)
        assertEquals(10f, EditorInputPolicy.cursorRadius(20f, 100f, 100), 0.001f)
    }

    @Test fun bracketKeysRecoverFromBrokenSize() {
        assertEquals(24f, Hotkeys.resize(Float.NaN, true), 0f)
        assertEquals(24f, Hotkeys.resize(Float.POSITIVE_INFINITY, false), 0f)
        var s = 1f
        repeat(100) { s = Hotkeys.resize(s, true) }
        assertEquals(1500f, s, 0f)
        repeat(100) { s = Hotkeys.resize(s, false) }
        assertEquals(1f, s, 0f)
    }
}
