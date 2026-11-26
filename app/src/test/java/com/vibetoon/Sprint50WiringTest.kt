package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 50: связка VM/рендерера - отделение выделения и дубликат слоя PSD, кисть на основном выбранном слое,
 * «Слить вниз» в пиксели нижнего слоя, эффекты и Turbulent Displace у группы целиком. Проверка по исходникам.
 */
class Sprint50WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val renderer by lazy { src("src/main/cpp/render/Renderer.cpp") }

    private fun body(text: String, start: String): String {
        val s = text.substring(text.indexOf(start))
        return s.substring(0, s.indexOf("\n    }\n").let { if (it < 0) s.length else it })
    }

    @Test fun selectionDetachAcceptsPsdLayers() {
        val ex = body(vm, "private fun extractSelection(cut: Boolean, partName: String")
        // источник - PNG или слой PSD; мультивыбор не мешает, проверка - общая detachRejection
        assertTrue(ex.contains("detachRejection(s.id)?.let"))
        assertFalse(ex.contains("_selection.value.size != 1"))
        assertFalse(ex.contains("safeSelectionSource(src)"))
        // рамка слоя PSD переводится в холст, вырезанная часть отменяется штрихом (liq_<id>.png)
        assertTrue(ex.contains("LayerDetach.mediaRect(fitAt"))
        assertTrue(ex.contains("PaintOp(s.id, change.rect.x"))
        assertTrue(ex.contains("saved.withPaint(psdCut)"))
        // часть наследует состояние источника и встаёт над ним в той же группе
        assertTrue(ex.contains("cloneLayerState(s.id, newId, psd, fitAt)"))
        assertTrue(ex.contains("StackOrder.place(layers, stackOrder, newId, s.id, above = true)"))
        val rej = body(vm, "private fun detachRejection(id: Int)")
        assertTrue(rej.contains("GroupSelectionPolicy.supports"))
        assertTrue(rej.contains("LayerDetach.breaksClipping"))
        val clone = body(vm, "private fun cloneLayerState(")
        assertTrue(clone.contains("LayerDetach.keys(tr.keys, k)"))
        assertTrue(clone.contains("copyEffectTracks(from, to)"))
    }

    @Test fun psdLayersDuplicateAsImages() {
        val dup = body(vm, "fun duplicateLayer(id: Int, above: Int?")
        assertTrue(dup.contains("duplicatePsdLayer(id, above, withAnimation)"))   // спринт 51: с выбором «с анимацией / без»
        val psd = body(vm, "private fun duplicatePsdLayer(")
        assertTrue(psd.contains("readLayer(id)"))
        assertTrue(psd.contains("LayerDetach.toArgb(lp.bytes, 8, lp.w, lp.h)"))
        assertTrue(psd.contains("cloneLayerState(id, newId, psd = true, fitAt = fitAt, animated = withAnimation)"))
        assertTrue(psd.contains("uploadTextureFor(pid, item)"))
    }

    @Test fun brushTargetsPrimaryLayer() {
        val target = body(vm, "private fun paintTargetId(): Int?")
        assertTrue(target.contains("warpTarget() ?: primaryLeafLayer()"))
        assertTrue(vm.contains("PaintTargetPolicy.primary(_selected.value, _selection.value)"))
        // размер текстуры запрашивается при выборе слоя при любом инструменте
        val sel = body(vm, "private fun applySelection(set: Set<Int>, primary: Int?)")
        assertTrue(sel.contains("(paintTargetId() ?: primaryLeafLayer())?.let { requestPaintSize(it) }"))
        val down = body(vm, "private fun paintDownCore(")
        assertTrue(down.contains("isLockedId(it)) { lockedNotice(); return false }"))
    }

    @Test fun mergeDownKeepsBlendResult() {
        val merge = body(vm, "fun mergeDown(")
        assertTrue(merge.contains("if (mergeDownIntoPixels(id, below.id)) return"))
        val px = vm.substring(vm.indexOf("private fun mergeDownIntoPixels("))
        assertTrue(px.contains("SceneBuilder.blendCode(pt.blend)"))
        assertTrue(px.contains("LayerMergeMath.merge(bt.bytes, 8"))
        assertTrue(px.contains("LayerMergeMath.topOutside("))
        assertTrue(px.contains("saved.withPaint(op)"))
        assertTrue(px.contains("if (fallback) mergeLayers(setOf(top, bottom))"))
    }

    @Test fun groupEffectsRenderOnGroupBuffer() {
        assertTrue(kt("domain/render/SceneBuilder.kt").contains("GroupTurb.split(turbs, fx, quads, groupWorlds.keys)"))
        assertTrue(renderer.contains("groupfx::applies(it.flags, hasGroupEffects(it.layerId), progFx_ != 0)"))
        assertTrue(renderer.contains("runGroupEffects(f.fbo, it.layerId)"))
        assertTrue(renderer.contains("uMode == 25"))
        assertTrue(vm.contains("private fun turbTarget(): Int? = warpTarget() ?: selectedEffectGroup()"))
        assertFalse(vm.contains("Группа: Spin, Swing и Stretch Axis действуют на саму группу"))
        val ci = src("../.github/workflows/android-build.yml")
        assertTrue(ci.contains("GroupFxPolicyTest"))
    }
}
