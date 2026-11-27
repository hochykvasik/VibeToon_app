package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 46: проводка кривых ключей символа, геометрии обрезки, последовательного декодера видео и библиотеки
 * проектов (поиск, сортировка, папки, перетаскивание, выделение) - проверка по исходникам.
 */
class Sprint46WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private fun cpp(path: String) = src("src/main/cpp/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }

    private fun body(text: String, start: String): String {
        val s = text.substring(text.indexOf(start))
        return s.substring(0, s.indexOf("\n    }\n").let { if (it < 0) s.length else it })
    }

    @Test fun symbolKeyCurvesAreRoutedLikeOtherKeys() {
        // старое правило «между разными формами - всегда Hold» убрано; спринт 47: между формами - растворение (frameRef)
        val forms = kt("domain/project/SymbolForms.kt")
        assertTrue(forms.contains("FormRef(SymbolPlayback.instanceFrame(keys, f, dur, animated, mode, start))"))
        assertTrue(vm.contains("fun openTimeKeysCurveEditor(instId: Int? = null)"))
        assertTrue(vm.contains("fun setTimeKeysEasing(instId: Int?, type: EasingType)"))
        assertTrue(vm.contains("private fun applyTimeKeys(t: Map<Int, Set<Int>>, undoKey: String?"))
        // «График…» (f(x)) пишет кривую и в ключи времени, редактор открывается с их кривой
        assertTrue(body(vm, "fun setCurve(").contains("TimelineItemOps.withCurve(keys, frames, data)"))
        assertTrue(body(vm, "fun currentCurve(").contains("TimeEase.curveOf(it)"))
        // общая кривая выделения захватывает и ключи времени
        assertTrue(body(vm, "fun openSelectedCurveEditor(").contains("selectedTimeTargets = timeTargets.ifEmpty { null }"))
        assertTrue(kt("ui/inspector/InspectorPanel.kt").contains("com.vibetoon.ui.timeline.TimeKeysCurveSection(vm, s.id)"))
        assertTrue(kt("ui/timeline/TimelinePanel.kt").contains("vm.openTimeKeysCurveEditor(tkInst)"))
        assertTrue(kt("ui/SymbolDialogs.kt").contains("vm.openTimeKeysCurveEditor(instId)"))
        assertTrue(kt("domain/project/ProjectJson.kt").contains("k.curve?.let { c -> mm[\"c\"] = c }"))
    }

    @Test fun cropChangesLayerGeometryEverywhere() {
        assertTrue(vm.contains("crops = cropTable()"))
        assertTrue(vm.contains("private fun texQuad(id: Int)"))
        assertTrue(vm.contains("CropGeometry.cropAlphaMap(bytes0, crop)"))
        val builder = kt("domain/render/SceneBuilder.kt")
        assertTrue(builder.contains("crops"))
        val r = cpp("render/Renderer.cpp")
        assertTrue(r.contains("uniform vec4 uUvRect;"))
        assertTrue(r.contains("vUv = uUvRect.z > 0.0 ? uUvRect.xy + base * uUvRect.zw : base;"))
        assertTrue(r.contains("croppolicy::uvRect(curUv_, uvr);"))
        assertTrue(cpp("render/CropPolicy.h").contains("toTexture"))
    }

    @Test fun videoLayersUseSequentialDecoder() {
        val frames = kt("data/VideoFrames.kt")
        assertTrue(frames.contains("private fun streamFrame(id: Int, r: Req): Boolean"))
        assertTrue(frames.contains("if (streamFrame(id, r)) return"))
        assertTrue(frames.contains("VideoDecodePolicy.canOpenStream(streams.size)"))
        // прежний путь остаётся для форматов, которые декодер не открыл
        assertTrue(frames.contains("getScaledFrameAtTime") || frames.contains("getFrameAtTime"))
        val stream = kt("data/VideoStream.kt")
        assertTrue(stream.contains("MediaExtractor.SEEK_TO_PREVIOUS_SYNC"))
        assertTrue(stream.contains("VideoYuv.convert("))
        assertTrue(kt("data/ExportVideoFrames.kt").contains("smooth = true"))
        assertTrue(vm.contains("m.srcWidth, m.srcHeight, side, playing"))
    }

    @Test fun projectLibrarySearchSortFoldersSelection() {
        val screen = kt("ui/projects/ProjectsScreen.kt")
        val app = kt("ui/AppViewModel.kt")
        // поиск по показанному (переведённому) имени
        assertTrue(screen.contains("ProjectListMath.visible(allProjects, query, folder) { com.vibetoon.ui.i18n.translate(ctx, lang, it.name) }"))
        // список не пропадает при входе: флаг загрузки, размеры - после публикации списка
        assertTrue(screen.contains("starterId != null && (!loaded || allProjects.any { it.id == starterId })"))
        val refresh = body(app, "fun refresh()")
        assertTrue(refresh.indexOf("_projects.value = list") < refresh.indexOf("repo.sizeOf"))
        assertTrue(kt("data/ProjectRepository.kt").contains("metaCache[d.name]"))
        // сортировка сохраняется, свой порядок, папки-карточки, перетаскивание и выделение
        assertTrue(app.contains("prefs.edit().putString(\"sort\", s.name).apply()"))
        assertTrue(app.contains("fun reorder(visible: List<String>)"))
        assertTrue(app.contains("fun deleteMany(ids: Collection<String>)"))
        assertTrue(app.contains("fun moveToFolder(ids: Collection<String>, folder: String)"))
        assertTrue(screen.contains("private fun FolderCard("))
        assertTrue(screen.contains("detectDragGesturesAfterLongPress("))
        assertTrue(screen.contains("androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty() || folder.isNotEmpty())"))
        assertTrue(screen.contains("app.deleteMany(ids)"))
        // старые фильтр-чипы папок заменены карточками и отдельной вкладкой
        assertFalse(screen.contains("FilterChip(selected = folder.isEmpty()"))
    }
}
