package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 54: проводка исправлений в UI и ViewModel (текстом исходников). */
class Sprint54WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }

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

    @Test fun tabRowsUseSafeIndex() {
        val insp = kt("ui/inspector/InspectorPanel.kt")
        assertTrue(insp.contains("TabIndex.indexOf(tabs, tabName, lastIndex[0])"))
        assertTrue(kt("ui/shell/SafeTabs.kt").contains("TabIndex.positionOrNull("))
    }

    @Test fun mergeButtonMergesIntoOneLayerAndGroupIsSeparate() {
        val shell = kt("ui/shell/EditorShell.kt")
        val merge = shell.indexOf("OutlinedButton(onClick = { vm.mergeSelected() }")
        val group = shell.indexOf("OutlinedButton(onClick = { vm.groupSelected() }")
        assertTrue(merge in 0 until group)   // «Слить в один слой» первой, «Сгруппировать (папка)» - отдельно
        assertTrue(shell.contains("Слить в один слой"))
        assertTrue(shell.contains("Сгруппировать (папка)"))
        val ml = body(vm, "fun mergeLayers(ids: Set<Int>)")
        assertTrue(ml.contains("MergePlan.plan(nodes)"))
        assertFalse(ml.contains("groupSelected("))
    }

    @Test fun mergeHasSingleAtomicUndo() {
        val px = body(vm, "private fun mergeIntoTarget(")
        assertTrue(px.contains("checkpoint(\"merge-down\", force = true)"))
        assertTrue(px.contains("deleteSelected(cp = false)"))
        assertTrue(px.contains("saved.withPaint(op)"))
        val snap = body(vm, "private fun mergeSnapshot(")
        assertTrue(snap.contains("Слои изменились: повторите слияние"))
        assertTrue(snap.contains("checkpoint(\"merge\", force = true)"))
        assertTrue(snap.indexOf("Слои изменились") < snap.indexOf("checkpoint(\"merge\""))
    }

    @Test fun symbolsFollowBones() {
        assertTrue(vm.contains("migrateSymbolLinks(regen = false)"))
        val create = body(vm, "fun createSymbol(")
        assertTrue(create.contains("migrateSymbolLinks()"))
        assertTrue(vm.contains("SymbolBoneLink.migrate(_links.value, defs, insts)"))
        assertTrue(vm.contains("SymbolBoneLink.snapshotLinks"))
        val box = body(vm, "private suspend fun ensureBox(id: Int)")
        assertTrue(box.contains("formCache.entriesOf(inst.symRef)"))
        assertTrue(box.contains("SymbolBoneLink.union(parts)"))
    }

    @Test fun speedRetimesKeysWithOneUndo() {
        val sp = body(vm, "fun setClipSpeed(speed: Float)")
        assertTrue(sp.contains("ClipSpeed.retimeAll("))
        assertTrue(sp.contains("TrackGroups.ids(node, anim.tracks)"))
        assertTrue(sp.contains("checkpoint(\"clipspeed\$id\", force = true)"))
        assertTrue(sp.contains("keySpeed = target"))
        assertTrue(sp.contains("setDurationInPlace(dur)"))
        assertTrue(sp.contains("timeKeys = sorted.mapIndexed"))
        assertTrue(sp.contains("cells = sorted.mapIndexed"))
        val insp = kt("ui/inspector/InspectorPanel.kt")
        assertTrue(insp.contains("ClipSpeedControl(vm, id, vm.clipSpeedOf(id), enabled)"))
        assertTrue(insp.contains("onValueChangeFinished = { vm.setClipSpeed(com.vibetoon.domain.anim.ClipSpeed.fromSlider(pos)) }"))
        assertTrue(insp.contains("ClipSpeed.nudge(applied, up = true)"))
        assertTrue(insp.contains("for (sp in com.vibetoon.domain.anim.ClipSpeed.PRESETS)"))
    }

    @Test fun timelineHandleIsNarrowAndNeedsHold() {
        val tl = kt("ui/timeline/TimelinePanel.kt")
        assertTrue(tl.contains("Spacer(Modifier.width(com.vibetoon.domain.input.HandleDragGate.GAP_DP.dp))"))
        assertTrue(tl.contains("Modifier.width(com.vibetoon.domain.input.HandleDragGate.HANDLE_DP.dp)"))
        assertTrue(tl.contains("HandleDragGate.decide("))
        assertFalse(tl.contains("Modifier.width(36.dp).fillMaxHeight().pointerInput(l.id)"))
    }

    @Test fun playWarmsUpAndCachesScenes() {
        val play = body(vm, "fun play()")
        assertTrue(play.contains("PlayWarmup.ready("))
        assertTrue(play.contains("prebuildPlayScene("))
        assertTrue(play.indexOf("PlayWarmup.ready(") < play.indexOf("audioEngine.play(startFrame"))
        val push = body(vm, "private fun pushScene()")
        assertTrue(push.contains("playScenes.get(_frame.value)?.shallowCopy()"))
        assertTrue(push.contains("if (_playing.value && !pushForFrame) playScenes.clear()"))
        assertTrue(body(vm, "fun pause()").contains("playScenes.clear()"))
        assertTrue(vm.contains("kotlinx.coroutines.delay(com.vibetoon.domain.render.PlayWarmup.FORM_GAP_MS)"))
        assertTrue(body(vm, "private fun invalidateThumb(id: Int)").contains("staleAfterPlay += id"))
    }
}
