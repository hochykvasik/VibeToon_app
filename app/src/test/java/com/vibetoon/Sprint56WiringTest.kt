package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 56: проводка фонового экспорта (служба, уведомления, жизненный цикл экрана) и окна референса - текстом исходников. */
class Sprint56WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val gl by lazy { kt("render/VtGLSurfaceView.kt") }
    private val work by lazy { kt("work/BackgroundWorkService.kt") }
    private val activity by lazy { kt("MainActivity.kt") }
    private val manifest by lazy { src("src/main/AndroidManifest.xml") }

    private fun body(text: String, start: String): String {
        val i = text.indexOf(start)
        assertTrue("нет $start", i >= 0)
        var depth = 0; var j = text.indexOf('{', i); val from = j
        while (j < text.length) {
            if (text[j] == '{') depth++ else if (text[j] == '}') { depth--; if (depth == 0) break }
            j++
        }
        return text.substring(from, j + 1)
    }

    @Test fun notificationTapReturnsToSameWindow() {
        // раньше: намерение лаунчера не совпадало с исходным - система открывала второй экземпляр с экраном проектов
        assertFalse(work.contains("getLaunchIntentForPackage"))
        assertTrue(work.contains("Intent(ctx, com.vibetoon.MainActivity::class.java)"))
        assertTrue(manifest.contains("android:launchMode=\"singleTask\""))
        assertTrue(activity.contains("override fun onNewIntent("))
        assertTrue(body(activity, "private fun handleWorkIntent(").contains("app.openIfExists(pid)"))
    }

    @Test fun configChangesNoLongerRecreateEditorMidExport() {
        val cfg = Regex("android:configChanges=\"([^\"]+)\"").find(manifest)?.groupValues?.get(1).orEmpty()
        for (c in listOf("orientation", "screenSize", "smallestScreenSize", "screenLayout", "keyboardHidden", "uiMode", "density"))
            assertTrue("configChanges без $c", cfg.split('|').contains(c))
        // контекст языка пересобирается вместе с конфигурацией (тема, размер), иначе он устарел бы
        assertTrue(activity.contains("remember(settings.effectiveLang, sysConfig)"))
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"))
    }

    @Test fun everyExportEndsThroughFinishWithOutcome() {
        val init = body(vm, "    init {\n        // Спринт 56: единое состояние задачи экспорта.")
        assertTrue(init.contains("ExportOutcome.classify(true, next"))
        assertTrue(init.contains("BackgroundWork.finish(ctx, exportWork, kind, detail, exportProject)"))
        assertTrue(init.contains("ExportNotices.markInflight"))
        assertTrue(init.contains("ExportNotices.clearInflight"))
        assertFalse(init.contains("BackgroundWork.end(ctx, exportWork)"))
        val finish = body(work, "    fun finish(ctx: Context, id: Long, outcome: ExportOutcome.Kind?")
        assertTrue(finish.contains("cancel(BackgroundWorkService.NOTIFICATION_ID)"))   // ход работы не остаётся
        assertTrue(finish.contains("releaseWake()"))
        assertTrue(finish.contains("ExportOutcome.postToShade(appVisible)"))
    }

    @Test fun clearedViewModelInterruptsExportInsteadOfFreezing() {
        assertTrue(body(vm, "override fun onCleared()").contains("finishExportOnClear()"))
        val clear = body(vm, "private fun finishExportOnClear()")
        assertTrue(clear.contains("exportCancel.set(true)"))
        assertTrue(clear.contains("ExportFiles.discard"))
        assertTrue(clear.contains("ExportOutcome.Kind.INTERRUPTED"))
        assertTrue(clear.contains("ExportNotices.raise"))
        assertTrue(clear.contains("cancelHandler === cancelFromNotice"))
    }

    @Test fun releasedGlViewDeliversInterruptedOnce() {
        val export = body(gl, "fun exportVideo(")
        assertTrue(export.contains("delivered.compareAndSet(false, true)"))
        assertTrue(export.contains("ExportInterrupted(com.vibetoon.domain.export.ExportOutcome.REASON_VIEW_CLOSED)"))
        assertTrue(body(gl, "fun releaseNative()").contains("exportAbandon?.invoke()"))
        // и ViewModel показывает это как «прерван», а не как тихий возврат в Idle
        assertTrue(vm.contains("it is com.vibetoon.domain.export.ExportInterrupted) ExportUi.Failed("))
        assertTrue(vm.contains("data class Failed(val message: String, val interrupted: Boolean = false)"))
    }

    @Test fun parkedOrStalledExportShowsWaitingWithoutTimer() {
        assertTrue(body(gl, "private fun exportTask()").contains("setParked(!resumeNow && surfacePaused)"))
        assertTrue(kt("ui/MainScreen.kt").contains("it.onExportParked = { on -> vm.onExportParked(on) }"))
        val n = body(work, "internal fun notification(ctx: Context): Notification")
        assertTrue(n.contains("ExportStall.title(pct)"))
        assertTrue(n.contains("!stalled) b.setWhen(job.startedAt).setShowWhen(true).setUsesChronometer(true)"))
        assertTrue(n.contains("ACTION_CANCEL"))
        assertTrue(work.contains("ExportStall.CHECK_MS"))
        assertTrue(body(activity, "override fun onStart()").contains("setAppVisible(this, true)"))
        assertTrue(body(activity, "override fun onStop()").contains("setAppVisible(this, false)"))
    }

    @Test fun leavingEditorAndTrimDuringExportAreSafe() {
        assertTrue(body(vm, "fun closeProject(done: () -> Unit)").contains("EditorLeave.allowed(_export.value is ExportUi.Running)"))
        assertTrue(body(vm, "fun onTrimMemory(level: Int)").contains("EditorLeave.trimFormsAllowed("))
        assertTrue(activity.contains("ExportNotices.recoverAfterRestart(applicationContext)"))
        assertTrue(activity.contains("com.vibetoon.ui.ExportNoticeDialog("))
    }

    @Test fun referenceWindowIsUiOnlyAndWired() {
        val overlay = kt("ui/ReferenceWindowOverlay.kt")
        assertTrue(overlay.contains("ReferenceWindow.moveBy("))
        assertTrue(overlay.contains("ReferenceWindow.resizeBy("))
        assertTrue(overlay.contains("ReferenceWindow.toggleCollapsed("))
        assertTrue(overlay.contains("vm::closeReference"))
        // окно не трогает сцену, рендер и проект
        for (bad in listOf("RenderHost", "setScene", "markDirty", "SceneData", "RenderNative")) assertFalse(bad, overlay.contains(bad))
        val main = kt("ui/MainScreen.kt")
        assertTrue(main.contains("ReferenceWindowOverlay(vm, onReplace = addActions.reference)"))
        // спринт 57: пункт меню стал CoachMenuItem - его подсвечивает урок «Окно референса»
        assertTrue(kt("ui/shell/EditorShell.kt").contains("CoachMenuItem(\"Окно референса…\", highlight = coachTarget == \"Окно референса…\") { actions = false; add.reference() }"))
        val load = body(vm, "fun loadReference(uri: android.net.Uri)")
        assertTrue(load.contains("ReferenceWindow.sampleSize("))
        assertTrue(load.contains("Dispatchers.IO"))
        assertFalse(load.contains("markDirty"))
    }
}
