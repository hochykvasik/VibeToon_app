package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 44: проводка кривой одного ключа и аудита пера в Compose/ViewModel/настройках (проверка по исходникам). */
class Sprint44WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val screen by lazy { kt("ui/MainScreen.kt") }
    private val pairs by lazy { (File("../tools/i18n/pairs2.py").takeIf { it.isFile } ?: File("tools/i18n/pairs2.py")).readText() }

    @Test fun singleKeyCurveIsReachableFromTimelineAndProperties() {
        val timeline = kt("ui/timeline/TimelinePanel.kt")
        assertTrue(timeline.contains("if (keySel.size == 1) \"Кривая ключа\" else \"Общая кривая\""))
        // кнопка кривой - первая после «Снять выделение», без прокрутки ряда
        val row = timeline.indexOf("val keyBtn = PaddingValues(horizontal = 10.dp, vertical = 2.dp)")
        assertTrue(row > 0 && timeline.indexOf("vm::openSelectedCurveEditor", row) < timeline.indexOf("vm::selectLayerKeys", row))
        assertTrue(kt("ui/inspector/InspectorPanel.kt").contains("if (s.count == 1) com.vibetoon.ui.timeline.KeyCurveSection(vm, s.id)"))
        val ui = kt("ui/timeline/KeyCurveUi.kt")
        assertTrue(ui.contains("vm.setSingleKeyEasing(type)") && ui.contains("vm::openSingleKeyCurve") && ui.contains("vm.singleKeyInfo()?.takeIf { it.id == id }"))
    }

    @Test fun viewModelTargetsOnlyOneKey() {
        assertTrue(vm.contains("if (_keySel.value.size == 1 && singleKey() != null) { openSingleKeyCurve(); return }"))
        assertTrue(vm.contains("selectedCurveTargets = com.vibetoon.domain.anim.SingleKeyCurve.targets(id, f, _animation.value.tracks, isBoneId(id))"))
        assertTrue(vm.contains("com.vibetoon.domain.anim.SingleKeyCurve.withEasing(_animation.value, id, f, isBoneId(id), type)"))
        // кость: только её ключ, а не все выделенные узлы на кадре
        val bone = vm.substring(vm.indexOf("fun openBoneKeyCurve()"))
        assertTrue(bone.substring(0, bone.indexOf("\n    }")).contains("SingleKeyCurve.targets(b.id, f, _animation.value.tracks, true)"))
        assertFalse(bone.substring(0, bone.indexOf("\n    }")).contains("openCurveEditor(true)"))
        assertTrue(screen.contains("title = curveTitle, note = curveNote"))
        assertTrue(kt("ui/timeline/CurveEditor.kt").contains("title: String = \"Кривая плавности\", note: String? = null"))
    }

    @Test fun canvasHandlesBarrelCancelAndPalm() {
        assertTrue(screen.contains("vm.stylusStart(down.type == PointerType.Eraser || stylusTracker.eraserTip, stylusTracker.barrelPressed, down.position.x, down.position.y)"))
        assertTrue("штрих отменяется при ACTION_CANCEL / FLAG_CANCELED",
            screen.contains("stylusTracker.canceled(c.uptimeMillis)) == com.vibetoon.domain.input.StylusSample.Release.CANCEL"))
        assertTrue("прерванный жест не оставляет перо и ластик «залипшими»", screen.contains("vm.paintCancel(); vm.paintStylus(false); vm.stylusTipEnd(); throw e"))
        assertTrue("растровый инструмент тоже защищён от ладони", screen.contains("if (vm.rasterToolActive() && down.type == PointerType.Touch && vm.paintPalmBlocked())"))
        assertTrue("защита от ладони - от любого контакта пера", screen.contains("if (pens.any { it.pressed || it.previousPressed }) vm.paintStylus(pens.any { it.pressed })"))
        assertTrue("тип штриха - от указателя, начавшего штрих", screen.contains("vm.paintDown(down.position.x, down.position.y, lastP, stylus)"))
        assertTrue(screen.contains("EditorInputPolicy.hudAtStart(st.leftHanded)"))
    }

    @Test fun trackerReadsSensorsButtonsAndResets() {
        val t = kt("ui/StylusTracker.kt")
        assertTrue(t.contains("getMotionRange(MotionEvent.AXIS_TILT"))
        assertTrue(t.contains("StylusButtons.pressed(ev.buttonState, true)"))
        assertTrue(t.contains("FLAG_CANCELED = 0x20"))
        assertTrue(t.contains("MotionEvent.ACTION_HOVER_EXIT) { reset(); return }"))
        assertTrue(vm.contains("TiltMath.angle(ts, tiltOrientation, Math.toRadians(camera.rotDeg.toDouble()).toFloat(), tiltNorm, camera.flipX, camera.flipY)"))
        assertTrue(vm.contains("EditorInputPolicy.palmBlocked(stylusDown, stylusLast, SystemClock.uptimeMillis())"))
    }

    @Test fun barrelSettingIsPersistedResetAndBackedUp() {
        val st = kt("ui/theme/AppTheme.kt")
        assertTrue(st.contains(".putInt(com.vibetoon.domain.input.StylusButtons.PREF, stylusButton)"))
        assertTrue(st.contains("stylusButton = com.vibetoon.domain.input.StylusButtons.DEFAULT.id; saveStylus()"))
        assertTrue(st.contains("init { if (!p.contains(com.vibetoon.domain.input.StylusButtons.PREF)) saveStylus() }"))
        val reload = st.substring(st.indexOf("private fun reload()"))
        assertTrue(reload.contains("stylusButton = "))
        val ui = kt("ui/shell/StylusSettings.kt")
        assertTrue(ui.contains("StylusButtons.Action.entries.forEach") && ui.contains("Text(\"Кнопка пера\""))
    }

    @Test fun newStringsHaveEnglishPairs() {
        for (line in listOf("Кривая ключа|Key curve", "Кривая ключа: кадр {}|Key curve: frame {}", "Кнопка пера|Pen button",
                "Ластик, пока зажата|Eraser while held", "Цвет взят кнопкой пера|Color picked with the pen button",
                "Только ключ кадра {}: остальные ключи не меняются|Only the key on frame {}: other keys stay unchanged"))
            assertTrue(line, pairs.contains("\n$line\n"))
    }
}
