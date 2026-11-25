package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 45: проводка исправления экспорта, общей кривой, вводного тура и вкладки «Обрезка» (проверка по исходникам). */
class Sprint45WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private fun cpp(path: String) = src("src/main/cpp/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val renderer by lazy { cpp("render/Renderer.cpp") }

    @Test fun blendLayerBindsBackdropBeforeLayerTexture() {
        assertTrue(renderer.contains("#include \"render/BlendBindPolicy.h\""))
        assertTrue(renderer.contains("blendbind::"))
        // подложка создаётся/копируется до привязки слоя, иначе шейдер читает весь кадр (мини-копия холста в экспорте)
        val draw = renderer.substring(renderer.indexOf("void Renderer::drawTexture("))
        val body = draw.substring(0, draw.indexOf("\n}\n"))
        assertTrue(body.contains("blendbind::bindInputs(gl, tex, [this]() { copyBackdrop(); return static_cast<unsigned>(backdropTex_); });"))
        val ensure = renderer.substring(renderer.indexOf("::ensureBackdrop("))
        assertTrue(ensure.substring(0, ensure.indexOf("\n}\n")).contains("GL_TEXTURE_BINDING_2D"))
    }

    @Test fun selectedKeysOnDifferentLayersShareOneCurve() {
        assertTrue(vm.contains("fun selectedKeysInfo()"))
        assertTrue(vm.contains("fun setSelectedKeysEasing("))
        val open = vm.substring(vm.indexOf("fun openCurveEditor("))
        assertTrue(open.substring(0, open.indexOf("\n    }")).contains("selectedKeysInfo() != null"))
        val ui = kt("ui/timeline/KeyCurveUi.kt")
        assertTrue(ui.contains("fun SelectedKeysCurveSection(vm: MainViewModel)"))
        assertTrue(kt("ui/inspector/InspectorPanel.kt").contains("com.vibetoon.ui.timeline.SelectedKeysCurveSection(vm)"))
    }

    @Test fun drawingStubIsGoneAndCropTabIsReal() {
        val insp = kt("ui/inspector/InspectorPanel.kt")
        assertFalse(insp.contains("\"Рисование\""))
        assertFalse(insp.contains("появится на одном из следующих этапов"))
        assertTrue(insp.contains("TAB_CROP -> CropTab(vm, selectedId, !playing)"))
        assertTrue(insp.contains("vm.setCropEdge(id, edge,") && insp.contains("vm.applyCrop(id)") && insp.contains("vm.cropToContent(id)"))
        // неразрушающая обрезка: проект -> сцена -> рендерер (холст и экспорт одинаково)
        assertTrue(vm.contains("LayerCrop.scene(layerProps, _cropDraft.value)"))
        assertTrue(vm.contains("sc.cropIds = cropsExp.first; sc.cropRects = cropsExp.second"))
        assertTrue(kt("render/VtGLSurfaceView.kt").contains("RenderNative.setCrops(handle, s.cropIds, s.cropRects)"))
        assertTrue(src("src/main/cpp/jni/RenderBridge.cpp").contains("Java_com_vibetoon_nativebridge_RenderNative_setCrops"))
        assertTrue(renderer.contains("uniform vec4 uCrop;") && renderer.contains("croppolicy::inside(cropC, u, v)"))
        assertTrue(kt("domain/project/ProjectJson.kt").contains("m[\"cr\"]"))
        // Undo: применение - одна запись, черновик не переживает Undo
        assertTrue(vm.contains("checkpoint(\"crop\$id\", force = true)"))
        assertTrue(vm.contains("_cropDraft.value = null   // спринт 45: черновик рамки «Обрезки» не переживает Undo/Redo"))
    }

    @Test fun introIsAShortInterfaceTour() {
        val t = kt("ui/projects/TutorialsScreen.kt")
        val first = t.substring(t.indexOf("val TUTORIALS"), t.indexOf("\"Основы работы с холстом\""))
        assertTrue(first.contains("\"Обзор приложения\"") && first.contains("lesson = true"))
        assertFalse(first.contains("Интерактивный урок"))
        // тур не повторяет шаги следующего урока, а проходит по всем зонам экрана
        for (target in listOf("\"Действия\"", "\"Трансформ\"", "\"Объект\"", "\"Слои\"", "\"Свойства\"", "\"◆ Add Key\"", "\"Кости\"", "\"Сетка\"", "coach = \"Export\""))
            assertTrue(target, first.contains(target))
        assertFalse(first.contains("Тестовый PSD"))
        assertTrue(t.contains("\"Объект\" -> \"chip:Объект\""))
        assertTrue(kt("ui/shell/EditorShell.kt").contains("Modifier.coachTarget(\"Свойства\")"))
        assertTrue(kt("ui/timeline/TimelinePanel.kt").contains("Modifier.coachTarget(\"◆ Add Key\")"))
        assertTrue(kt("ui/theme/WelcomeDialog.kt").contains("Обзор интерфейса"))
        assertTrue(kt("MainActivity.kt").contains("WelcomeDialog(settings, onTour = {"))
    }
}
