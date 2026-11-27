package com.vibetoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 59: проводка окна подсказки, тапа по верхней панели и плавной прокрутки списка проектов - текстом исходников. */
class Sprint59WiringTest {
    private fun file(path: String): File = File(path).takeIf { it.exists() } ?: File("../$path").takeIf { it.exists() } ?: File("app/$path")
    private fun src(path: String): String = file(path).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val coach by lazy { kt("ui/shell/Coach.kt") }
    private val shell by lazy { kt("ui/shell/EditorShell.kt") }

    @Test fun coachWindowDragsCollapsesAndClosesWithoutResize() {
        // шапка развёрнутого окна и вся свёрнутая полоска перетаскиваются
        assertEquals(2, Regex("""\.coachDrag\(geo\)""").findAll(coach).count())
        assertTrue(coach.contains("Coach.moveTo(CoachWindow.moveBy(cur, drag.x, drag.y, geo.w, geo.h, geo.areaW, geo.areaH))"))
        assertTrue(coach.contains("if (compact) CoachCompactBar(s, step, geo) else CoachCard(s, step, targetHidden, geo)"))
        for (s in listOf("L(\"Свернуть подсказку\")", "L(\"Развернуть подсказку\")", "L(\"Закрыть урок\")", "\"Шаг \${Coach.index + 1} из \${s.steps.size}: \${step.title}\""))
            assertTrue(s, coach.contains(s))
        // ручки размера нет: ширина из CoachWindow, высота по тексту с прокруткой
        assertFalse(coach.contains("resizeBy"))
        assertFalse(coach.contains("OpenInFull"))
        assertTrue(coach.contains("Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = maxH)"))
        assertTrue(coach.contains(".weight(1f, fill = false).padding(end = 6.dp).verticalScroll(scroll)"))
        // новый урок и закрытие - окно снова на авторазмещении и развёрнуто
        assertTrue(coach.contains("fun start(s: Session) { session = s; index = 0; customPos = null; compact = false }"))
        assertTrue(coach.contains("popupTargetVisible = false; customPos = null; compact = false }"))
    }

    @Test fun stepChecksAndTargetsAreUntouched() {
        assertTrue(coach.contains("CoachMenuTargets.inActionsMenu(step.target)"))
        assertTrue(coach.contains("val targetHidden = step.target != null && (if (popupTarget) !Coach.popupTargetVisible else rect == null)"))
        assertTrue(coach.contains("Кнопка сейчас не видна: выберите слой"))
        assertTrue(coach.contains("onGloballyPositioned { Coach.bounds[id] = it.boundsInWindow() }"))
        // кнопки «Пропустить / Назад / Далее» остаются (о них говорят тексты уроков)
        for (s in listOf("Text(\"Пропустить\")", "Text(\"Назад\")", "\"Готово\" else \"Далее\"")) assertTrue(s, coach.contains(s))
    }

    @Test fun newHintStringsAreTranslated() {
        val pairs = src("../tools/i18n/pairs2.py").takeIf { it.isNotEmpty() } ?: ""
        for (s in listOf("Перетащите, чтобы передвинуть подсказку|", "Свернуть подсказку|", "Развернуть подсказку|", "Закрыть урок|", "Шаг {} из {}: {}|Step {} of {}: {}"))
            assertTrue(s, pairs.contains(s))
    }

    @Test fun topBarFreeSpaceDeselects() {
        // обе раскладки: отдельная строка режимов (высокий экран) и режимы в верхней строке (низкий экран)
        assertTrue(shell.contains("ModeChips(vm, mesh, onMesh)\n            DeselectZone(vm, Modifier.weight(1f).fillMaxHeight())"))
        assertTrue(shell.contains("if (modes) TitleModesRow(vm, name, mesh, onMesh, Modifier.weight(1f))"))
        assertTrue(shell.contains("ModeChips(vm, mesh, onMesh)\n            DeselectZone(vm)\n"))
        assertTrue(shell.contains("TopBarDeselect.titleMax(c.maxWidth, chips.width, minZone)"))
        // осознанный тап: сдвиг, длительность, число пальцев, перехват
        assertTrue(shell.contains("viewConfiguration.longPressTimeoutMillis, pointers, consumed"))
        assertTrue(shell.contains("if (tap && vm.deselectFromBar())"))
        val vm = kt("ui/MainViewModel.kt")
        assertTrue(vm.contains("val busy = stylusDown || grab != 0 || stroker != null || vecLayer >= 0 || guideDrag != null || _toolDrag.value != null"))
        assertTrue(vm.contains("if (!com.vibetoon.domain.input.TopBarDeselect.shouldDeselect(has, busy)) return false\n        selectLayer(null)"))
    }

    @Test fun projectListScrollsWithoutExtraWork() {
        val p = kt("ui/projects/ProjectsScreen.kt")
        assertTrue(p.contains("items(shown, key = { it.id }, contentType = { PROJECT_TYPE })"))
        assertTrue(p.contains("items(folderNames, key = { FOLDER_KEY + it }, contentType = { FOLDER_KEY })"))
        assertTrue(p.contains("key = HEADER_KEY, contentType = HEADER_KEY"))
        assertTrue(p.contains("val subtitle = remember(p.width, p.height, p.fps, p.durationFrames, sizeText, showFolder, p.folder)"))
        assertTrue(p.contains("if (swipe.dismissDirection != SwipeToDismissBoxValue.Settled) Row("))
        assertTrue(p.contains("?.also { it.prepareToDraw() }?.asImageBitmap()"))
        assertTrue(p.contains("app.cachedThumb(\"\${p.id}:\${p.updatedAt}:1\")"))
    }

    @Test fun testerApkIsNotDebuggableButProfileable() {
        val build = src("build.gradle.kts")
        assertTrue(build.contains("debug {\n            isDebuggable = false\n        }"))
        assertTrue(build.contains("getByName(\"debug\")"))   // ключ подписи прежний
        assertTrue(src("src/main/AndroidManifest.xml").contains("<profileable android:shell=\"true\" tools:targetApi=\"29\" />"))
        assertTrue(src("../.github/workflows/android-build.yml").contains("grep -q 'application-debuggable'"))
    }
}
