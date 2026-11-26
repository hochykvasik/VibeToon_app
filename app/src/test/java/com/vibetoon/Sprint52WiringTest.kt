package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 52: связка UI/VM/репозитория - удержание «− / +», «Новый кадр» как ползунок, быстрый перенос проектов в папку
 * (правка заголовка project.json, список без полного разбора, кэш размеров и превью), освобождение памяти. Проверка
 * по исходникам.
 */
class Sprint52WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val app by lazy { kt("ui/AppViewModel.kt") }
    private val repo by lazy { kt("data/ProjectRepository.kt") }
    private val timeline by lazy { kt("ui/timeline/TimelinePanel.kt") }
    private val projects by lazy { kt("ui/projects/ProjectsScreen.kt") }
    private val activity by lazy { kt("MainActivity.kt") }

    private fun body(text: String, start: String): String {
        val i = text.indexOf(start)
        assertTrue("нет: $start", i >= 0)
        val s = text.substring(i)
        return s.substring(0, s.indexOf("\n    }\n").let { if (it < 0) s.length else it })
    }

    @Test fun lengthButtonsRepeatWhileHeld() {
        assertTrue(timeline.contains("RepeatIcon(Icons.Default.Remove, \"Короче на кадр (удерживайте)\""))
        assertTrue(timeline.contains("RepeatIcon(Icons.Default.Add, \"Длиннее на кадр (удерживайте)\""))
        val rep = timeline.substring(timeline.indexOf("private fun RepeatIcon("))
        for (s in listOf("StepGestures.FIRST_DELAY_MS", "StepGestures.stepAfter(n)", "StepGestures.delayAfter(n)", "repeat.cancel()", "awaitFirstDown"))
            assertTrue(s, rep.substring(0, rep.indexOf("\n}\n")).contains(s))
        // правки удержания - одна запись Undo; у края - без новых записей
        val nudge = body(vm, "fun nudgeFrameLength(")
        assertTrue(nudge.contains("undoKey = \"framelen\${m.id}\""))
        assertTrue(nudge.contains("if (end == c.end) return"))
        assertTrue(body(vm, "private fun commitCells(").contains("if (undoKey != null) checkpoint(undoKey) else checkpoint(\"frames\", force = true)"))
    }

    @Test fun newFrameChipDragsLikeASlider() {
        for (s in listOf("detectHorizontalDragGestures(", "StepGestures.dragValue(start, acc, stepPx, 1, com.vibetoon.ui.MAX_FRAME_HOLD)",
            "onDragEnd = { dragHold?.let { vm.setFrameHold(it) }; dragHold = null }", "val shownHold = dragHold ?: hold", "Text(\"Новый кадр: \$shownHold\"",
            "androidx.compose.material3.Slider(", "onValueChangeFinished = { vm.setFrameHold(sv.roundToInt()) }", "listOf(1, 2, 3, 4, 6, 8, 12, 24, 48)"))
            assertTrue(s, timeline.contains(s))
        assertTrue(vm.contains("const val MAX_FRAME_HOLD = 48"))
    }

    @Test fun folderMovePatchesOnlyTheHeader() {
        val move = body(repo, "fun moveToFolder(")
        assertTrue(move.contains("patchMeta("))
        assertFalse(move.contains("ProjectJson.encode"))
        val patch = body(repo, "private fun patchMeta(")
        for (s in listOf("ProjectJson.withTopLevel(text, values)", "ProjectJson.decodeMeta(patched).getOrNull() == now", "metaCache[id] = stamp1 to now", "sizes.update("))
            assertTrue(s, patch.contains(s))
        assertTrue(body(repo, "fun rename(").contains("patchMeta("))
        // список проектов - только заголовки
        assertTrue(body(repo, "fun list()").contains("ProjectJson.decodeMeta(f.readText())"))
        assertTrue(repo.contains("synchronized(writeLock)"))
    }

    @Test fun projectsScreenUpdatesWithoutWaitingForDisk() {
        val move = body(app, "fun moveToFolder(ids: Collection<String>, folder: String)")
        assertTrue(move.indexOf("ProjectListMath.withFolder(_projects.value, ids, folder)") in 0 until move.indexOf("viewModelScope.launch"))
        assertTrue(move.contains("diskLock.withLock"))
        assertTrue(body(app, "fun open(").contains("diskLock.withLock { }"))   // редактор не читает проект до конца записи
        assertTrue(move.contains("Не удалось перенести проектов: \$failed"))
        assertTrue(body(app, "fun refresh()").contains("refreshJob?.cancel()"))
        // поля - выше init (иначе refresh() из init увидел бы null)
        val init = app.indexOf("    init {")
        for (f in listOf("private var refreshJob", "private val diskLock", "private val thumbs")) assertTrue(f, app.indexOf(f) in 0 until init)
        // превью из памяти, данные папок - один раз на список
        assertTrue(projects.contains("app.cachedThumb(key)"))
        assertTrue(projects.contains("ProjectListMath.folderPreviews(allProjects, sortBy, sizes, manual)"))
        assertFalse(projects.contains("ProjectListMath.folderPreview(allProjects, f,"))
    }

    @Test fun memoryIsReturnedOnTrim() {
        assertTrue(activity.contains("app.onTrimMemory(level)"))
        assertTrue(activity.contains("app.restoreCaches()"))
        assertTrue(body(app, "fun open(").contains("thumbs.resize(THUMB_BUDGET / 4)"))
        val trim = body(vm, "fun onTrimMemory(level: Int)")
        assertTrue(trim.contains("cellCache.trim(level)"))
        assertTrue(trim.contains("formCache.trim(limit, pinnedForms())"))
        assertTrue(trim.contains("refreshFormAliases(); dropFormTexturesLater(drop)"))
        // редактор: служебные таблицы считаются один раз, а не на каждый слой
        assertTrue(vm.contains("heldCellIds"))
        val reset = body(vm, "private fun resetEditor(")
        assertTrue(reset.contains("_duplicateAsk.value = null"))
    }
}
