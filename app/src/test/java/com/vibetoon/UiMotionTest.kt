package com.vibetoon

import com.vibetoon.domain.settings.UiMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 31: анимации интерфейса - системный масштаб, настройка приложения, подключение во всех окнах. */
class UiMotionTest {
    @Test fun systemScaleZeroOrAppSwitchDisablesMotion() {
        assertTrue(UiMotion.enabled(1f, true))
        assertFalse(UiMotion.enabled(0f, true))      // «Отключить анимацию» в Android
        assertFalse(UiMotion.enabled(1f, false))     // настройка «Плавные анимации интерфейса» выключена
        assertTrue(UiMotion.enabled(Float.NaN, true))  // ошибка чтения - как 1
        assertTrue(UiMotion.enabled(-1f, true))
    }

    @Test fun durationsAreBaseOrZeroComposeAppliesScale() {
        assertEquals(UiMotion.PANEL_MS, UiMotion.duration(UiMotion.PANEL_MS, 0.5f, true))
        assertEquals(0, UiMotion.duration(UiMotion.PANEL_MS, 0f, true))
        assertEquals(0, UiMotion.duration(UiMotion.DIALOG_MS, 1f, false))
        assertEquals(120, UiMotion.effectiveMs(240, 0.5f, true))
        assertEquals(1200, UiMotion.effectiveMs(240, 5f, true))
        assertEquals(2400, UiMotion.effectiveMs(240, 50f, true))   // предел 10x
        assertEquals(0, UiMotion.effectiveMs(240, 0f, true))
    }

    @Test fun exitIsShorterAndAllDurationsAreSnappy() {
        assertTrue(UiMotion.EXIT_MS < UiMotion.PANEL_MS)
        assertTrue(UiMotion.EXIT_MS <= UiMotion.DIALOG_MS)
        for (ms in listOf(UiMotion.PANEL_MS, UiMotion.DIALOG_MS, UiMotion.EXIT_MS, UiMotion.TOOLBAR_MS)) assertTrue(ms in 120..300)
        assertTrue(UiMotion.DIALOG_START_SCALE in 0.9f..0.99f)
    }

    @Test fun highRefreshRateGetsMoreFrames() {
        assertEquals(14, UiMotion.frames(240, 1f, true, 60f))
        assertEquals(28, UiMotion.frames(240, 1f, true, 120f))
        assertEquals(0, UiMotion.frames(240, 0f, true, 120f))
        assertEquals(0, UiMotion.frames(240, 1f, true, 0f))
    }

    private fun src(path: String) = File("src/main/java/com/vibetoon/$path").readText()

    @Test fun allAlertDialogsUseAnimatedWrapper() {
        val offenders = File("src/main/java/com/vibetoon").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').endsWith("ui/motion/AlertDialog.kt") }
            .filter { f -> f.readText().let { it.contains("import androidx.compose.material3.AlertDialog\n") || it.contains("androidx.compose.material3.AlertDialog(") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        assertTrue(src("ui/motion/AlertDialog.kt").contains("modifier.motionAppear()"))
    }

    @Test fun panelsUseSharedMotionAndRespectSystemScale() {
        val shell = src("ui/shell/EditorShell.kt")
        assertTrue(shell.contains("Motions.sidePanelEnter(fromEnd = !leftHanded)"))   // спринт 42: правая панель - мягкий переход
        assertTrue(shell.contains("Motions.barEnter(fromTop = true)"))
        assertTrue(shell.contains("Motions.barEnter(fromTop = false)"))
        assertTrue(shell.contains("Motions.toolbarEnter()"))
        assertTrue(shell.contains("Motions.popEnter()"))
        assertFalse("ручные переходы без общего правила", shell.contains("androidx.compose.animation.fadeIn()"))
        val motion = src("ui/motion/Motion.kt")
        assertTrue(motion.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue(motion.contains("EnterTransition.None"))
        assertTrue(src("MainActivity.kt").contains("LocalMotion provides motion"))
    }

    @Test fun settingIsPersistedBackedUpAndTranslated() {
        val app = src("ui/theme/AppTheme.kt")
        assertTrue(app.contains("\"ui_animations\", true"))
        assertTrue(app.contains("uiAnimations = p.getBoolean(\"ui_animations\", true)"))   // импорт резервной копии
        val dlg = src("ui/shell/SettingsDialog.kt")
        assertTrue(dlg.contains("Toggle(\"Smooth UI Animations\")"))
        assertTrue(dlg.contains("\"Smooth UI Animations\" to \"Плавные анимации интерфейса\""))
        val pairs = File("../tools/i18n/pairs2.py").readText()
        assertTrue(pairs.contains("Плавные анимации интерфейса|Smooth UI Animations"))
    }

    @Test fun cellFramesUseCacheAndPool() {
        val vm = src("ui/MainViewModel.kt")
        assertTrue(vm.contains("withContext(cellCache.dispatcher) { cellCache.load(projects.fileOf(pid, act.file)) }"))
        assertTrue(vm.contains("if (d.owned) d.bitmap.recycle()"))
        assertTrue(vm.contains("cellCache.invalidate(destination)"))
        assertTrue(vm.contains("prefetchCells(pid, f)"))
        assertFalse("кэшированный Bitmap нельзя освобождать после загрузки", vm.contains("importer.decodeImage(projects.fileOf(pid, act.file))"))
        val video = src("data/VideoFrames.kt")
        assertTrue(video.contains("setPreferredConfig(Bitmap.Config.ARGB_8888)"))
        assertTrue(video.contains("bmp.recycle()\n                bmp = copy"))
    }
}
