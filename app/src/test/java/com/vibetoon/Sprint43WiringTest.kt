package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 43: связка редактора - нет отключения деформаций, кэш сеток, поток ключей, кнопка «ключи слоя», натив. */
class Sprint43WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val timeline by lazy { kt("ui/timeline/TimelinePanel.kt") }
    private val builder by lazy { kt("domain/render/SceneBuilder.kt") }
    private val bridge by lazy { src("src/main/cpp/jni/RenderBridge.cpp") }
    private val renderer by lazy { src("src/main/cpp/render/Renderer.cpp") }
    private val pairs by lazy { (File("../tools/i18n/pairs2.py").takeIf { it.isFile } ?: File("tools/i18n/pairs2.py")).readText() }

    @Test fun deformationIsNeverSkippedOrDisabled() {
        assertFalse(vm.contains("Деформация пропущена"))
        assertFalse(vm.contains("useGroups = false, dense = emptyMap()"))
        assertFalse(vm.contains("Плотный Warp несовместим"))
        assertFalse(vm.contains("Групповая деформация недоступна для этого содержимого"))
        assertFalse(builder.contains("Group mesh conflicts with a leaf deformer"))
        assertFalse(pairs.contains("Деформация пропущена: неподдерживаемое содержимое или область|"))
    }

    @Test fun editorAndExportKeepSeparateDeformCaches() {
        assertTrue(vm.contains("private val deformCache = com.vibetoon.domain.render.DeformCache()"))
        assertTrue(vm.contains("deformCache = deformCache, crops = cropTable())"))   // спринт 46: обрезка в сцене
        assertTrue(vm.contains("val exportDeformCache = com.vibetoon.domain.render.DeformCache()"))
        assertTrue(vm.contains("deformCache = exportDeformCache"))
        assertTrue(vm.contains("deformCache.clear()"))
        assertTrue(builder.contains("deformCache?.retain(order)"))
    }

    @Test fun hotPathSkipsDeformerPointIds() {
        assertTrue(vm.contains("com.vibetoon.domain.render.SceneHotPath::isNodeId"))
        assertTrue(vm.contains("meshEdgeCache.size > 256"))
    }

    @Test fun keysCoverWholeLayer() {
        assertTrue(vm.contains("flow.addKey(anim, id, frame, t, deformerGroupsOf(id))"))
        assertTrue(vm.contains("flow.removeKey(_animation.value, id, frame, base)"))
        assertTrue(vm.contains("LayerKeyFlow.transformEdit("))
        assertTrue(vm.contains("LayerKeyFlow.baseAfterLastKey(id, base[id], lastValue)"))
        assertTrue(timeline.contains("LayerKeyFlow.hasKey(animation, it, frameS.value)"))
        assertTrue(vm.contains("LayerKeyFlow.vertexTarget("))
    }

    @Test fun pathDeformerUsesLayerScaleAndAllowsRootOutsideMesh() {
        assertTrue(vm.contains("PuppetMath.addTo(m, v, pins, poff, w, h, sx, sy)"))
        assertTrue(vm.contains("sz.first, sz.second, lt.scaleX, lt.scaleY)"))
        assertTrue(vm.contains("private val PIN_OUTSIDE = 0.5f"))
    }

    @Test fun selectLayerKeysButtonIsLocalized() {
        assertTrue(vm.contains("fun selectLayerKeys()"))
        assertTrue(timeline.contains("vm::selectLayerKeys"))
        assertTrue(timeline.contains("\"Выбрать все ключи слоя\""))
        assertTrue(pairs.contains("Выбрать все ключи слоя|Select all layer keys"))
        assertTrue(pairs.contains("Выбрано ключей слоя: {}|Layer keys selected: {}"))
    }

    @Test fun nativeMeshesAreValidatedOneByOne() {
        assertTrue(bridge.contains("vt::meshpack::fittingPrefix("))
        assertFalse(bridge.contains("R(h)->setMeshes(i, c, 0, nullptr, nullptr)"))
        assertTrue(renderer.contains("vt::meshpack::meshValid(vc, tc)"))
    }
}
