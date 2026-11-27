package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 41 (тесты на телефоне и планшете): падение на старте, ложный баннер сохранения, автопродление под видео,
 * длительность временем, адаптивные окна, автоключи деформаций и цепочек костей, перевёрнутый холст, фон.
 */
class Sprint41WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val gl by lazy { kt("render/VtGLSurfaceView.kt") }
    private val settings by lazy { kt("ui/shell/SettingsDialog.kt") }
    private val theme by lazy { kt("ui/theme/AppTheme.kt") }
    private val pairs by lazy { (File("../tools/i18n/pairs2.py").takeIf { it.isFile } ?: File("tools/i18n/pairs2.py")).readText() }

    /** Тело функции [name] (до следующего объявления fun на том же уровне отступа). */
    private fun body(s: String, name: String): String {
        val i = s.indexOf(name)
        assertTrue("нет $name", i >= 0)
        val next = Regex("\n    (private |internal |override )?(suspend )?fun ").find(s, i + name.length)
        return s.substring(i, next?.range?.first ?: s.length)
    }

    // ---------------------------------------------------------------- 8. падение и ложный баннер

    @Test fun appViewModelInitRunsAfterAllFields() {
        val app = kt("ui/AppViewModel.kt")
        val init = app.indexOf("    init {")
        assertTrue(init > 0)
        for (field in listOf("private val _sizes", "private val _folders", "private val prefs", "private val _note", "val note:"))
            assertTrue("$field объявлено до init (NPE в refresh на новом устройстве)", app.indexOf(field) in 0 until init)
    }

    @Test fun paintSaveFailuresAreRetriedAndPixelsCapturedBeforePause() {
        val paused = body(vm, "fun onAppPaused()")
        assertTrue(paused.contains("capturePaintBeforePause()"))
        assertTrue("снимок до задержки сохранения", paused.indexOf("capturePaintBeforePause()") < paused.indexOf("delay(250)"))
        assertTrue(body(vm, "private fun capturePaintBeforePause()").contains("view.layerPixelsNow(id)"))
        assertTrue(vm.contains("pauseCapture.remove(id) ?: if (view.surfacePaused) null"))
        val result = body(vm, "private fun onPaintSaveResult(ok: Boolean)")
        assertTrue(result.contains("PaintSaveRetry.report(paintSaveFailures, lastPaintFailureTransient, background)"))
        assertTrue(result.contains("PaintSaveRetry.delayMs(paintSaveFailures)"))
        assertTrue(body(vm, "fun onAppResumed()").contains("pauseCapture.clear()"))
        assertTrue(gl.contains("fun layerPixelsNow("))
    }

    // ---------------------------------------------------------------- 1. автопродление под видео

    @Test fun videoExtendsProjectLikeAudio() {
        val extend = body(vm, "private fun extendForMedia(item: MediaItem): Int")
        assertTrue(extend.contains("item.kind != MediaKind.AUDIO && item.kind != MediaKind.VIDEO"))
        assertTrue(extend.contains("MediaAutoExtend.plan(st.durationFrames, item.durationMs, st.fps, autoExtendMediaOn())"))
        assertTrue("слой «Видео/Аудио»", vm.contains("val extended = extendForMedia(item)   // 1 - продлён до конца"))
        assertTrue("вставка из библиотеки", vm.contains("val extended = extendForMedia(item)   // спринт 41"))
        assertTrue("видео как символ", vm.contains("MediaAutoExtend.planFrames(st.durationFrames, total, autoExtendMediaOn())"))
        assertTrue(vm.contains("getBoolean(com.vibetoon.domain.project.MediaAutoExtend.PREF, true)"))
    }

    // ---------------------------------------------------------------- 3/4. диалоги

    @Test fun timelineSettingsUseTimeAndAdaptiveDialog() {
        val tl = kt("ui/timeline/TimelinePanel.kt")
        val dlg = tl.substring(tl.indexOf("private fun TimelineSettingsDialog("))
        assertTrue(dlg.contains("com.vibetoon.ui.shell.AdaptiveDialog("))
        assertTrue(dlg.contains("TimeFormat.parseDuration(time, it)"))
        assertTrue(dlg.contains("TimeFormat.reframe(cur, old, now)"))
        assertTrue(dlg.contains("FlowRow("))
        assertFalse("нет предела 5000 кадров", tl.contains("до 5000"))
        assertTrue(tl.contains("TimelineZoom.clamp(frameDpRaw, duration, density.density, MIN_FRAME_DP, MAX_FRAME_DP)"))
    }

    @Test fun dialogsFitPhoneAndTablet() {
        val adaptive = kt("ui/shell/AdaptiveDialog.kt")
        assertTrue(adaptive.contains("windowInsetsPadding(WindowInsets.safeDrawing)"))
        assertTrue(adaptive.contains("usePlatformDefaultWidth = false"))
        assertTrue(adaptive.contains(".heightIn(max = this.maxHeight)"))
        assertTrue(adaptive.contains("verticalScroll(rememberScrollState())"))
        assertTrue("заголовок переводится", adaptive.contains("import com.vibetoon.ui.i18n.Text"))
        val fx = kt("ui/inspector/EffectLibrary.kt")
        assertTrue(fx.contains("GridCells.Adaptive(104.dp)"))
        assertTrue(fx.contains("FullWindowDialogSetup()"))
        assertTrue(settings.contains("com.vibetoon.ui.shell.AdaptiveDialog("))
        assertTrue(kt("ui/theme/WelcomeDialog.kt").contains("com.vibetoon.ui.shell.AdaptiveDialog("))
    }

    // ---------------------------------------------------------------- 5. автоключи

    @Test fun deformersUseAutoKeyWithOriginalShape() {
        val mesh = body(vm, "private fun setVertexOffsets(")
        assertTrue(mesh.contains("DeformAutoKey.key(_animation.value, MeshMath.ids("))
        assertTrue(vm.contains("setVertexOffsets(t, m, dirty.filter"))   // кисть деформации
        assertTrue(vm.contains("setVertexOffsets(t, m, mapOf(k to (ox to oy)))"))   // перетаскивание точки
        assertFalse("старый ключ одной вершины без исходной формы", vm.contains("private fun setVertexOffset("))
        assertTrue("Warp", vm.contains("map { WarpMath.pointId(t, it) }"))
        assertTrue("перспектива", vm.contains("(0 until PerspectiveMath.CORNERS).map { PerspectiveMath.pointId(t, it) }"))
        assertTrue("пины", vm.contains("map { PuppetMath.pinId(t, it) }"))
        assertFalse(vm.contains("if (t in groupDeformationAnchors && _frame.value > 0 && !_animation.value.hasTrack(id))"))
    }

    @Test fun boneKeysCoverChainAndSelection() {
        val add = body(vm, "fun addBoneKey()")
        assertTrue(add.contains("boneKeyTargets(b)"))
        assertTrue(add.contains("BoneChainKeys.keyAll(_animation.value, need, f, { poses.getValue(it) }, { boneRestPose(it) })"))
        assertTrue(body(vm, "private fun boneKeyTargets(b: Bone)").contains("BoneChainKeys.targets(b.id, _selection.value, _links.value, ids, boneChainKeysOn())"))
        assertTrue(vm.contains("getBoolean(\"bone_chain_keys\", true)"))
        val edit = body(vm, "private fun storeBoneEdit(b: Bone, t: LayerTransform)")
        assertTrue(edit.contains("if (!autoKeyEnabled() || !canAutoKeyTransform(id)) { storeEdit(id, t); return }"))
        assertTrue(edit.contains("BoneChainKeys.keyAll(anim, listOf(id), f, { t }, { boneRestPose(it) })"))
        val pose = body(vm, "private fun editAnimPose(")
        assertTrue(pose.contains("storeBoneEdit(b, t.copy(dx = px - b.hx, dy = py - b.hy))"))
        assertTrue(pose.contains("storeBoneEdit(b, t.copy(rotation = t.rotation + diff))"))
        val shell = kt("ui/shell/EditorShell.kt")
        assertTrue("кнопка доступна для цепочки", shell.contains("OutlinedButton(onClick = vm::addBoneKey, enabled = !playing,"))
    }

    // ---------------------------------------------------------------- 6. перевёрнутый холст

    @Test fun surfaceBufferIsHeldDuringPanelAnimation() {
        assertTrue(gl.contains("holder.setFixedSize(w, h)"))
        assertTrue(gl.contains("ResizeSettle.applyNow(bufferW, bufferH, w, h)"))
        assertTrue(gl.contains("postDelayed(applySettledSize, com.vibetoon.domain.render.ResizeSettle.SETTLE_MS)"))
        assertTrue(gl.contains("removeCallbacks(applySettledSize)"))
    }

    // ---------------------------------------------------------------- 7. импорт и экспорт в фоне

    @Test fun backgroundWorkServiceIsDeclaredAndUsed() {
        val manifest = src("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifest.contains(".work.BackgroundWorkService"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
        val svc = kt("work/BackgroundWorkService.kt")
        assertTrue(svc.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue(svc.contains("getBoolean(BackgroundRender.PREF, true)"))
        assertTrue("служба не запустилась - фон запрещён", svc.contains("BackgroundWork.serviceStopped()"))
        assertTrue(Regex("importJob = viewModelScope\\.launch \\{").findAll(vm).count() == 0)
        assertTrue(Regex("importJob = launchImport \\{").findAll(vm).count() >= 9)
        assertFalse("метки возврата переименованы вместе с launchImport", Regex("launchImport \\{[^}]*return@launch\\b").containsMatchIn(vm))
        assertTrue(vm.contains("BackgroundWork.begin(ctx, \"Экспорт\""))   // спринт 56: + id проекта для возврата по уведомлению
        assertTrue(vm.contains("RenderHost.view?.backgroundWorkChanged()"))
        assertTrue(vm.contains("private fun glUsable(): Boolean = !appBackground || RenderHost.view?.backgroundRenderAllowed == true"))
        assertTrue(body(vm, "private suspend fun captureExportFrame(").contains("while (!glUsable())"))
        assertTrue(kt("MainActivity.kt").contains("BackgroundWork.requestNotificationPermission = null"))
    }

    @Test fun glViewKeepsRenderingOffscreenWhenMinimized() {
        assertTrue(gl.contains("BackgroundRender.mustPark(transitions, surfacePaused, !backgroundUnavailable, backgroundRenderAllowed)"))
        assertTrue(gl.contains("private fun ensureBackgroundCurrent(): Boolean"))
        assertTrue(gl.contains("eglCreatePbufferSurface"))
        assertTrue(gl.contains("EGL_KHR_surfaceless_context"))
        assertTrue(gl.contains("override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = transition {"))
        assertTrue(gl.contains("fun backgroundWorkChanged()"))
        val exporter = kt("render/VideoExporter.kt")
        assertTrue("кодек возвращает прежний (внеэкранный) контекст", exporter.contains("EGL14.eglMakeCurrent(display, oldDraw, oldRead, context)"))
    }

    // ---------------------------------------------------------------- настройки и перевод

    @Test fun settingsArePersistedBackedUpAndTranslated() {
        for (key in listOf("auto_extend_media", "background_work", "bone_chain_keys")) {
            assertTrue(key, theme.contains("p.getBoolean(\"$key\", true)"))
            assertTrue("$key в reload()", theme.substring(theme.indexOf("private fun reload()")).contains("p.getBoolean(\"$key\", true)"))
        }
        assertTrue(theme.contains("saveSprint41() }"))
        for (title in listOf("Auto-Extend Project for Media", "Background Import/Export", "Bone Chain Keys"))
            assertTrue(title, settings.contains("item.title == \"$title\" && st != null) Switch("))
        for (line in listOf("Продлевать проект под аудио и видео|", "Импорт и экспорт в фоне|", "Ключи всей цепочке костей|",
                "Длительность (мин:сек)|", "Всего кадров|", "Наибольшая длительность при этой частоте: {}|", "Работа продолжается в фоне|",
                "Ключи костей на кадре: {}|"))
            assertTrue(line, pairs.contains("\n$line"))
    }
}
