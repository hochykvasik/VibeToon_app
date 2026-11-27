package com.vibetoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 60: проводка исправления ANR при создании символа (ворота расчёта границ, безопасное создание с откатом)
 * и телефонной раскладки (поворот, портрет, левша) - текстом исходников. Поведение - в Sprint60Test / Sprint60LayoutTest.
 */
class Sprint60WiringTest {
    private fun file(path: String): File = File(path).takeIf { it.exists() } ?: File("../$path").takeIf { it.exists() } ?: File("app/$path")
    private fun src(path: String): String = file(path).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val shell by lazy { kt("ui/shell/EditorShell.kt") }
    private val manifest by lazy { src("src/main/AndroidManifest.xml") }

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

    @Test fun contentBoxRecomputeGoesThroughGate() {
        val sched = body(vm, "private fun contentBoxScheduled(id: Int)")
        assertTrue(sched.contains("if (!boxGate.begin(id)) return"))
        assertTrue(sched.contains("viewModelScope.launch(Dispatchers.Main)"))
        assertFalse("immediate запускал расчёт внутри перерисовки рамки - петля ANR", sched.contains("Main.immediate"))
        assertTrue(sched.contains("if (boxGate.end(id, cached || stale) && projectId != null) runCatching { updateSelectionFrame() }"))
        assertTrue(sched.contains("val stale = boxGate.takeStale(id)"))
        // кэш границ сбрасывается только вместе с воротами (dropBox) или для устаревшего расчёта
        assertEquals(2, Regex("""contentBox\.remove\(""").findAll(vm).count())
        assertTrue(vm.contains("private fun dropBox(id: Int) { contentBox.remove(id); boxGate.forget(id) }"))
        assertTrue(vm.contains("contentBox.clear(); boxGate.clear();"))
    }

    @Test fun createSymbolIsSafeAndRollsBack() {
        val create = body(vm, "fun createSymbol(fromId: Int? = null, asFrames: Boolean = false)")
        assertTrue(create.contains("createSymbolChecked(fromId, asFrames)"))
        assertTrue(create.contains("catch (t: kotlinx.coroutines.CancellationException) { throw t }"))
        assertTrue(create.contains("if (undoStack.size > undoBefore) runCatching { undo(); redoStack.clear(); updateHistoryFlags() }"))
        assertTrue(create.contains("UiState.Error("))
        val checked = body(vm, "private fun createSymbolChecked(fromId: Int?, asFrames: Boolean)")
        assertTrue(checked.contains("it < BONE_ID_BASE && it in known"))
        assertTrue(checked.contains("if (sel.any { isLockedId(it) }) { lockedNotice(); return }"))
        assertTrue(checked.contains("SymbolMath.frameLeaves(sel"))
    }

    @Test fun activityRotatesWithoutRecreation() {
        assertFalse("портрет был запрещён", manifest.contains("sensorLandscape"))
        assertTrue(manifest.contains("android:screenOrientation=\"fullUser\""))
        for (c in listOf("orientation", "screenSize", "smallestScreenSize", "screenLayout")) assertTrue(c, manifest.contains(c))
    }

    @Test fun shellUsesWindowClassLayout() {
        assertTrue(shell.contains("com.vibetoon.domain.ui.EditorLayout.of(maxWidth.value, maxHeight.value, leftHanded)"))
        assertFalse("старый порог по ширине", shell.contains("PHONE_MAX"))
        assertTrue(shell.contains("CompositionLocalProvider(LocalPhone provides spec.phone)"))
        assertTrue(shell.contains("EditorLayout.timelineHeight(spec, maxHdp, userTimelineDp)"))
        assertTrue(shell.contains("userTimelineDp = (cur - dy / density.density).coerceIn(tlRange.start, tlRange.endInclusive)"))
        // портрет телефона: колонки инструментов нет, строка под холстом с тем же выбором инструмента и кнопкой «Слои»
        assertTrue(shell.contains("if (!spec.toolbarBottom) androidx.compose.animation.AnimatedVisibility("))
        assertTrue(shell.contains("if (spec.toolbarBottom) androidx.compose.animation.AnimatedVisibility("))
        assertTrue(shell.contains("RailItem(Icons.Default.Layers, \"Слои\", rightOpen, dividerAfter = true) { rightOpen = !rightOpen }"))
        assertEquals(2, Regex("""pickTool\b""").findAll(shell).count() - 1)   // объявление + колонка + строка
        assertTrue(shell.contains("EditorLayout.stripOrder(items, spec)"))
        assertTrue(shell.contains("Modifier.width(spec.sidePanelDp.dp).fillMaxHeight()"))
        // компактная шапка: библиотека уходит в «Ещё», экспорт - значком
        assertTrue(shell.contains("if (!compact) BarIcon(Icons.Default.PhotoLibrary, \"Библиотека\")"))
        assertTrue(shell.contains("if (compact) DropdownMenuItem(text = { Text(\"Библиотека\") }"))
        // индикатор инструмента сокращается и в портрете уходит вниз
        assertTrue(shell.contains("EditorLayout.shortLabel(com.vibetoon.ui.i18n.L(toolTitle), spec.labelMaxChars)"))
        assertTrue(shell.contains("if (spec.toolbarBottom) Alignment.BottomEnd else Alignment.TopEnd"))
        // состояние полного экрана и скрытой панели инструмента переживает поворот/пересоздание
        assertTrue(shell.contains("var full by androidx.compose.runtime.saveable.rememberSaveable"))
        assertTrue(shell.contains("var toolRowHidden by androidx.compose.runtime.saveable.rememberSaveable"))
        for (s in listOf("var rightOpen by rememberSaveable", "var timelineOpen by rememberSaveable")) assertTrue(s, shell.contains(s))
    }

    @Test fun referenceWindowRespectsLeftHand() {
        val ov = kt("ui/ReferenceWindowOverlay.kt")
        assertTrue(ov.contains("ReferenceWindow.initial(aspect, areaW, areaH, com.vibetoon.ui.theme.LocalAppSettings.current?.leftHanded == true)"))
    }
}
