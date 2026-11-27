package com.vibetoon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 48: первая форма символа после повторного открытия проекта - формы не снимаются до разбора PSD, после разбора
 * переснимаются, пустой снимок формы маркера не остаётся в кэше. Проверка по исходникам.
 */
class Sprint48WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }

    private fun body(text: String, start: String): String {
        val s = text.substring(text.indexOf(start))
        return s.substring(0, s.indexOf("\n    }\n").let { if (it < 0) s.length else it })
    }

    @Test fun reopenWaitsForPsdBeforeSnapshots() {
        val open = body(vm, "fun openProject(")
        // флаг ставится после resetEditor (он его сбрасывает) и до публикации холста
        assertTrue(open.indexOf("psdPending = data.psd != null") > open.indexOf("resetEditor()"))
        assertTrue(open.contains("if (openToken == token && !psdStarted && psdPending)"))
        // снимок формы без участников невозможен: строгий снимок ждёт, рабочий цикл форм стоит
        val locked = body(vm, "private suspend fun symbolBitmapLocked(")
        assertTrue(locked.contains("SymbolReadiness.canSnapshot(psdPending, if (strict) symbolMissingMembers(defId) else emptySet())) throw SymbolNotReady()"))
        assertTrue(vm.contains("if (appBackground || projectId == null || psdPending) break"))
        assertTrue(vm.contains("SymbolReadiness.waitCounts(RenderNative.pendingUploadBytes(), _opening.value, psdPending)"))
        assertTrue(body(vm, "private fun resetEditor()").contains("psdPending = false"))
    }

    @Test fun psdLoadRetakesForms() {
        val load = body(vm, "private fun loadPsd(")
        assertTrue(load.contains("} finally {") && load.contains("psdLoaded()"))
        val loaded = body(vm, "private fun psdLoaded()")
        assertTrue(loaded.contains("if (was) invalidateForms() else invalidateFormsIfChanged()"))
        // миниатюры маркеров - тоже после разбора PSD
        assertTrue(vm.contains("while (psdPending && projectId == pid) delay(150)"))
    }

    @Test fun blankMarkerFormIsNotFinal() {
        assertTrue(vm.contains("SymbolReadiness.suspiciousBlank(blank, key.local, def.markers)"))
        assertTrue(vm.contains("if (strict && !suspicious) formProvisional.remove(key)"))
        assertTrue(vm.contains("formProvisional[key] = n; scheduleProvisionalRetry()"))
        // повтор временных форм идёт сам, а не только при смене кадра
        val sched = body(vm, "private fun scheduleProvisionalRetry()")
        assertTrue(sched.contains("delay(PROVISIONAL_RETRY_MS)") && sched.contains("retryProvisionalForms()"))
        assertTrue(body(vm, "private fun retryProvisionalForms()").contains("|| psdPending) return false"))
        assertTrue(body(vm, "private fun resetForms(").contains("provisionalRetryJob?.cancel()"))
        // смеси: пустой снимок формы маркера не кэшируется
        assertTrue(vm.contains("if (!keep || markerBlank) return p"))
    }
}
