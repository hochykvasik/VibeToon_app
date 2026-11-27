package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 49: связка UI/VM - клавиатура не сжимает редактор, копирование/вставка и свои кривые, кисть сетки на кости,
 * точечный выбор для привязки, очистка кэшей при смене проекта. Проверка по исходникам.
 */
class Sprint49WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val shell by lazy { kt("ui/shell/EditorShell.kt") }

    private fun body(text: String, start: String): String {
        val s = text.substring(text.indexOf(start))
        return s.substring(0, s.indexOf("\n    }\n").let { if (it < 0) s.length else it })
    }

    @Test fun editorRootIgnoresKeyboard() {
        // корень редактора отступает от системных панелей и выреза, но не от клавиатуры
        assertTrue(shell.contains("windowInsetsPadding(WindowInsets.editorSafe.only(WindowInsetsSides.Vertical))"))
        assertFalse(shell.contains("windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))"))
        val insets = kt("ui/shell/KeyboardInsets.kt")
        assertTrue(insets.contains("WindowInsets.systemBars.union(WindowInsets.displayCutout)"))
        assertTrue(insets.contains("KeyboardLift.lift(focused, ime, view.height - bottom)"))
        assertTrue(insets.contains("KeyboardLift.panelInset(ime, view.height - bottom)"))
        // таймлайн поднимается над клавиатурой, инспектор получает отступ - холст не перестраивается
        assertTrue(shell.contains(".liftAboveKeyboard()"))
        assertTrue(shell.contains(".keyboardInsetPanel()"))
        val manifest = src("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""))
        assertTrue(kt("MainActivity.kt").contains("setDecorFitsSystemWindows(window, false)"))
    }

    @Test fun curveCopyPasteIsOneUndoStep() {
        val paste = body(vm, "fun pasteCurveToSelection()")
        assertTrue(paste.contains("checkpoint(\"curve-paste\", force = true)"))
        assertTrue(paste.indexOf("checkpoint(\"curve-paste\"") < paste.indexOf("SelectedKeyCurves.apply("))
        assertTrue(paste.contains("applyTimeKeys(time, null)"))
        assertTrue(paste.contains("lockedNotice()"))
        assertTrue(vm.contains("fun copyCurve(curve: com.vibetoon.domain.anim.Curve)"))
        val timeline = kt("ui/timeline/TimelinePanel.kt")
        assertTrue(timeline.contains("vm::copySelectedCurve"))
        assertTrue(timeline.contains("vm::pasteCurveToSelection"))
    }

    @Test fun curveEditorHasClipboardAndUserCurves() {
        val ed = kt("ui/timeline/CurveEditor.kt")
        assertTrue(ed.contains("onCopy(curve)"))
        assertTrue(ed.contains("CurveClipboard.valid(clipboard)"))
        assertTrue(ed.contains("\"Мои кривые\""))
        assertTrue(ed.contains("SaveCurveDialog("))
        assertTrue(ed.contains("onDeleteUser?.invoke(name)"))
        val screen = kt("ui/MainScreen.kt")
        assertTrue(screen.contains("onCopy = vm::copyCurve"))
        assertTrue(screen.contains("UserCurves.save(st.userCurves, name, c)"))
        assertTrue(screen.contains("UserCurves.delete(st.userCurves, name)"))
        // свои кривые - в настройках приложения (vt_app): общие для проектов, в резервной копии, переживают обновление
        val theme = kt("ui/theme/AppTheme.kt")
        assertTrue(theme.contains("UserCurves.PREF, com.vibetoon.domain.anim.UserCurves.encode(list)).commit()"))
        assertTrue(body(theme, "private fun reload()").contains("userCurves = com.vibetoon.domain.anim.UserCurves.decode("))
    }

    @Test fun meshBrushOnSkinnedLayerMapsFingerBack() {
        val scene = kt("domain/render/SceneBuilder.kt")
        assertTrue(scene.contains("o.skinAffines = skinAffines"))
        assertTrue(scene.contains("if (skinAff.isNotEmpty()) sd.skinAffines = skinAff"))
        assertTrue(body(vm, "private fun meshMove(").contains("SkinEdit.unskin(skinAffinesOf(t, m), k, u0, v0)"))
        val smudge = body(vm, "private fun smudgeMove(")
        assertTrue(smudge.contains("val aff = skinAffinesOf(t, m)"))
        assertTrue(smudge.contains("val (ru, rv) = back(ru0, rv0)"))
        assertTrue(body(vm, "private fun cutMove(").contains("hitMeshOffsets(t, m)"))
        assertTrue(body(vm, "private fun meshAddAt(").contains("hitMeshOffsets(t, m)"))
    }

    @Test fun bonePointSelection() {
        assertTrue(body(vm, "private fun boneTap(").contains("BoneSelectMode.POINT) { pointPick(sx, sy); return }"))
        val down = body(vm, "fun boneDown(")
        assertTrue(down.contains("BoneSelectMode.POINT) { pointDownAt = SystemClock.uptimeMillis(); return false }"))
        val pick = body(vm, "private fun pointPick(")
        assertTrue(pick.contains("BonePointPick.eligible(hit, GHOST_BASE, ::isLockedId, bindableLayerIds() - symbolContextIds())"))
        assertTrue(pick.contains("BonePointPick.apply(_lassoLayers.value, id, held)"))
        assertTrue(shell.contains("vm.setBoneSelectMode(com.vibetoon.domain.input.BoneSelectMode.POINT)"))
        assertTrue(shell.contains("\"Точечный выбор\""))
    }

    @Test fun resetEditorDropsPerLayerCaches() {
        val reset = body(vm, "private fun resetEditor()")
        for (s in listOf("contentBox.clear()", "ghostShown.clear()", "cellShown.clear()", "textFxKeys.clear()", "pauseCapture.clear()",
                "_lassoLayers.value = emptySet()", "brushLive = null", "cutRemoved.clear()"))
            assertTrue(s, reset.contains(s))
        // буфер кривой намеренно живёт между проектами
        assertFalse(reset.contains("_curveClipboard"))
    }
}
