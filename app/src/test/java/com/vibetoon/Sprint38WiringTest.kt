package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 38: слой-путь, слой костей и маски слоёв подключены к VM, сцене (холст, Play, экспорт), рендеру, интерфейсу и переводам. */
class Sprint38WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private val vm by lazy { src("src/main/java/com/vibetoon/ui/MainViewModel.kt") }
    private val shell by lazy { src("src/main/java/com/vibetoon/ui/shell/EditorShell.kt") }
    private val screen by lazy { src("src/main/java/com/vibetoon/ui/MainScreen.kt") }
    private val renderer by lazy { src("src/main/cpp/render/Renderer.cpp") }
    private val policy by lazy { src("src/main/cpp/render/MaskPolicy.h") }
    private val builder by lazy { src("src/main/java/com/vibetoon/domain/render/SceneBuilder.kt") }
    private val en by lazy { src("src/main/res/values-en/strings.xml") }

    @Test fun editorAndExportScenesUseTheSameMasksAndPaths() {
        // редактор (холст и Play) и экспорт (PNG/JPEG/GIF/MOV/PNG-секвенция, PSD) собирают сцену с одними и теми же масками и путями
        assertTrue(vm.contains("layerMasks = options.layerMasks, maskSources = options.maskSources,"))
        assertTrue(vm.contains("pathPlaces = options.pathPlaces { lid -> effective(com.vibetoon.domain.anim.PathLayerMath.progressId(lid)).dx })"))
        assertTrue(vm.contains("layerMasks = sceneOptions.layerMasks, maskSources = sceneOptions.maskSources,"))
        assertTrue(vm.contains("anim.evaluate(pidP, pf, baseCopy[pidP]).dx"))
        // PSD: служебные слои и слои-маски не пишутся, маска уже применена
        assertTrue(vm.contains("media.filter { it.isRig }.map { it.id } + com.vibetoon.domain.model.LayerMasks.sources(layerProps)"))
    }

    @Test fun maskInvertFlagReachesTheRenderer() {
        assertTrue(builder.contains("const val FLAG_MASK_INVERT = 16"))
        assertTrue(policy.contains("constexpr int kMaskInvert = 16;"))
        assertTrue(renderer.contains("multiplyByMask(m, maskpolicy::inverted(f.flags))"))
        assertTrue(renderer.contains("GL_ONE_MINUS_SRC_ALPHA : GL_SRC_ALPHA"))
        assertTrue(renderer.contains("it.flags & (kFlagMaskSrc | kFlagMasked | kFlagMaskInvert)"))   // флаг доезжает до kEndGroup
    }

    @Test fun layerTypesAreCreatedAndRigLayersAreNeverDrawn() {
        assertTrue(shell.contains("vm.addPathLayer()"))
        assertTrue(shell.contains("vm.addBoneLayer()"))
        assertTrue(vm.contains("MediaKind.PATH -> \"Путь\"; MediaKind.BONES -> \"Слой костей\""))
        // Transform/путь/кости: без текстуры, вне контекста символа и прочих наборов «не рисуется»
        assertTrue(vm.contains("m.kind == MediaKind.SYMBOL || m.isRig) return null"))
        assertFalse(vm.contains("it.kind == MediaKind.CAMERA || it.kind == MediaKind.NULLOBJ"))
    }

    @Test fun maskEditingIsUndoableAndCleanedUpOnDelete() {
        for (k in listOf("checkpoint(\"mask-add\"", "checkpoint(\"mask-above\"", "checkpoint(key, force = true)", "checkpoint(\"mask-unlink\""))
            assertTrue(k, vm.contains(k))
        assertTrue(vm.contains("fun setMaskEnabled(target: Int, on: Boolean)"))
        assertTrue(vm.contains("fun setMaskInvert(target: Int, on: Boolean)"))
        assertTrue(vm.contains("setProps(com.vibetoon.domain.model.LayerMasks.cleared(layerProps, all))"))
        assertTrue(vm.contains("it.copy(mask = -1, maskInvert = false, maskOff = false)"))   // дубликат без чужой маски
        assertTrue(shell.contains("vm.setMaskInvert(id, !p.maskInvert)"))
        assertTrue(shell.contains("vm.setMaskEnabled(id, p.maskOff)"))
        assertTrue(shell.contains("\"Добавить маску слоя\" -> vm.addLayerMask(l.id)"))
    }

    @Test fun boneLayerOwnsItsSkeleton() {
        assertTrue(vm.contains("BoneLayers.targetLayer(activeBoneLayer, boneLayerIds())"))
        assertTrue(vm.contains("else if (layer != 0) _links.value = _links.value + (id to layer)"))
        assertTrue(vm.contains("BoneLayers.removeLayers(_bones.value, _links.value, boneLayersGone)"))
        assertTrue(vm.contains("BoneLayers.visible(_bones.value, hiddenBoneLayers())"))
        assertTrue(vm.contains("val (cx, cy) = boneRest(b, cx0, cy0)"))
        assertTrue(shell.contains("vm.editBoneLayer(id)"))
        assertTrue(shell.contains("vm.adoptFreeBones(id)"))
    }

    @Test fun pathLayerIsDrawnAttachedAndAnimated() {
        assertTrue(vm.contains("if (_pathDraw.value && !_boneMode.value) return pathDown(sx, sy)"))
        assertTrue(vm.contains("if (grab == PATH_GRAB) { pathMove(sx, sy); return }"))
        assertTrue(vm.contains("if (grab == PATH_GRAB) { pathUp(); return }"))
        assertTrue(vm.contains("checkpoint(\"path-attach\", force = true)"))
        assertTrue(vm.contains("checkpoint(\"pathpos\$id:\$f\")"))
        assertTrue(vm.contains("_links.value = _links.value + (id to pathId)"))
        assertTrue(screen.contains("PathOverlayLayer(vm)"))
        assertTrue(shell.contains("vm.setPathProgress(id, it)"))
        assertTrue(shell.contains("vm.setPathOrient(id, !p.pathOrient)"))
        assertTrue(shell.contains("vm.setPathPreset(key)"))
    }

    @Test fun newUiStringsAreTranslated() {
        for (s in listOf("Path layer", "Bone layer", "Add layer mask", "Mask from layer above", "Attach to path", "Position", "Along tangent", "Collect free bones"))
            assertTrue(s, en.contains(s))
    }
}
