package com.vibetoon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stylus sprint: проводка пера, кривой нажима и стабилизации в Compose/ViewModel/настройках проверяется по исходникам
 * (Compose и SharedPreferences нельзя запустить в unit-тесте).
 */
class StylusSprintWiringTest {
    private fun src(path: String): String =
        listOf(File("src/main/java/com/vibetoon/$path"), File("app/src/main/java/com/vibetoon/$path")).first { it.exists() }.readText()
    private fun pairs(): String =
        listOf(File("../tools/i18n/pairs2.py"), File("tools/i18n/pairs2.py")).first { it.exists() }.readText()

    @Test fun canvasReadsStylusDataWithoutLosingPoints() {
        val s = src("ui/MainScreen.kt")
        assertTrue("MotionEvent: нажим истории, наклон, обратный конец пера", s.contains(".motionEventSpy { stylusTracker.onEvent(it) }"))
        assertTrue("исторические точки со своим нажимом", s.contains("StylusSample.historical(stylusTracker.historyFor(c.uptimeMillis), lastP, pm, c.historical.size, stylus)"))
        assertTrue(s.contains("c.historical.forEachIndexed { i, h -> vm.paintMove(h.position.x, h.position.y, hp[i]) }"))
        assertTrue("фантом на отпускании отбрасывается", s.contains("StylusSample.keepReleasePoint(lastPos.x, lastPos.y, c.position.x, c.position.y, screenDensity)"))
        // спринт 44: обратный конец и кнопка на корпусе решаются вместе (StylusButtons.start)
        assertTrue("ластик на обратном конце пера", s.contains("vm.stylusStart(down.type == PointerType.Eraser || stylusTracker.eraserTip, stylusTracker.barrelPressed, down.position.x, down.position.y)"))
        assertTrue(s.contains("vm.stylusTipEnd()"))
        assertTrue("индикатор нажима", s.contains("PressureHudLayer(vm)"))
        assertTrue("старый жёсткий откат 0.05 убран", !s.contains("down.pressure.coerceIn(0.05f, 1f)"))
        val t = src("ui/StylusTracker.kt")
        assertTrue(t.contains("MotionEvent.AXIS_TILT") && t.contains("getHistoricalPressure") && t.contains("TOOL_TYPE_ERASER"))
    }

    @Test fun viewModelAppliesCurveAndStabilizerBeforeGuides() {
        val s = src("ui/MainViewModel.kt")
        assertTrue(s.contains("PressureCurve.decode(prefs?.getString(\"pressure_curve\", null))"))
        assertTrue(s.contains("prefs?.getInt(\"stabilization\""))
        assertTrue("кривая только для пера", s.contains("if (!strokeStylus) return 1f"))
        assertTrue("стабилизатор кормит общий конвейер (направляющие, симметрия, ластик)", s.contains("for (s in st.add(sx, sy, p)) paintMoveCore(s.x, s.y, s.pressure)"))
        assertTrue("хвост догоняет перо до запекания", s.contains("for (s in st.finish()) paintMoveCore(s.x, s.y, s.pressure)"))
        assertTrue(s.contains("private fun paintDownCore(") && s.contains("private fun paintUpCore()"))
        assertTrue("ластик на обратном конце можно выключить", s.contains("getBoolean(\"stylus_eraser_tip\", true)"))
        val down = s.indexOf("fun paintDown(sx: Float, sy: Float, pressure: Float, stylus: Boolean = stylusDown): Boolean")
        assertTrue(down >= 0 && s.indexOf("val ok = paintDownCore(sx, sy, p)", down) > down)
    }

    @Test fun settingsArePersistedResetAndBackedUp() {
        val st = src("ui/theme/AppTheme.kt")
        assertTrue(st.contains("putString(\"pressure_curve\", pressureCurve).putInt(\"stabilization\", stabilization.coerceIn(1, 10))"))
        assertTrue(st.contains("fun resetStylus()"))
        assertTrue("ключи есть в Manual Backup", st.contains("init { if (!p.contains(\"pressure_curve\") || !p.contains(\"stabilization\")) saveStylus() }"))
        // после «Import Backup» значения перечитываются
        val reload = st.substring(st.indexOf("private fun reload()"))
        assertTrue(reload.contains("pressureCurve = ") && reload.contains("stabilization = ") && reload.contains("stylusEraserTip = ") && reload.contains("pressureHud = "))
        val dlg = src("ui/shell/SettingsDialog.kt")
        assertTrue(dlg.contains("\"Stylus\" to emptyList()") && dlg.contains("StylusSettingsSection(st)") && dlg.contains("\"Stylus\" to \"Стилус\""))
        val ui = src("ui/shell/StylusSettings.kt")
        assertTrue("график с живой точкой и редактированием", ui.contains("PressureCurveGraph(") && ui.contains("onChange = { curve = it }"))
        assertTrue("доступность", ui.contains("contentDescription = desc") && ui.contains("contentDescription = stabDesc"))
        val shell = src("ui/shell/EditorShell.kt")
        assertTrue("стабилизация в панели кисти/ластика", shell.contains("ScrubBox(\"Стаб.\", stab.stabilization.toFloat(), 1f, 10f"))
    }

    @Test fun newStringsHaveEnglishPairs() {
        val p = pairs()
        for (line in listOf("Стилус|Stylus", "Чувствительность стилуса|Stylus sensitivity", "Стабилизация: {}|Stabilization: {}",
                "Нажим пера: {} % → кисть: {} %|Pen pressure: {} % → brush: {} %", "Ластик на обратном конце пера|Eraser on the pen's back end",
                "Сбросить настройки пера|Reset pen settings", "Стаб.|Stab.", "Наклон пера: {}°|Pen tilt: {}°"))
            assertTrue(line, p.contains(line))
        for (title in listOf("Очень мягкая", "Мягкая кривая", "Линейная", "Жёсткая кривая", "Очень жёсткая")) assertTrue(title, p.contains("\n$title|"))
    }
}
