package com.vibetoon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 36: SVG и наклон/искажение подключены к репозиторию, VM, интерфейсу и переводам (проверка исходников). */
class Sprint36SkewWiringTest {
    private fun src(path: String): String {
        return (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    }
    private val vm by lazy { src("src/main/java/com/vibetoon/ui/MainViewModel.kt") }
    private val shell by lazy { src("src/main/java/com/vibetoon/ui/shell/EditorShell.kt") }
    private val screen by lazy { src("src/main/java/com/vibetoon/ui/MainScreen.kt") }
    private val repo by lazy { src("src/main/java/com/vibetoon/data/ProjectRepository.kt") }
    private val en by lazy { src("src/main/res/values-en/strings.xml") }
    private val table by lazy { src("src/main/java/com/vibetoon/ui/i18n/I18nTable.kt") }

    @Test fun svgImportIsWiredIntoProjectImportAndEditor() {
        assertTrue(repo.contains("ProjectArchive.Kind.XML"))
        assertTrue(repo.contains("SvgImport.looksLike(text)"))
        assertTrue(src("src/main/java/com/vibetoon/domain/project/foreign/ForeignImport.kt").contains("Kind.SVG -> SvgProject.convert("))
        assertTrue(vm.contains("fun insertSvg(uri: android.net.Uri"))
        assertTrue(vm.contains("SvgImport.fit(r, canvasW, canvasH)"))
        assertTrue(vm.contains("checkpoint(\"svg\", force = true)"))
        assertTrue(screen.contains("svgInsert = { openSvgInsert.launch("))
        assertTrue(screen.contains("vm.svgNotes.collectAsStateWithLifecycle()"))
        assertTrue(shell.contains("add.svgInsert()"))
    }

    @Test fun layerSkewUsesPerspectiveCornersWithUndoAndKeys() {
        assertTrue(vm.contains("fun skewLayer(kxDeg: Float?, kyDeg: Float?)"))
        assertTrue(vm.contains("LayerSkew.withSkew(off, w, h, kxDeg, kyDeg)"))
        assertTrue(vm.contains("checkpoint(\"layer-skew:\$t\", force = true)"))
        assertTrue(vm.contains("private fun writePerspOffsets(t: Int, off: FloatArray)"))
        assertTrue(shell.contains("LayerSkewDialog(cur"))
        assertTrue(shell.contains("vm::straightenLayer"))
    }

    @Test fun vectorShapeTransformBoxIsWired() {
        assertTrue(vm.contains("fun beginVectorTransform(wholeLayer: Boolean)"))
        assertTrue(vm.contains("VectorWarp.boxMap(b)"))
        assertTrue(vm.contains("8 -> vecBoxMove(sx, sy)"))
        assertTrue(vm.contains("if (vecBox != null) return vecBoxDown(sx, sy)"))
        assertTrue(vm.contains("setVectors(id, result)"))
        assertTrue(shell.contains("vm.beginVectorTransform(false)"))
        assertTrue(shell.contains("vm.beginVectorTransform(true)"))
        assertTrue(shell.contains("vm::applyVectorTransform"))
        assertTrue(screen.contains("val p = box ?: vecBox ?: return"))
    }

    @Test fun newStringsHaveEnglish() {
        for (ru in listOf("Наклон/искажение фигуры", "Все фигуры слоя", "Трансформ фигур", "Наклон слоя", "Выпрямить",
                "Импорт SVG: что не перенесено", "SVG в текущий проект (векторные слои)", "Текст SVG не переносится: переведите текст в кривые в редакторе SVG",
                "Градиенты SVG заменены сплошным цветом", "Фигуры трансформированы: прямоугольники, эллипсы и фигуры стали контурами, их узлы правятся пером"))
            assertTrue("перевод: $ru", table.contains("\"$ru\""))
        for (en0 in listOf("Skew/distort shape", "All layer shapes", "Layer skew", "SVG import: what wasn&apos;t imported", "SVG gradients were replaced with a solid color"))
            assertTrue("en: $en0", en.contains(en0) || en.contains(en0.replace("&apos;", "\\'")))
    }
}
