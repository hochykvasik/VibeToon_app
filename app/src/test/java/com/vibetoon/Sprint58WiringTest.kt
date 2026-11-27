package com.vibetoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 58: проводка перехвата пером, отражения для плоского пера и выпуска релиза - текстом исходников. */
class Sprint58WiringTest {
    private fun file(path: String): File = File(path).takeIf { it.exists() } ?: File("../$path").takeIf { it.exists() } ?: File("app/$path")
    private fun src(path: String): String = file(path).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val screen by lazy { kt("ui/MainScreen.kt") }

    @Test fun penTakeoverIsWiredIntoEveryPalmFirstBranch() {
        assertTrue(screen.contains("private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.penStrokesAfterTakeover("))
        assertTrue(screen.contains("StylusTakeover.shouldTakeOver(gestureByTouch, it.pressed, it.previousPressed, vm.paintToolActive())"))
        // 1) «Определение касаний», 2) ладонь сразу после штриха, 3) штрих пальцем, 4) камера/слой/кость от ладони
        assertEquals(4, Regex("""penStrokesAfterTakeover\(vm, (pen|takeover), stylusTracker, (density|screenDensity)\)""").findAll(screen).count())
        assertTrue("след ладони отменяется", screen.contains("vm.paintCancel()\n                                    penStrokesAfterTakeover(vm, takeover, stylusTracker, screenDensity)"))
        assertTrue("кость ладони отменяется", screen.contains("if (capture) { vm.boneCancel(); capture = false }\n                            if (multi) vm.snapCameraRotation()\n                            penStrokesAfterTakeover"))
    }

    @Test fun takeoverStrokeKeepsPenRules() {
        val body = screen.substring(screen.indexOf("penStrokesAfterTakeover(\n"), screen.indexOf("@Composable\nfun EditorSurface("))
        for (s in listOf("vm.stylusStart(down.type == PointerType.Eraser || tracker.eraserTip, tracker.barrelPressed",
            "vm.paintTilt(tracker.tiltRadians, tracker.orientationRadians, first = true)", "StylusSample.historical(tracker.historyFor(c.uptimeMillis)",
            "tracker.canceled(c.uptimeMillis)", "StylusSample.keepReleasePoint", "StylusSample.releasePressure(c.pressure, true, lastP)",
            "ev.changes.filter { !it.isPen() }.forEach { it.consume() }", "vm.paintCancel(); vm.paintStylus(false); vm.stylusTipEnd(); throw e",
            "vm.stylusTipEnd()\n            vm.paintStylus(false)"))
            assertTrue("перехват: $s", body.contains(s))
    }

    @Test fun penNeverCountsAsMultiFingerTap() {
        assertTrue(screen.contains("if (ev.changes.any { it.isPen() && (it.pressed || it.previousPressed) }) validTouch = false"))
        assertFalse(screen.contains("if (gs?.touchDetection == true && ev.changes.any { (it.type == PointerType.Stylus"))
    }

    @Test fun flatPenUsesCameraFlip() {
        assertTrue(kt("ui/MainViewModel.kt").contains("tiltNorm, camera.flipX, camera.flipY)"))
        assertTrue(kt("domain/input/TiltMath.kt").contains("if (flipX) a = pi - a") )
    }

    @Test fun releaseWorkflowRunsOnMainTagsAndPullRequests() {
        val wf = src(".github/workflows/android-build.yml")
        assertTrue(wf.contains("      - main\n") && wf.contains("    tags:\n      - 'v*'") && wf.contains("  pull_request:\n    branches:\n      - main"))
        assertTrue(wf.contains("run: bash tools/ci/tests/publish-release-test.sh"))
        assertTrue(wf.contains("if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')"))
        assertTrue("релиз - после проверки подписи и загрузки APK", wf.indexOf("publish-release.sh vibetoon-debug.apk") > wf.indexOf("Verify debug package and signing certificate"))
        val script = src("tools/ci/publish-release.sh")
        assertTrue(script.contains("compare/main...\$GITHUB_SHA") && script.contains("--verify-tag") && script.contains("refusing to overwrite"))
    }

    @Test fun releaseNotesAndReadmeRemindAboutBackups() {
        val notes = src("docs/RELEASE_NOTES_RU.md")
        assertTrue(notes.contains(".tsproj") && notes.contains("Резервная копия вручную") && notes.contains("vibetoon-debug.apk"))
        assertTrue(notes.contains("Known limitations") || notes.contains("Известные ограничения"))
        // README.md - из main (текст автора, тоже напоминает о .tsproj); сборка и выпуск - в docs/BUILD_AND_RELEASE_RU.md
        assertTrue(src("README.md").contains(".tsproj"))
        val build = src("docs/BUILD_AND_RELEASE_RU.md")
        assertTrue(build.contains(".tsproj") && build.contains("RELEASE_NOTES_RU.md") && build.contains("Резервная копия вручную"))
        assertTrue(build.contains("git tag v0.1.0-beta") && build.contains("tools/ci/publish-release.sh"))
    }
}
