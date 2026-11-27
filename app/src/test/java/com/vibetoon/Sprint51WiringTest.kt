package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 51: связка VM/UI - отделение деформированных и анимированных слоёв, дубль «с анимацией / без», покадровая
 * анимация («< Кадр», «Кадр >», «+ Кадр», «Дубль кадра», длина нового кадра), калька по слоям-рисункам, плашки к
 * флажкам и плейхеду, точное время, магнит и настройки лесенки. Проверка по исходникам.
 */
class Sprint51WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val timeline by lazy { kt("ui/timeline/TimelinePanel.kt") }
    private val dialogs by lazy { kt("ui/timeline/TimingDialogs.kt") }
    private val shell by lazy { kt("ui/shell/EditorShell.kt") }
    private val onionDialog by lazy { kt("ui/timeline/OnionSkinDialog.kt") }

    private fun body(text: String, start: String): String {
        val i = text.indexOf(start)
        assertTrue("нет: $start", i >= 0)
        val s = text.substring(i)
        return s.substring(0, s.indexOf("\n    }\n").let { if (it < 0) s.length else it })
    }

    @Test fun deformedLayersDetachWithTheirDeformers() {
        val rej = body(vm, "private fun detachRejection(id: Int)")
        assertFalse(rej.contains("Деформация слоя пока не поддерживается"))
        val clone = body(vm, "private fun cloneLayerState(")
        assertTrue(clone.contains("cloneDeformers(from, to, animated)"))
        val def = body(vm, "private fun cloneDeformers(")
        for (s in listOf("LayerClone.base(from, to, base)", "LayerClone.baked(from, to, base, anim.tracks, frameF)", "LayerClone.tracks(from, to, anim.tracks)",
            "warpLayers = warpLayers + to", "meshLayers = meshLayers + (to to it)", "perspLayers = perspLayers + to", "pinLayers = pinLayers + (to to it)",
            "turbLayers = turbLayers + (to to it)", "skinLayers = skinLayers + (to to it)", "denseWarps = denseWarps + (to to it)", "warpGridSizes = warpGridSizes + (to to it)"))
            assertTrue(s, def.contains(s))
        // нетронутый деформер - не деформация (выделение на месте)
        assertTrue(vm.contains("deformed = layerDeforms(id)"))
    }

    @Test fun movingSelectionOffLayerDetachesPart() {
        val up = body(vm, "fun selMoveUp(")
        assertTrue(up.contains("extractSelection(cut = true, partName = dn(\"часть\"), moveCanvas ="))
        assertTrue(up.contains("detachRejection(s.id) == null"))
        val ex = body(vm, "private fun extractSelection(cut: Boolean, partName: String, moveCanvas: Pair<Float, Float>? = null)")
        assertTrue(ex.contains("shiftLayerBy(newId, moveCanvas.first, moveCanvas.second)"))
        val shift = body(vm, "private fun shiftLayerBy(")
        assertTrue(shift.contains("parentWorlds?.get(id)?.inverse()"))
        assertTrue(shift.contains("k.copy(dx = k.dx + lx, dy = k.dy + ly)"))
    }

    @Test fun duplicateAsksAboutAnimation() {
        assertTrue(vm.contains("fun duplicateSelected() { val id = _selected.value ?: return; requestDuplicate(id, null) }"))
        val req = body(vm, "fun requestDuplicate(")
        assertTrue(req.contains("LayerClone.animated(id, _animation.value.tracks)"))
        assertTrue(req.contains("_duplicateAsk.value = id to above"))
        val dup = body(vm, "fun duplicateLayer(id: Int, above: Int?, withAnimation: Boolean = true)")
        assertTrue(dup.contains("if (withAnimation) copyEffectTracks(id, newId)"))
        assertTrue(dup.contains("cloneDeformers(id, newId, withAnimation)"))
        assertTrue(dup.contains("m\${newId}_c\${k + 1}.png"))   // рисунки копии - свои файлы
        assertTrue(dialogs.contains("vm.answerDuplicate(true)") && dialogs.contains("vm.answerDuplicate(false)") && dialogs.contains("vm.answerDuplicate(null)"))
        assertTrue(shell.contains("DuplicateAnimationDialog(vm)"))
    }

    @Test fun frameByFrameUsesShortDrawings() {
        val add = body(vm, "private fun addEmptyFrameAt(note: String?)")
        assertTrue(add.contains("FrameMath.insertAt(cells0, _frame.value, duration())"))
        assertTrue(add.contains("FrameCell(target, \"assets/\$name\", hold)"))
        assertTrue(add.contains("if (drawingLayerMode(m)) { addDrawingLayer(m); return }"))
        assertFalse(vm.contains("FrameMath.freeStart(cells0, _frame.value + 1"))
        assertTrue(vm.contains("getInt(\"frame_hold_default\", 2)"))
        assertTrue(body(vm, "private fun addDrawingLayer(").contains("clip = Clip(start, minOf(duration(), start + _frameHold.value)), above = m.id, goto = start"))
        // пустой промежуток не показывает прошлый рисунок - ни в редакторе, ни в экспорте
        assertTrue(vm.contains("|| id in gaps) t.copy(opacity = 0f)"))
        assertTrue(vm.contains("heldCells[id]?.let { cs -> FrameMath.visible(cs, pf, durExp) == null } == true"))
        // кисть в промежутке - новый рисунок, а не штрих в невидимый
        assertTrue(vm.contains("addEmptyFrameAt(\"Пустой кадр: создан новый рисунок"))
        for (s in listOf("vm.jumpDrawing(false)", "vm.jumpDrawing(true)", "vm::addEmptyFrame", "vm::duplicateFrame", "vm.deleteFrame()",
            "vm.nudgeFrameLength(-it)", "vm.nudgeFrameLength(it)", /* спринт 52: шаг автоповтора удержания */ "vm.setFrameHold(n)", "\"< Кадр\"", "\"Кадр >\"", "\"+ Кадр\"", "\"Дубль кадра\""))
            assertTrue(s, timeline.contains(s))
        // полоски рисунков - по отрезкам с их длиной
        assertTrue(timeline.contains("val cs = cellSpans[l.id]"))
        assertTrue(vm.contains("FrameMath.spans(it.cells, duration())"))
    }

    @Test fun onionFollowsDrawingLayersAndPlayback() {
        val r = body(vm, "private fun refreshOnion()")
        assertTrue(r.contains("OnionSkin.activeNow(_onion.value, _playing.value, st)"))
        assertTrue(r.contains("OnionSkin.planLayers(drawingSpans(_selected.value), _frame.value, st)"))
        assertTrue(r.contains("\"live:\${o.id}\""))
        assertTrue(r.contains("!it.live && ghostShown[it.id] != it.file"))
        assertTrue(body(vm, "private fun buildAliases(").contains("prim[i] = g.owner"))
        assertTrue(body(vm, "private fun applySelection(").contains("refreshOnion()"))
        assertTrue(body(vm, "fun pause()").contains("refreshOnion()"))
        assertTrue(onionDialog.contains("OnionSkin.MAX_STEP"))
        assertTrue(onionDialog.contains("playback = it") && onionDialog.contains("layers = it"))
    }

    @Test fun timelineClipTools() {
        val tool = body(vm, "fun clipTool(op: com.vibetoon.domain.anim.ClipTiming.Op)")
        assertTrue(tool.contains("ClipTiming.apply(op, clipOf(id), _frame.value, _markers.value, duration())"))
        assertTrue(tool.contains("checkpoint(\"cliptool\", force = true)"))
        assertTrue(body(vm, "fun setClipExact(").contains("ClipTiming.exact(clipOf(id), start, end, duration())"))
        val snap = body(vm, "fun snapClipDrag(")
        assertTrue(snap.contains("if (!_magnet.value)"))
        assertTrue(snap.contains("ClipTiming.snapTargets(_markers.value, _frame.value, duration(), others)"))
        assertTrue(timeline.contains("vm.snapClipDrag(id, 1, f, clip.value.end, th)"))
        assertTrue(timeline.contains("vm.snapClipDrag(id, 3, f, f + len, th)"))
        assertTrue(timeline.contains("vm.clipTool(op)") && timeline.contains("FIT_MARKERS"))
        assertTrue(timeline.contains("ExactTimeDialog(vm, exactId)"))
        assertTrue(dialogs.contains("vm.setClipExact(id, start, end)") && dialogs.contains("ClipTiming.parse(") && dialogs.contains("vm.setTimeInSeconds("))
    }

    @Test fun staggerHasSettings() {
        assertTrue(vm.contains("fun staggerSelected() = staggerWith(_staggerSettings.value)"))
        assertTrue(body(vm, "fun staggerWith(").contains("StaggerPlan.starts(clips.map { it.start }, clips.map { it.end - it.start }, st.step, st.chain, st.reverse)"))
        assertTrue(vm.contains("putString(\"stagger_settings\", com.vibetoon.domain.anim.StaggerPlan.encode(n))"))
        assertTrue(timeline.contains("onLong = { showStagger = true }"))
        assertTrue(dialogs.contains("vm.staggerWith(st)"))
    }
}
