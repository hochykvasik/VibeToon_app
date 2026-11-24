package com.vibetoon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 33: проводка направляющих и рамки трансформации в ViewModel/Compose, которую нельзя запустить в unit-тесте,
 * проверяется по исходникам: штрих идёт через DrawingGuides.Stroke с зеркальными DabStroker, рамка применяется через SelectionWarp.
 */
class Sprint33GuidesWiringTest {
    private fun src(path: String): String =
        listOf(File("src/main/java/com/vibetoon/$path"), File("app/src/main/java/com/vibetoon/$path")).first { it.exists() }.readText()

    @Test fun paintStrokeUsesGuidesAndMirrors() {
        val s = src("ui/MainViewModel.kt")
        assertTrue(s.contains("com.vibetoon.domain.paint.DrawingGuides.Stroke(guideState,"))
        assertTrue(s.contains("mirrorStrokers = List(gs.count - 1) { DabStroker(params) }"))
        assertTrue("зеркальные штрихи завершаются до запекания", s.contains("for (mirror in mirrorStrokers) sendDabs(mirror.end())"))
        assertTrue("линейка тоже повторяется симметрией", s.contains("DrawingGuides.mirrorCount(symmetry)"))
        assertTrue("ручки направляющих тянутся вместо мазка", s.contains("DrawingGuides.moveHandle(_guides.value, handle,"))
        assertTrue("направляющие переживают перезапуск", s.contains("putString(\"drawing_guides\""))
        assertTrue(s.contains("restoreGuides()"))
        assertTrue("при выравнивании фигур штрих не подменяется", s.contains("if (!guided && !_gradientMode.value"))
    }

    @Test fun selectionTransformCommitsThroughWarp() {
        val s = src("ui/MainViewModel.kt")
        assertTrue(s.contains("if (warp != null) com.vibetoon.domain.raster.SelectionWarp.prepare(src, lp.bytes, 8, lp.w, lp.h, s.mask, warp, budget)"))
        assertTrue(s.contains("moveSelectionExpanded(s, 0, 0, warp = b)"))
        assertTrue(s.contains("7 -> selBoxMove(sx, sy)"))
        assertTrue("сочетание выделений", s.contains("applyCombined(id, size[0], size[1], mask)") && s.contains("applyCombined(id, lp.w, lp.h, mask)"))
        assertTrue("новое выделение снимает рамку", Regex("private fun setSelection\\(s: Sel\\?\\) \\{\\s*cancelSelectionTransform\\(\\)").containsMatchIn(s))
    }

    @Test fun overlaysAndMenusAreWired() {
        val screen = src("ui/MainScreen.kt")
        assertTrue(screen.contains("DrawingGuideLayer(vm)")); assertTrue(screen.contains("SelectionTransformLayer(vm)"))
        val shell = src("ui/shell/EditorShell.kt")
        for (k in listOf("ISOMETRIC", "PERSPECTIVE_1", "PERSPECTIVE_2", "PERSPECTIVE_3", "ELLIPSE")) assertTrue(k, shell.contains("DrawingGuides.Kind.$k to"))
        for (k in listOf("VERTICAL", "HORIZONTAL", "BOTH", "RADIAL")) assertTrue(k, shell.contains("DrawingGuides.Symmetry.$k to"))
        for (k in listOf("FREE", "SKEW", "DISTORT")) assertTrue(k, shell.contains("TransformBox.Mode.$k to"))
        assertTrue(shell.contains("SelectionTransformDialog(onDismiss"))
        assertTrue(shell.contains("vm::selectAllPixels")); assertTrue(shell.contains("vm.growSelection(-2)"))
    }
}
