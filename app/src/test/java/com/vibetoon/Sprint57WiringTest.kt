package com.vibetoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 57: проводка плавной правой панели и уроков об окне референса - текстом исходников. */
class Sprint57WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val shell by lazy { kt("ui/shell/EditorShell.kt") }
    private val tut by lazy { kt("ui/projects/TutorialsScreen.kt") }
    private val pairs by lazy {
        listOf("../tools/i18n/pairs.py", "../tools/i18n/pairs2.py").flatMap { (File(it).takeIf { f -> f.isFile } ?: File(it.removePrefix("../"))).readLines() }
            .filter { '|' in it }.associate { it.substringBefore('|') to it.substringAfter('|') }
    }

    @Test fun tabletPanelSlidesOverEdgeWithoutReservedGap() {
        assertTrue(shell.contains("val sideOpen = rightOpen && !full && !phone"))
        assertTrue("ход панели - один Animatable с мягкой кривой", shell.contains("Motions.sidePanelSpec(opening = sideOpen)"))
        assertTrue("анимации выключены - сразу", shell.contains("if (!motionOn) sideP.snapTo(target) else sideP.animateTo("))
        assertTrue("место в строке - только у открытой панели", shell.contains("if (sideOpen && sideSettled) {"))
        assertTrue("сдвиг - в фазе раскладки", shell.contains(".offset { androidx.compose.ui.unit.IntOffset(com.vibetoon.domain.settings.SidePanelSlide.offsetPx(sideP.value, sidePx.intValue, sideAtRight), 0) }"))
        assertTrue("уехавшая панель не рисуется", shell.contains("if (sideOpen || sideShown) Box("))
        assertFalse("старый AnimatedVisibility планшетной панели убран", shell.contains("visible = rightOpen && !full && !phone,"))
        // телефонная панель - прежний выезд поверх холста
        assertEquals(1, Regex("""Motions\.sidePanelExit\(toEnd = !leftHanded\)""").findAll(shell).count())
    }

    @Test fun canvasFollowsPanelWithoutExtraRedraws() {
        assertTrue(shell.contains("SidePanelSlide.canvasSpan(b.left, b.right, row.left, row.right, sideP.value, sidePx.intValue, sideAtRightNow)"))
        assertTrue(shell.contains("!motionOn || sideP.isRunning) viewRect.snapTo(target)"))
        assertTrue("выезд - облегчённый кадр рендерера", shell.contains("viewRect.isRunning || sideP.isRunning"))
        // ход читается в производных состояниях, а не прямо в теле EditorShell
        assertTrue(shell.contains("derivedStateOf { sideP.value > 0.001f }") && shell.contains("derivedStateOf { sideP.value >= 0.999f }"))
        val motion = kt("ui/motion/Motion.kt")
        assertTrue(motion.contains("fun sidePanelSpec(opening: Boolean)") && motion.contains("tween(UiMotion.SIDE_PANEL_EXIT_MS, easing = SoftExitEasing)"))
    }

    @Test fun welcomeTourMentionsReferenceWindow() {
        val tour = tut.substring(tut.indexOf("\"Обзор приложения\""), tut.indexOf("\"Покадровая анимация\", \"Рисунки"))
        val step = tour.substring(tour.indexOf("TutorialStep(\"Окно референса\""))
        assertTrue(step.contains("«Действия» → «Окно референса…»") && step.contains("В сцену, проект и экспорт референс не попадает"))
        assertTrue("до финального шага", tour.indexOf("TutorialStep(\"Окно референса\"") < tour.indexOf("TutorialStep(\"Проверка и экспорт\""))
    }

    @Test fun referenceTutorialCoversOpenWhyControlsAndScope() {
        val card = tut.substring(tut.indexOf("\"Окно референса\", \"Картинка-образец поверх холста\""))
            .let { it.substring(0, it.indexOf("    Tutorial(")) }
        for (s in listOf("«Окно референса…»", "PNG, JPEG или WebP", "позу, силуэт и пропорции", "за шапку", "Уголок справа внизу",
            "сворачивает окно в узкую полоску", "разворачивает обратно", "заменяет образец", "крестик ✕ закрывает",
            "в «Отменить», в файле проекта и в экспорте", "1024 px"))
            assertTrue("урок: $s", card.contains(s))
        assertTrue("первый шаг подсвечивает пункт меню", card.contains("UiButton(null, \"Окно референса…\", \"меню «Действия»\", chip = true)"))
        assertTrue(shell.contains("CoachMenuItem(\"Окно референса…\", highlight = coachTarget == \"Окно референса…\")"))
        assertTrue(kt("ui/shell/Coach.kt").contains("CoachMenuTargets.inActionsMenu(step.target)"))
        assertTrue(shell.contains("CoachMenuTargets.menuAction(coachTarget, coachMenu)"))
    }

    @Test fun referenceTextsHaveEnglish() {
        val lits = Regex("\"([^\"\\n]*)\"").findAll(tut.substring(tut.indexOf("\"Окно референса\", \"Картинка-образец"))
            .let { it.substring(0, it.indexOf("    Tutorial(")) }).map { it.groupValues[1] }.filter { Regex("[А-Яа-яЁё]").containsMatchIn(it) }.toList()
        assertTrue(lits.size >= 10)
        for (l in lits) {
            val en = pairs[l]
            assertTrue("перевод: $l", en != null && !Regex("[А-Яа-яЁё]").containsMatchIn(en))
        }
        assertEquals("Reference window", pairs["Окно референса"])
        assertTrue(pairs.getValue("Не часть сцены").startsWith("Not part"))
        val strings = src("src/main/res/values-en/strings.xml")
        assertTrue(strings.contains("A sample image over the canvas"))
    }
}
