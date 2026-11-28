package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 62: проводка пяти UX-улучшений в интерфейс - текстом исходников. Поведение (геометрия кружка, решение жеста,
 * память цвета, состояния очистки, раскладка уроков) - Sprint62Test.
 */
class Sprint62WiringTest {
    private fun file(path: String): File = File(path).takeIf { it.exists() } ?: File("../$path").takeIf { it.exists() } ?: File("app/$path")
    private fun kt(path: String) = file("src/main/java/com/vibetoon/$path").readText()
    private val shell by lazy { kt("ui/shell/EditorShell.kt") }
    private val screen by lazy { kt("ui/MainScreen.kt") }
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val scrub by lazy { kt("ui/inspector/ScrubField.kt") }
    private val picker by lazy { kt("ui/inspector/ColorPicker.kt") }
    private val tutorials by lazy { kt("ui/projects/TutorialsScreen.kt") }

    private fun body(text: String, sig: String): String {
        val i = text.indexOf(sig)
        assertTrue("нет $sig", i >= 0)
        var depth = 0; var started = false; var j = text.indexOf('{', i)
        val start = j
        while (j < text.length) {
            val c = text[j]
            if (c == '{') { depth++; started = true } else if (c == '}') { depth--; if (started && depth == 0) break }
            j++
        }
        return text.substring(start, j + 1)
    }

    // ---- 1. кружок размера ----

    @Test fun sizeFieldDrivesPreview() {
        assertTrue(shell.contains("ScrubBox(\"Размер\", p.size, 1f, 1500f, { v -> vm.updatePaint { it.copy(size = v) } }, log = true,\n            onHold = vm::holdSizePreview, onStep = vm::pulseSizePreview)"))
        val hk = body(vm, "com.vibetoon.domain.HotkeyAction.BRUSH_SMALLER ->")
        assertTrue("клавиша [ показывает кружок", hk.contains("pulseSizePreview()"))
    }

    @Test fun previewUsesLayerAxesAndClearsOnRelease() {
        val axes = body(vm, "private fun sizePreviewAxes()")
        assertTrue(axes.contains("texQuad(it)") && axes.contains("toScreen(q[0], q[1])") && axes.contains("toScreen(q[6], q[7])"))
        assertTrue("без слоя - оси холста", axes.contains("toScreen(1f, 0f)"))
        val hold = body(vm, "fun holdSizePreview(on: Boolean)")
        assertTrue(hold.contains("_sizePreview.value = null"))
        val refresh = body(vm, "private fun refreshSizePreview()")
        assertTrue(refresh.contains("BrushSizePreview.visible(") && refresh.contains("BrushSizePreview.center(viewW, viewH)") && refresh.contains("p.roundness"))
        // касание холста и смена инструмента/проекта прячут кружок - следов не остаётся
        assertTrue(body(vm, "fun beginCanvasGesture()").contains("hideSizePreview()"))
        assertTrue(body(vm, "fun setPaintTool(t: PaintTool?)").contains("hideSizePreview()"))
        assertTrue(body(vm, "private fun resetEditor()").contains("hideSizePreview()"))
    }

    @Test fun previewLayerDrawsWithoutInput() {
        assertTrue(screen.contains("BrushSizePreviewLayer(vm)"))
        val layer = body(screen, "private fun BrushSizePreviewLayer(vm: MainViewModel)")
        assertTrue(layer.contains("vm.sizePreview.collectAsStateWithLifecycle()"))
        assertFalse("слой кружка не перехватывает перо и пальцы", layer.contains("pointerInput"))
    }

    // ---- 2. очистка ----

    @Test fun quickClearSitsNextToUndoRedo() {
        val redo = shell.indexOf("add { BarIcon(Icons.Default.Redo, \"Повторить\"")
        val clear = shell.indexOf("add { QuickClearButton(vm, playing) }")
        assertTrue(redo > 0 && clear > redo && clear - redo < 400)
        val btn = body(shell, "private fun QuickClearButton(")
        assertTrue("подтверждение перед очисткой", btn.contains("DropdownMenu(expanded = confirm") && btn.contains("onClick = { confirm = false; vm.quickClear() }"))
        assertTrue(btn.contains("QuickClear.enabled(st)"))
        assertFalse("нажатие само не чистит", btn.contains("{ vm.quickClear() }\n"))
    }

    @Test fun quickClearIsUndoable() {
        val qc = body(vm, "fun quickClear()")
        assertTrue(qc.contains("clearVectors()") && qc.contains("clearLayer(id,"))
        assertTrue(qc.contains("QuickClear.enabled(st)"))
        val cl = body(vm, "fun clearLayer(id: Int, done: String")
        assertTrue("одна запись истории", cl.contains("commitPaintOp(op)"))
        val st = body(vm, "fun quickClearState()")
        assertTrue(st.contains("isLockedId(id)") && st.contains("_playing.value") && st.contains("vectorCount(it)"))
    }

    // ---- 3. цвет ----

    @Test fun colorPickerUsesMemory() {
        assertTrue(picker.contains("ColorMemory.open(initial, ColorMemory.decode(prefs.getString(ColorMemory.PREF_KEY, null)))"))
        assertTrue(picker.contains("putString(ColorMemory.PREF_KEY, ColorMemory.encode(memo()))"))
        assertFalse("тап по кругу больше не ставит яркость 100 %", picker.contains("if (value < 0.05f) value = 1f"))
        assertTrue(picker.contains("ColorMemory.pickWheel(memo()"))
        assertTrue(picker.contains("{ setValue(it / 100f); push() }") && picker.contains("{ setValue(it); push() }"))
    }

    // ---- 4. поля «потяни» ----

    @Test fun scrubFieldsUseGestureDecision() {
        assertFalse(scrub.contains("detectHorizontalDragGestures"))
        val g = body(scrub, "internal fun Modifier.scrubGesture(")
        assertTrue(g.contains("ScrubGesture.decide(") && g.contains("withTimeoutOrNull(wait)") && g.contains("finally { onHold(false) }"))
        assertTrue("при регулировке строка не едет", g.contains("c.consume()"))
        val box = body(scrub, "fun ScrubBox(")
        assertTrue(box.contains("ScrubGesture.modeFor(com.vibetoon.ui.shell.LocalPhone.current)"))
        assertTrue(box.contains("ScrubGesture.tapStep(x, w)") && box.contains("ScrubGesture.stepValue("))
    }

    // ---- 5. уроки ----

    @Test fun tutorialUsesAdaptiveLayout() {
        val d = body(tutorials, "fun TutorialDetailScreen(")
        assertTrue(d.contains("TutorialLayout.plan(maxWidth.value, maxHeight.value, leftHanded)"))
        assertTrue(d.contains("if (plan.bottomAction && t.steps.isNotEmpty())"))
        assertTrue(d.contains("Шаг {} из {}") && d.contains("animateScrollToItem"))
        assertTrue("левша: схема справа", d.contains("if (plan.diagramRight) diagram()"))
        val step = body(tutorials, "private fun StepCard(")
        assertFalse("шаг без вложенной карточки", step.contains("Card("))
        val btn = body(tutorials, "private fun EditorButton(")
        assertTrue("подпись в две строки", btn.contains("maxLines = 2"))
        assertTrue(body(tutorials, "private fun ButtonPlate(").contains("if (stacked) Column("))
    }
}
