package com.vibetoon

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.TextBidi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 39: RTL-текст, вид слоёв «Список» и обмен кистями/палитрами подключены к рендеру, VM, интерфейсу, копии и переводам. */
class Sprint39WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private val vm by lazy { src("src/main/java/com/vibetoon/ui/MainViewModel.kt") }
    private val shell by lazy { src("src/main/java/com/vibetoon/ui/shell/EditorShell.kt") }
    private val inspector by lazy { src("src/main/java/com/vibetoon/ui/inspector/InspectorPanel.kt") }
    private val picker by lazy { src("src/main/java/com/vibetoon/ui/inspector/ColorPicker.kt") }
    private val renderer by lazy { src("src/main/java/com/vibetoon/data/TextRenderer.kt") }
    private val theme by lazy { src("src/main/java/com/vibetoon/ui/theme/AppTheme.kt") }
    private val json by lazy { src("src/main/java/com/vibetoon/domain/project/ProjectJson.kt") }
    private val en by lazy { src("src/main/res/values-en/strings.xml") }

    @Test fun rtlTextReachesRendererWithAndWithoutEffects() {
        // обычный текст: направление абзацев StaticLayout и физическое выравнивание через AlignmentSpan
        assertTrue(renderer.contains(".setTextDirection(heuristic)"))
        assertTrue(renderer.contains("android.text.TextDirectionHeuristics.RTL"))
        assertTrue(renderer.contains("android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR"))
        assertTrue(renderer.contains("android.text.style.AlignmentSpan.Standard(a)"))
        // текстовые эффекты: визуальный порядок букв, выравнивание строки, связанные арабские буквы - словом
        assertTrue(renderer.contains("TextBidi.visualUnits(line, rtl)"))
        assertTrue(renderer.contains("TextBidi.lineStartX(align, layoutW, lineW)"))
        assertTrue(renderer.contains("canvas.drawTextRun(s, 0, s.length, 0, s.length, x, 0f, true, p)"))
        assertTrue(renderer.contains("letterSpacing, direction)"))
        // все вызовы рендера текста передают направление (холст, кадры эффектов, экспорт)
        assertEquals(2, Regex("m\\.lineSpacing, m\\.letterSpacing, m\\.textDir\\)").findAll(vm).count())
        assertTrue(vm.contains("tm.lineSpacing, tm.letterSpacing, tm.textDir).bitmap"))
        assertTrue(vm.contains("o.letterSpacing != m.letterSpacing || o.textDir != m.textDir"))
        assertTrue(vm.contains("fun setTextDirection(d: Int)"))
        assertTrue(vm.contains("TextBidi.alignOnDirectionChange(it.textAlign, wasRtl, nowRtl)"))
    }

    @Test fun inspectorHasDirectionAndRtlCursor() {
        assertTrue(inspector.contains("vm.setTextDirection(d)"))
        assertTrue(inspector.contains("TextBidi.RTL to \"Справа налево\""))
        assertTrue(inspector.contains("androidx.compose.ui.text.style.TextDirection.Rtl"))
        assertTrue(inspector.contains("androidx.compose.ui.text.style.TextDirection.Content"))
    }

    @Test fun directionIsSavedOnlyWhenSet() {
        assertTrue(json.contains("if (m.textDir != 0) it[\"tdir\"] = m.textDir"))
        assertTrue(json.contains("textDir = TextBidi.coerce(o.int(\"tdir\", 0))"))
        val m = MediaItem(1, MediaKind.TEXT, "T", "", text = "שלום", textDir = TextBidi.RTL)
        assertEquals(TextBidi.RTL, m.textDir)
        assertEquals(TextBidi.AUTO, MediaItem(2, MediaKind.TEXT, "T", "").textDir)
    }

    @Test fun layersListModeIsWired() {
        assertTrue(shell.contains("val listMode by vm.layersList.collectAsStateWithLifecycle()"))
        assertTrue(shell.contains("vm.setLayersListMode(true)") && shell.contains("vm.setLayersListMode(false)"))
        assertTrue(shell.contains("if (listMode) LayerListBody("))
        assertTrue(shell.contains("com.vibetoon.domain.model.LayerListView.apply("))
        assertTrue(shell.contains("vm.setAllGroupsCollapsed(true)"))
        assertTrue(shell.contains("vm.selectBoneRow(e.id)"))
        assertTrue(shell.contains("vm.setMaskEnabled(e.id, lp.maskOff)") && shell.contains("vm.setMaskInvert(e.id, !lp.maskInvert)"))
        assertTrue(shell.contains("vm.moveLayerStep(e.id, up = true)"))
        // одно контекстное меню для обоих видов
        assertEquals(2, Regex("LayerContextMenu\\(vm, l, menuFor == ").findAll(shell).count())
        assertTrue(vm.contains("putString(com.vibetoon.domain.model.LayerListView.PREF_KEY"))
        assertTrue(vm.contains("fun treeRowsExpanded()"))
        assertTrue(vm.contains("StackOrder.place(layers, stackOrder, id, target, above = up)"))
    }

    @Test fun brushAndPaletteExchangeIsWired() {
        assertTrue(vm.contains("fun importBrushesFrom(uri: Uri)") && vm.contains("fun exportBrushesTo(uri: Uri)"))
        assertTrue(vm.contains("BrushExchange.decode(text, cur.map { it.id }.toSet())"))
        assertTrue(vm.contains("for (pr in BrushPresets.all() + _customBrushes.value)"))
        assertTrue(vm.contains("putString(com.vibetoon.domain.paint.BrushExchange.PREF_KEY"))
        assertTrue(shell.contains("vm.importBrushesFrom(uri)") && shell.contains("vm.exportBrushesTo(uri)"))
        assertTrue(shell.contains("vm.saveCurrentBrush()") && shell.contains("vm.deleteCustomBrush(pr.id)"))
        assertTrue(shell.contains("val cur = vm.presetById(p.presetId)"))
        assertTrue(picker.contains("PaletteExchange.decode(text,"))
        assertTrue(picker.contains("putString(PaletteExchange.PREF_KEY, PaletteExchange.encode(list))"))
        assertTrue(picker.contains("palOut.launch(\"vibetoon-palettes.json\")"))
        // резервная копия: «vt_app» (свои кисти, палитры, вид слоёв) + раздел окна цвета «vt_colors»
        assertTrue(theme.contains("SettingsBackup.encode(p.all, colors = colorPrefs.all)"))
        assertTrue(theme.contains("SettingsBackup.decodeColors(text)"))
    }

    @Test fun newStringsAreTranslated() {
        for (s in listOf("Right to left", "Thumbnails", "Search layers", "Bones and paths", "Import brushes…", "Export palettes…",
            "Brushes imported: {}", "This is a palette file: import it in the color window", "Found: {} of {}"))
            assertTrue(s, en.contains(s) || en.contains(s.replace("'", "\\'")))
    }
}
