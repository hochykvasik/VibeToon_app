package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.Curve
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.SelectedKeyCurves
import com.vibetoon.domain.anim.SingleKeyCurve
import com.vibetoon.domain.anim.WarpMath
import com.vibetoon.domain.input.EditorInputPolicy
import com.vibetoon.domain.input.StylusButtons
import com.vibetoon.domain.input.StylusSample
import com.vibetoon.domain.input.StylusTouchFilter
import com.vibetoon.domain.input.TiltMath
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 44: кривая одного ключа и аудит пера (кнопка на корпусе, наклон, отмена касания, ладонь, индикатор). */
class Sprint44Test {
    private val noBones: (Int) -> Boolean = { false }
    private val custom = listOf(0.7f, 0f, 0.3f, 1f)

    /** Слой 2: ключи 0, 12, 24; на кадре 12 есть и ключ точки Warp. Слой 3: ключ на 12. */
    private fun anim(): Animation {
        var a = Animation()
        for (f in listOf(0, 12, 24)) a = a.withKey(2, f, LayerTransform(dx = f.toFloat()))
        a = a.withKey(WarpMath.pointId(2, 3), 12, LayerTransform(dx = 1f))
        a = a.withKey(WarpMath.pointId(2, 3), 24, LayerTransform(dx = 2f))
        a = a.withKey(3, 12, LayerTransform(dy = 5f))
        return a
    }

    // ---------------------------------------------------------------- кривая одного ключа

    @Test fun oneSelectedDiamondIsTheKey() {
        val a = anim()
        assertEquals(2 to 12, SingleKeyCurve.pick(setOf(2 to 12), null, 0, a.tracks, noBones))
        // ромб другого слоя тоже однозначен (таймлайн), даже если выбран слой 2
        assertEquals(3 to 12, SingleKeyCurve.pick(setOf(3 to 12), 2, 0, a.tracks, noBones))
        // ромб без ключей анимации (например, ключ времени символа) - не «Кривая ключа»
        assertNull(SingleKeyCurve.pick(setOf(2 to 5), 2, 5, a.tracks, noBones))
    }

    @Test fun propertiesUseSelectedLayerKeyOnPlayhead() {
        val a = anim()
        assertEquals(2 to 24, SingleKeyCurve.pick(emptySet(), 2, 24, a.tracks, noBones))
        assertNull("между ключами - ключа нет", SingleKeyCurve.pick(emptySet(), 2, 7, a.tracks, noBones))
        assertNull(SingleKeyCurve.pick(emptySet(), null, 12, a.tracks, noBones))
        // в выделении один ромб выбранного слоя и ромб другого - берётся ромб выбранного
        assertEquals(2 to 0, SingleKeyCurve.pick(setOf(2 to 0, 3 to 12), 2, 24, a.tracks, noBones))
        // несколько ромбов выбранного слоя - это «Общая кривая», не один ключ
        assertNull(SingleKeyCurve.pick(setOf(2 to 0, 2 to 12), 2, 12, a.tracks, noBones))
    }

    @Test fun curveChangesOnlyThatKey() {
        val a = anim()
        val b = SingleKeyCurve.withCurve(a, 2, 12, false, custom)
        assertEquals(custom, b.curveAt(2, 12))
        assertEquals("ромб = все дорожки слоя на этом кадре", custom, b.curveAt(WarpMath.pointId(2, 3), 12))
        assertNull("предыдущий ключ не тронут", b.curveAt(2, 0))
        assertNull("следующий ключ не тронут", b.curveAt(2, 24))
        assertNull(b.curveAt(WarpMath.pointId(2, 3), 24))
        assertNull("другой слой на том же кадре не тронут", b.curveAt(3, 12))
        // а «Общая кривая» по ключам слоя меняет все - разница именно в выборе целей
        val all = SelectedKeyCurves.apply(a, SelectedKeyCurves.targets(a, setOf(2 to 0, 2 to 12, 2 to 24)), custom)
        assertEquals(custom, all.curveAt(2, 0)); assertEquals(custom, all.curveAt(2, 24))
    }

    @Test fun easingPresetOnlyForThatKeyAndResetsCustomCurve() {
        val a = SingleKeyCurve.withCurve(anim(), 2, 12, false, custom)
        val b = SingleKeyCurve.withEasing(a, 2, 12, false, EasingType.EASE_IN_OUT)
        assertEquals(EasingType.EASE_IN_OUT, b.easingAt(2, 12))
        assertEquals(EasingType.EASE_IN_OUT, b.easingAt(WarpMath.pointId(2, 3), 12))
        assertNull(b.curveAt(2, 12))
        assertEquals(EasingType.LINEAR, b.easingAt(2, 0))
        assertEquals(EasingType.LINEAR, b.easingAt(2, 24))
        assertEquals(EasingType.LINEAR, b.easingAt(3, 12))
    }

    @Test fun infoReportsCustomAndMixedTracks() {
        val a = anim()
        val plain = SingleKeyCurve.info(2, 12, a.tracks, false)!!
        assertEquals(2, plain.tracks); assertFalse(plain.custom); assertFalse(plain.mixed)
        assertEquals(EasingType.LINEAR, plain.easing)
        val one = a.withCurve(WarpMath.pointId(2, 3), 12, custom)   // кривая только у дорожки Warp (старый проект)
        val mixed = SingleKeyCurve.info(2, 12, one.tracks, false)!!
        assertTrue(mixed.mixed); assertFalse("опора - дорожка трансформации", mixed.custom)
        val all = SingleKeyCurve.withCurve(one, 2, 12, false, custom)
        val fixed = SingleKeyCurve.info(2, 12, all.tracks, false)!!
        assertTrue(fixed.custom); assertFalse(fixed.mixed)
        assertNull(SingleKeyCurve.info(2, 5, a.tracks, false))
    }

    @Test fun ownTrackComesFirstForEditorStartCurve() {
        val a = anim().withCurve(2, 12, custom)
        val t = SingleKeyCurve.targets(2, 12, a.tracks, false)
        assertEquals(2 to 12, t.first())
        val c = SingleKeyCurve.curveOf(SingleKeyCurve.info(2, 12, a.tracks, false))
        assertEquals(Curve.parse(custom)!!.toList(), c.toList())
        assertEquals(Curve.of(EasingType.EASE_OUT).toList(),
            SingleKeyCurve.curveOf(SingleKeyCurve.info(2, 0, SingleKeyCurve.withEasing(a, 2, 0, false, EasingType.EASE_OUT).tracks, false)).toList())
        assertEquals(Curve.LINEAR.toList(), SingleKeyCurve.curveOf(null).toList())
    }

    @Test fun boneKeyUsesOnlyItsOwnTrack() {
        val bone = 200_001
        var a = anim().withKey(bone, 12, LayerTransform(rotation = 0.3f)).withKey(bone, 20, LayerTransform(rotation = 0.6f))
        a = a.withKey(200_002, 12, LayerTransform(rotation = 0.1f))   // вторая выбранная кость
        val isBone: (Int) -> Boolean = { it >= 200_000 }
        assertEquals(bone to 12, SingleKeyCurve.pick(emptySet(), bone, 12, a.tracks, isBone))
        val b = SingleKeyCurve.withCurve(a, bone, 12, true, custom)
        assertEquals(custom, b.curveAt(bone, 12))
        assertNull("другая кость не меняется", b.curveAt(200_002, 12))
        assertNull(b.curveAt(2, 12))
        assertNull(b.curveAt(bone, 20))
    }

    // ---------------------------------------------------------------- кнопка на корпусе пера

    @Test fun barrelButtonIsReadOnlyForPen() {
        assertTrue(StylusButtons.pressed(StylusButtons.BUTTON_STYLUS_PRIMARY, true))
        assertTrue(StylusButtons.pressed(StylusButtons.BUTTON_STYLUS_SECONDARY, true))
        assertTrue("Wacom: кнопка пера как BUTTON_SECONDARY", StylusButtons.pressed(StylusButtons.BUTTON_SECONDARY, true))
        assertFalse(StylusButtons.pressed(0, true))
        assertFalse(StylusButtons.pressed(0x01, true))   // BUTTON_PRIMARY - сам кончик, не кнопка
        assertFalse("правая кнопка мыши - не кнопка пера", StylusButtons.pressed(StylusButtons.BUTTON_SECONDARY, false))
    }

    @Test fun strokeStartDecision() {
        val e = StylusButtons.Action.ERASER; val p = StylusButtons.Action.EYEDROPPER; val off = StylusButtons.Action.OFF
        assertEquals(StylusButtons.Start.DRAW, StylusButtons.start(false, true, false, e))
        assertEquals(StylusButtons.Start.ERASE, StylusButtons.start(true, true, false, off))
        assertEquals("обратный конец выключен - рисует", StylusButtons.Start.DRAW, StylusButtons.start(true, false, false, e))
        assertEquals(StylusButtons.Start.ERASE, StylusButtons.start(false, true, true, e))
        assertEquals(StylusButtons.Start.PICK_COLOR, StylusButtons.start(false, true, true, p))
        assertEquals(StylusButtons.Start.DRAW, StylusButtons.start(false, true, true, off))
        assertEquals("обратный конец важнее кнопки", StylusButtons.Start.ERASE, StylusButtons.start(true, true, true, p))
        assertEquals(StylusButtons.Action.ERASER, StylusButtons.Action.of(99))
        assertEquals(StylusButtons.Action.EYEDROPPER, StylusButtons.Action.of(2))
        assertEquals(3, StylusButtons.Action.entries.map { it.id }.toSet().size)
    }

    // ---------------------------------------------------------------- наклон

    @Test fun verticalPenIsZeroNotMissing() {
        assertEquals(0f, StylusSample.tiltAxis(0f, true), 0f)
        assertTrue("датчика нет", StylusSample.tiltAxis(0f, false).isNaN())
        assertTrue("датчика нет, даже если пришло число", StylusSample.tiltAxis(0.4f, false).isNaN())
        assertTrue("устройство неизвестно: 0 = нет датчика, как раньше", StylusSample.tiltAxis(0f, null).isNaN())
        assertEquals(0.4f, StylusSample.tiltAxis(0.4f, null), 0f)
        assertTrue(StylusSample.tiltAxis(Float.NaN, true).isNaN())
        assertEquals(0, StylusSample.tiltDegrees(StylusSample.tiltAxis(0f, true)))
        assertNull(StylusSample.tiltDegrees(StylusSample.tiltAxis(0f, false)))
        assertEquals("Датчика наклона нет: кисть рисует без изменений", TiltMath.describe(TiltMath.settings(1, 60), StylusSample.tiltAxis(0f, false)))
        assertEquals("Наклон пера 0° → толщина ×1.0", TiltMath.describe(TiltMath.settings(1, 60), 0f))
    }

    @Test fun flatPenDoesNotRotateWhenUpright() {
        val s = TiltMath.settings(TiltMath.Mode.ANGLE.id, 100)
        assertTrue("вертикальное перо: поворот кисти не подменяется", TiltMath.angle(s, 1f, 0f, 0f).isNaN())
        assertTrue(TiltMath.angle(s, 1f, 0f, 0.01f).isNaN())
        assertFalse(TiltMath.angle(s, 1f, 0f, 0.5f).isNaN())
        assertFalse("без наклона по умолчанию - как раньше", TiltMath.angle(s, 1f).isNaN())
        assertEquals("Наклон пера 0° → перо почти вертикально: штамп не меняется", TiltMath.describe(s, 0f))
        assertEquals(1f, TiltMath.roundness(s, 0f, 1f), 0f)
    }

    @Test fun allFourTiltModesStayNeutralWithoutSensor() {
        val t = TiltMath.norm(StylusSample.tiltAxis(0f, false))
        for (m in TiltMath.Mode.entries) {
            val s = TiltMath.settings(m.id, 100)
            assertEquals(1f, TiltMath.sizeFactor(s, t), 0f)
            assertEquals(1f, TiltMath.flowFactor(s, t), 0f)
            assertEquals(0.8f, TiltMath.roundness(s, t, 0.8f), 0f)
        }
    }

    // ---------------------------------------------------------------- отмена касания системой

    @Test fun systemCancelIsNotAStroke() {
        assertEquals(StylusSample.Release.UP, StylusSample.release(false, 120, 112, false))
        assertEquals("ACTION_CANCEL в Compose: потреблённое «отпускание» без сдвига времени",
            StylusSample.Release.CANCEL, StylusSample.release(true, 112, 112, false))
        assertEquals("FLAG_CANCELED (ладонь, Android 13+)", StylusSample.Release.CANCEL, StylusSample.release(false, 120, 112, true))
        assertEquals("кто-то потребил обычное отпускание - всё равно подъём", StylusSample.Release.UP, StylusSample.release(true, 120, 112, false))
    }

    // ---------------------------------------------------------------- ладонь и индикатор

    @Test fun palmGuardMatchesTouchDetectionInterval() {
        assertTrue(EditorInputPolicy.palmBlocked(true, 0L, 10_000L))
        assertTrue(EditorInputPolicy.palmBlocked(false, 10_000L, 10_000L + StylusTouchFilter.GUARD_MS - 1))
        assertFalse(EditorInputPolicy.palmBlocked(false, 10_000L, 10_000L + StylusTouchFilter.GUARD_MS))
        assertFalse("перо ещё не касалось", EditorInputPolicy.palmBlocked(false, 0L, 500L))
        assertFalse(EditorInputPolicy.palmBlocked(false, 10_000L, 9_000L))
    }

    @Test fun pressureHudThrottlesAndSitsAwayFromHand() {
        assertTrue(EditorInputPolicy.hudChanged(null, null, 0.5f, 0.5f))
        assertFalse(EditorInputPolicy.hudChanged(0.5f, 0.5f, 0.502f, 0.503f))
        assertTrue(EditorInputPolicy.hudChanged(0.5f, 0.5f, 0.51f, 0.5f))
        assertTrue(EditorInputPolicy.hudChanged(0.5f, 0.5f, 0.5f, 0.49f))
        assertTrue("правша: слева сверху", EditorInputPolicy.hudAtStart(false))
        assertFalse("левша: справа сверху", EditorInputPolicy.hudAtStart(true))
    }
}
