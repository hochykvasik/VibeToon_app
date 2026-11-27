package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 40: наклон пера и векторные кисти, полный Bidi в эффектах, перетаскивание в «Списке» и обновление после импорта копии. */
class Sprint40WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private val vm by lazy { src("src/main/java/com/vibetoon/ui/MainViewModel.kt") }
    private val screen by lazy { src("src/main/java/com/vibetoon/ui/MainScreen.kt") }
    private val shell by lazy { src("src/main/java/com/vibetoon/ui/shell/EditorShell.kt") }
    private val stylus by lazy { src("src/main/java/com/vibetoon/ui/shell/StylusSettings.kt") }
    private val theme by lazy { src("src/main/java/com/vibetoon/ui/theme/AppTheme.kt") }
    private val raster by lazy { src("src/main/java/com/vibetoon/data/VectorRaster.kt") }
    private val renderer by lazy { src("src/main/java/com/vibetoon/data/TextRenderer.kt") }
    private val json by lazy { src("src/main/java/com/vibetoon/domain/project/ProjectJson.kt") }
    private val bidi by lazy { src("src/main/java/com/vibetoon/domain/project/TextBidi.kt") }
    private val en by lazy { src("src/main/res/values-en/strings.xml") }
    private val pairs by lazy { (File("../tools/i18n/pairs2.py").takeIf { it.isFile } ?: File("tools/i18n/pairs2.py")).readText() }

    @Test fun tiltReachesTheBrush() {
        // холст передаёт наклон и направление пера в VM перед началом штриха и на каждом движении
        assertTrue(screen.contains("vm.paintTilt(stylusTracker.tiltRadians, stylusTracker.orientationRadians, first = true)"))
        assertTrue(screen.contains("vm.paintTilt(stylusTracker.tiltRadians, stylusTracker.orientationRadians)"))
        // VM читает режим, отдаёт множители штамповщику, плоское перо сплющивает штамп
        assertTrue(vm.contains("fun paintTilt(tiltRadians: Float, orientationRadians: Float, first: Boolean = false)"))
        assertTrue(vm.contains("com.vibetoon.domain.input.TiltMath.PREF_MODE"))
        assertTrue(vm.contains("st.setTilt("))
        assertTrue(vm.contains("val params = tiltParams(paintParams())"))
        assertTrue(vm.contains("applyTilt(st)"))
        // палец и мышь - без изменений
        assertTrue(vm.contains("private fun tiltActive(): Boolean = strokeStylus && tiltSettings.active"))
    }

    @Test fun tiltSettingsSavedAndBackedUp() {
        assertTrue(theme.contains(".putInt(\"stylus_tilt\", stylusTilt).putInt(\"stylus_tilt_strength\""))
        assertTrue(theme.contains("init { if (!p.contains(\"stylus_tilt\")) saveStylus() }"))
        assertTrue(theme.contains("stylusTilt = com.vibetoon.domain.input.TiltMath.Mode.of(p.getInt(\"stylus_tilt\", 0)).id"))
        assertTrue(stylus.contains("TiltMath.Mode.values().forEach"))
        assertTrue(stylus.contains("TiltMath.describe("))
        assertFalse("наклон больше не «только показывается»", stylus.contains("пока только показывается"))
    }

    @Test fun vectorBrushesDrawSaveAndUndo() {
        assertTrue(vm.contains("com.vibetoon.domain.vector.VectorBrush.stroke(_vecBrush.value, vecPts.toList(), vecPressures.toList()"))
        assertTrue(vm.contains("vecPressures += pressure * tiltSizeFactor()"))
        assertTrue(vm.contains("if (vecLayer >= 0) { vectorMove(sx, sy, pressure); return }"))
        // Undo/Redo: штрих добавляется через setVectors -> checkpoint("vec")
        assertTrue(vm.contains("setVectors(id, com.vibetoon.domain.vector.VectorMath.withShape(m.vectors, shape))"))
        assertTrue(vm.contains("checkpoint(\"vec\", force = true)"))
        assertTrue(shell.contains("vm.setVecBrush(style)"))
        assertTrue(raster.contains("VectorBrush.hasWidths(s)) brushStroke(cv, s, fillPaint, strokePaint)"))
        assertTrue(json.contains("mm[\"vw\"]") && json.contains("mm[\"vb\"]"))
    }

    @Test fun fullBidiInTextEffects() {
        assertTrue(bidi.contains("fun levels(line: CharSequence, rtlBase: Boolean): IntArray = UnicodeBidi.levels(line, rtlBase)"))
        assertTrue(renderer.contains("TextBidi.isControlUnit(line, u.range)"))
        assertTrue(renderer.contains("TextBidi.visualUnits(line, rtl)"))
    }

    @Test fun listViewDragAndImportRefresh() {
        assertTrue(shell.contains("com.vibetoon.domain.model.LayerListDrag.dropTarget(es, es.map { if (it.bone) boneRowPx else layerRowPx }, start, listDragDy, e.id)"))
        assertTrue(shell.contains("if (lifted && target != null) vm.dropLayer(e.id, target)"))
        assertTrue(shell.contains("LayerListDrag.canDrag(e, searching, multi)"))
        // стрелки «Выше»/«Ниже» остаются рядом с ручкой
        assertTrue(shell.contains("vm.moveLayerStep(e.id, up = true)"))
        // «Импорт копии» настроек: VM слушает vt_app и сразу меняет вид слоёв
        assertTrue(vm.contains("appPrefs().registerOnSharedPreferenceChangeListener(appPrefsListener)"))
        assertTrue(vm.contains("appPrefs().unregisterOnSharedPreferenceChangeListener(appPrefsListener)"))
        assertTrue(vm.contains("if (_layersList.value != list) { _layersList.value = list; rebuildRows() }"))
    }

    @Test fun translationsPresent() {
        for (p in listOf("Наклон пера|Pen tilt", "Плоское перо|Flat nib", "Штриховка|Shading", "Сила наклона: {} %|Tilt strength: {} %",
            "Наклон пера {}° → толщина ×{}|Pen tilt {}° → thickness ×{}", "Ровная|Uniform", "По нажиму|By pressure", "Тушь|Ink",
            "Каллиграфия|Calligraphy", "Перетащить слой|Drag layer")) assertTrue(p, pairs.contains(p))
        assertFalse(pairs.contains("Наклон пера пока только показывается"))
        assertTrue(en.contains("Flat nib") && en.contains("Calligraphy") && en.contains("Drag layer"))
    }
}
