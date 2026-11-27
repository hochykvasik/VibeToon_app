package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 47: проводка растворения форм (холст, экспорт, вложенные символы), пересъёмки форм после загрузки текстур,
 * индикатора открытия проекта, переноса из папки и освобождения кэшей - проверка по исходникам.
 */
class Sprint47WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }

    private fun body(text: String, start: String): String {
        val s = text.substring(text.indexOf(start))
        return s.substring(0, s.indexOf("\n    }\n").let { if (it < 0) s.length else it })
    }

    @Test fun canvasExportAndNestedUseTheSameRule() {
        assertTrue(vm.contains("SymbolForms.frameRef(inst.timeKeys, f, def.symDur"))
        assertTrue(vm.contains("private fun symbolKey(inst: MediaItem, def: MediaItem, f: Int): Int = symbolRef(inst, def, f).dominant"))
        assertTrue(vm.contains("refKey(def.id, symbolRef(inst, def, f), formCrop(inst.id))"))
        // форма-смесь снимается из двух снимков форм
        assertTrue(vm.contains("if (key.blended) blendedForm(def.id, key, strict)"))
        assertTrue(vm.contains("com.vibetoon.domain.project.FormBlend.blend(a, b, w)"))
        // экспорт: смеси на кадрах экспорта и та же таблица форм
        assertTrue(vm.contains("SymbolForms.blendsOn(expFrames) { symbolRef(i, d, it) }"))
        assertTrue(vm.contains("symCache.forms[SymbolFormCache.Key(si.symRef, ref.local, cropF, ref.to, ref.mix)]"))
        assertTrue(vm.contains("SymbolForms.effectiveDuration(d.symDur, d.markers)"))
        // вложенные символы
        assertTrue(vm.contains("if (ref.blended) nestedBlend(nd.id, ref, depth + 1, strict)"))
    }

    @Test fun formsAreRetakenAfterTexturesLoad() {
        assertTrue(vm.contains("private fun retryProvisionalForms()"))
        assertTrue(vm.contains("reloadNonResident(includeSymbolMembers = true)"))
        assertTrue(vm.contains("symbolMemberTextureIds(sc.textureIds)"))
        val req = body(vm, "private fun requestForm(")
        assertTrue(req.contains("FORM_RETRY_MS"))
        val reset = body(vm, "private fun resetForms(")
        assertTrue(reset.contains("formSources.clear()") && reset.contains("formProvisional.clear()") && reset.contains("formFailAt.clear()"))
    }

    @Test fun openingShowsLoaderNotEmptyProject() {
        assertTrue(vm.contains("private val _opening = MutableStateFlow(true)"))
        val open = body(vm, "fun openProject(")
        assertTrue(open.indexOf("_opening.value = true") in 0 until open.indexOf("openJob = viewModelScope.launch"))
        assertTrue(open.contains("if (openToken == token) { _opening.value = false"))
        val screen = kt("ui/MainScreen.kt")
        assertTrue(screen.contains("ProjectOpeningOverlay(vm)"))
        assertTrue(screen.contains("else if (opening && state !is UiState.Error) \"Загрузка проекта…\""))
        val shell = kt("ui/shell/EditorShell.kt")
        assertTrue(shell.contains("if (opening) \"Загрузка проекта…\" else \"Слоёв: \${rows.size}\""))
        assertTrue(shell.contains("if (rows.isEmpty() && !opening) Text("))
        assertTrue(shell.contains("if (shown.isEmpty() && !opening) Text("))
        assertFalse(shell.contains("if (rows.isEmpty()) Text("))
    }

    @Test fun projectCanLeaveItsFolder() {
        val p = kt("ui/projects/ProjectsScreen.kt")
        assertTrue(p.contains("ProjectListMath.overTopDropZone(cy, head?.offset?.y, head?.size?.height)"))
        assertTrue(p.contains("if (overHead) { dropFolder = \"\"; return }"))
        assertTrue(p.contains("app.moveToFolder(selected.toList(), \"\")"))
        assertTrue(p.contains("onUnfolder = if (p.folder.isNotEmpty())"))
        assertTrue(p.contains("Text(\"Убрать из папки\")"))
    }

    @Test fun leavingProjectReleasesCaches() {
        val reset = body(vm, "private fun resetEditor()")
        assertTrue(reset.contains("resetForms(dropTextures = true)"))
        assertTrue(reset.contains("videoFrames.release()"))
        assertTrue(reset.contains("memberCache.clear()") && reset.contains("meshTrackCache.clear()") && reset.contains("liqPsdCache = null"))
    }
}
