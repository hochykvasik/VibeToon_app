package com.vibetoon

import com.vibetoon.domain.anim.Curve
import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.Mesh
import com.vibetoon.domain.anim.MeshMath
import com.vibetoon.domain.anim.MeshTrackCache
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.anim.TimeKeyCurves
import com.vibetoon.domain.model.LayerCrop
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.perf.VideoDecodePolicy
import com.vibetoon.domain.perf.VideoYuv
import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectListMath
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.ProjectSort
import com.vibetoon.domain.project.SymbolForms
import com.vibetoon.domain.project.SymbolMath
import com.vibetoon.domain.project.TimeEase
import com.vibetoon.domain.project.TimeKey
import com.vibetoon.domain.project.TimelineItemOps
import com.vibetoon.domain.render.CropGeometry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 46: кривые ключей времени символа реально меняют кадры; геометрия обрезанного слоя (рамки по видимой части);
 * перевод кадров видео из декодера (YUV -> ARGB, поворот, сглаживание при уменьшении) и правила декодирования.
 */
class Sprint46Test {
    private val custom = listOf(0.9f, 0f, 1f, 0.1f)   // резкий конец: почти всё время у начала

    private fun seg(ease: Int, curve: List<Float>? = null) = TimeKeyCurves.samples(TimeKey(0, 0, ease, curve), TimeKey(10, 20))

    // ---------------------------------------------------------------- 1. кривые ключей символа

    @Test fun everyEaseGivesDifferentFrames() {
        val hold = seg(TimeEase.HOLD); val lin = seg(TimeEase.LINEAR); val smooth = seg(TimeEase.SMOOTH)
        val easeIn = seg(TimeEase.EASE_IN); val easeOut = seg(TimeEase.EASE_OUT); val own = seg(TimeEase.CUSTOM, custom)
        val all = listOf(hold, lin, smooth, easeIn, easeOut, own)
        for (i in all.indices) for (j in i + 1 until all.size) assertFalse("переходы $i и $j дают одинаковые кадры", all[i].contentEquals(all[j]))
        // Hold держит форму до ключа, Linear идёт ровно, Ease In отстаёт, Ease Out опережает
        assertTrue(hold.dropLast(1).all { it == 0 })
        assertArrayEquals(IntArray(11) { it * 2 }, lin)
        assertTrue(easeIn[5] < lin[5]); assertTrue(easeOut[5] > lin[5])
        assertTrue(own[5] < easeIn[5] || own[5] != lin[5])
        // начало и конец сегмента одинаковы у всех плавных переходов
        for (s in all.drop(1)) { assertEquals(0, s.first()); assertEquals(20, s.last()) }
    }

    @Test fun curvesWorkBetweenDifferentFormsLikeOtherKeys() {
        // раньше SymbolForms.frameAt делал сегмент на другую форму Hold: все кривые давали одно и то же
        val m = listOf(FrameMarker(0, "A"), FrameMarker(10, "B"))
        fun at(ease: Int, f: Int) = SymbolForms.frameAt(listOf(TimeKey(0, 0, ease), TimeKey(8, 10)), f, 20, animated = true, loop = false, markers = m)
        assertEquals(0, at(TimeEase.HOLD, 4))
        assertEquals(5, at(TimeEase.LINEAR, 4))
        assertNotEquals(at(TimeEase.LINEAR, 2), at(TimeEase.EASE_IN, 2))
        assertNotEquals(at(TimeEase.EASE_IN, 6), at(TimeEase.EASE_OUT, 6))
        assertEquals(10, at(TimeEase.SMOOTH, 8))
    }

    @Test fun presetsMapToTimeEaseAndBack() {
        for (t in EasingType.values()) assertEquals(t, TimeEase.toEasing(TimeEase.fromEasing(t)))
        assertEquals(TimeEase.HOLD, TimeEase.fromEasing(EasingType.STEP))
        assertEquals(TimeEase.SMOOTH, TimeEase.fromEasing(EasingType.EASE_IN_OUT))
        assertNull(TimeEase.toEasing(TimeEase.CUSTOM))
        // редактор «График…» открывается с кривой пресета или своей кривой
        assertEquals(Curve.of(EasingType.EASE_IN).toList(), TimeEase.curveOf(TimeKey(0, 0, TimeEase.EASE_IN)).toList())
        assertEquals(Curve.parse(custom)!!.toList(), TimeEase.curveOf(TimeKey(0, 0, TimeEase.CUSTOM, custom)).toList())
    }

    @Test fun withCurveAndWithEase() {
        val keys = listOf(TimeKey(0, 0, TimeEase.LINEAR), TimeKey(10, 5), TimeKey(20, 9))
        val c = TimelineItemOps.withCurve(keys, setOf(0, 10), custom)
        assertEquals(TimeEase.CUSTOM, c[0].ease); assertEquals(custom, c[0].curve)
        assertEquals(TimeEase.CUSTOM, c[1].ease); assertNull(c[2].curve)
        assertEquals(keys, TimelineItemOps.withCurve(keys, setOf(0), listOf(1f)))   // неверная кривая не применяется
        val e = TimelineItemOps.withEase(c, setOf(0), TimeEase.EASE_OUT)
        assertEquals(TimeEase.EASE_OUT, e[0].ease); assertNull("пресет сбрасывает свою кривую", e[0].curve)
        assertEquals(TimeEase.CUSTOM, e[1].ease)
        assertEquals(TimeEase.HOLD, TimelineItemOps.withEase(keys, setOf(0), TimeEase.CUSTOM)[0].ease)   // CUSTOM без кривой нельзя
    }

    @Test fun curveSurvivesJsonAndMirror() {
        val inst = MediaItem(100001, MediaKind.SYMBOL, "S", "", w = 10f, h = 10f, symRef = 99999,
            timeKeys = listOf(TimeKey(0, 0, TimeEase.CUSTOM, custom), TimeKey(6, 3, TimeEase.EASE_IN), TimeKey(12, 1)))
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(ProjectMeta("p", "n", 100, 100), media = listOf(inst)))).getOrThrow().media.single()
        assertEquals(inst.timeKeys, back.timeKeys)
        val mirrored = TimelineItemOps.mirrorTimeKeys(inst.timeKeys, setOf(0, 6, 12))
        assertTrue("зеркало переносит свою кривую", mirrored.any { it.curve == custom })
    }

    @Test fun infoForPropertiesPanel() {
        assertNull(TimeKeyCurves.info(emptyList()))
        val one = TimeKeyCurves.info(listOf(TimeKey(0, 0, TimeEase.SMOOTH)))!!
        assertEquals(EasingType.EASE_IN_OUT, one.easing); assertFalse(one.custom); assertFalse(one.mixed)
        val own = TimeKeyCurves.info(listOf(TimeKey(0, 0, TimeEase.CUSTOM, custom)))!!
        assertTrue(own.custom); assertNull(own.easing)
        assertTrue(TimeKeyCurves.info(listOf(TimeKey(0, 0, TimeEase.LINEAR), TimeKey(5, 2, TimeEase.HOLD)))!!.mixed)
        assertEquals("Кривая ключа времени", TimeKeyCurves.title(1))
        assertEquals("Кривая ключей времени (3)", TimeKeyCurves.title(3))
    }

    @Test fun customCurveMayOvershootButStaysInSymbol() {
        val over = listOf(0.3f, 1.6f, 0.6f, 1.4f)   // перелёт за конечное значение
        val keys = listOf(TimeKey(0, 0, TimeEase.CUSTOM, over), TimeKey(10, 8))
        val raw = (0..10).map { SymbolMath.timeAt(keys, it)!! }
        assertTrue("перелёт виден в значениях", raw.any { it > 8 })
        for (f in 0..10) assertTrue(SymbolForms.frameAt(keys, f, 9, animated = true, loop = false, markers = emptyList()) in 0..8)
    }

    // ---------------------------------------------------------------- 2. геометрия обрезки

    @Test fun croppedQuadIsTheVisiblePart() {
        // котёнок 576×1024, видимая часть 576×801 (как на скриншоте): рамка слоя - видимая часть
        val c = LayerCrop(0f, 0f, 1f, 801f / 1024f)
        val m = CropGeometry.rect(100f, 50f, 576f, 1024f, c)
        assertEquals(100f, m.applyX(0f, 0f), 1e-3f); assertEquals(50f, m.applyY(0f, 0f), 1e-3f)
        assertEquals(676f, m.applyX(1f, 1f), 1e-3f); assertEquals(50f + 801f, m.applyY(1f, 1f), 1e-2f)
        val side = LayerCrop(0.25f, 0.1f, 0.75f, 0.6f)
        val q = CropGeometry.rect(0f, 0f, 200f, 100f, side)
        assertEquals(50f, q.applyX(0f, 0f), 1e-3f); assertEquals(10f, q.applyY(0f, 0f), 1e-3f)
        assertEquals(150f, q.applyX(1f, 1f), 1e-3f); assertEquals(60f, q.applyY(1f, 1f), 1e-3f)
    }

    @Test fun textureMappingRoundTrips() {
        val c = LayerCrop(0.2f, 0.1f, 0.7f, 0.9f)
        val (tu, tv) = CropGeometry.toTexture(c, 0.5f, 0.5f)
        assertEquals(0.45f, tu, 1e-5f); assertEquals(0.5f, tv, 1e-5f)
        val (u, v) = CropGeometry.fromTexture(c, tu, tv)
        assertEquals(0.5f, u, 1e-5f); assertEquals(0.5f, v, 1e-5f)
        assertEquals(0.2f, CropGeometry.toTexture(c, 0f, 0f).first, 1e-6f)
        assertEquals(0.9f, CropGeometry.toTexture(c, 1f, 1f).second, 1e-6f)
    }

    @Test fun visibleBoxAndAlphaMap() {
        val c = LayerCrop(0.25f, 0f, 0.75f, 0.5f)
        assertArrayEquals(floatArrayOf(0.25f, 0.1f, 0.6f, 0.5f), CropGeometry.visibleBox(floatArrayOf(0.1f, 0.1f, 0.6f, 0.9f), c), 1e-6f)
        assertEquals(0, CropGeometry.visibleBox(floatArrayOf(0f, 0.6f, 1f, 1f), c).size)
        val box = floatArrayOf(0f, 0f, 1f, 1f)
        assertTrue(CropGeometry.visibleBox(box, null) === box)
        // карта 4×2: альфа = номер пикселя; видимая часть - x 1..2, y 0
        val bytes = ByteArray(8 + 8)
        bytes[0] = 4; bytes[4] = 2
        for (i in 0 until 8) bytes[8 + i] = i.toByte()
        val out = CropGeometry.cropAlphaMap(bytes, c)!!
        assertEquals(2, out[0].toInt()); assertEquals(1, out[4].toInt())
        assertArrayEquals(byteArrayOf(1, 2), out.copyOfRange(8, out.size))
        assertTrue(CropGeometry.cropAlphaMap(bytes, null) === bytes)
        assertNull(CropGeometry.cropAlphaMap(ByteArray(4), c))
        assertArrayEquals(intArrayOf(1, 0, 3, 1), CropGeometry.pixelRect(c, 4, 2))
    }

    @Test fun cropTableUsesDraftAndSkipsUncropped() {
        val props = mapOf(1 to LayerProps(crop = listOf(0f, 0f, 1f, 0.5f)), 2 to LayerProps())
        val t = CropGeometry.table(props)
        assertEquals(setOf(1), t.keys)
        assertEquals(0.5f, t.getValue(1).b, 1e-6f)
        val draft = CropGeometry.table(props, 2 to LayerCrop(0.1f, 0f, 1f, 1f))
        assertEquals(setOf(1, 2), draft.keys)
    }

    // ---------------------------------------------------------------- 3. кадры видео

    @Test fun yuvMatrixIsCorrectForBt601AndBt709() {
        val m601 = VideoYuv.matrix(0, 0, 480)
        assertEquals(0xFF000000.toInt(), VideoYuv.argb(16, 128, 128, m601))       // чёрный (ограниченный диапазон)
        assertEquals(0xFFFFFFFF.toInt(), VideoYuv.argb(235, 128, 128, m601))      // белый
        val red = VideoYuv.argb(81, 90, 240, m601)                                 // BT.601 красный
        assertTrue((red shr 16 and 0xFF) > 245 && (red shr 8 and 0xFF) < 10 && (red and 0xFF) < 10)
        val m709 = VideoYuv.matrix(0, 0, 1080)
        val red709 = VideoYuv.argb(63, 102, 240, m709)                              // BT.709 красный
        assertTrue((red709 shr 16 and 0xFF) > 245 && (red709 shr 8 and 0xFF) < 12 && (red709 and 0xFF) < 12)
        val full = VideoYuv.matrix(VideoYuv.STANDARD_BT709, VideoYuv.RANGE_FULL, 1080)
        assertEquals(0xFF000000.toInt(), VideoYuv.argb(0, 128, 128, full))
        assertEquals(0xFFFFFFFF.toInt(), VideoYuv.argb(255, 128, 128, full))
        // серый одинаков во всех матрицах (цветность нейтральна)
        assertEquals(VideoYuv.argb(126, 128, 128, m601), VideoYuv.argb(126, 128, 128, m709))
    }

    /** Кадр 4×2 (I420: плоскости U/V 2×1) с яркостью = 10 * (x + 4y) и нейтральной цветностью, ограниченный диапазон. */
    private fun frame(): Triple<ByteArray, ByteArray, ByteArray> {
        val y = ByteArray(8) { (16 + 10 * it).toByte() }
        return Triple(y, ByteArray(2) { 128.toByte() }, ByteArray(2) { 128.toByte() })
    }

    private fun lum(argb: Int) = argb and 0xFF

    @Test fun convertKeepsOrientationAndRotation() {
        val (y, u, v) = frame()
        val m = VideoYuv.matrix(VideoYuv.STANDARD_BT601_NTSC, VideoYuv.RANGE_FULL, 2)
        val out = IntArray(8)
        VideoYuv.convert(y, 4, 1, u, v, 2, 1, 0, 0, 4, 2, 0, out, 4, 2, m, smooth = false)
        assertEquals((0 until 8).map { 16 + 10 * it }, out.map { lum(it) })   // 1:1 без поворота - пиксель в пиксель
        // поворот 90 (по часовой): экран 2×4, левый верхний угол экрана - левый нижний кадра
        val r = IntArray(8)
        VideoYuv.convert(y, 4, 1, u, v, 2, 1, 0, 0, 4, 2, 90, r, 2, 4, m, smooth = false)
        assertEquals(16 + 10 * 4, lum(r[0])); assertEquals(16 + 10 * 0, lum(r[1]))
        assertEquals(16 + 10 * 7, lum(r[6])); assertEquals(16 + 10 * 3, lum(r[7]))
        assertEquals(2 to 4, VideoYuv.displaySize(4, 2, 90)); assertEquals(4 to 2, VideoYuv.displaySize(4, 2, 180))
        assertEquals(0 to 1, VideoYuv.sourceOf(0, 0, 4, 2, 90)); assertEquals(3 to 1, VideoYuv.sourceOf(0, 0, 4, 2, 180))
        assertEquals(3 to 0, VideoYuv.sourceOf(0, 0, 4, 2, 270))
        // 180: зеркально по обеим осям
        val h = IntArray(8)
        VideoYuv.convert(y, 4, 1, u, v, 2, 1, 0, 0, 4, 2, 180, h, 4, 2, m, smooth = false)
        assertEquals(out.reversed().map { lum(it) }, h.map { lum(it) })
    }

    @Test fun smoothDownscaleAveragesInsteadOfSkipping() {
        // полосы через пиксель (чёрный/белый) 8×8 -> 2×2: ближайший пиксель даёт «рябь» (только один цвет),
        // сглаживание - средний серый, как у картинки на холсте
        val w = 8; val hh = 8
        val y = ByteArray(w * hh) { if ((it % w) % 2 == 0) 0 else 255.toByte() }
        val u = ByteArray(16) { 128.toByte() }; val v = ByteArray(16) { 128.toByte() }
        val m = VideoYuv.matrix(VideoYuv.STANDARD_BT709, VideoYuv.RANGE_FULL, 8)
        val sharp = IntArray(4); val smooth = IntArray(4)
        VideoYuv.convert(y, w, 1, u, v, 4, 1, 0, 0, w, hh, 0, sharp, 2, 2, m, smooth = false)
        VideoYuv.convert(y, w, 1, u, v, 4, 1, 0, 0, w, hh, 0, smooth, 2, 2, m, smooth = true)
        assertTrue(sharp.all { lum(it) == 0 || lum(it) == 255 })
        for (p in smooth) assertTrue("серый ${lum(p)}", lum(p) in 90..165)
    }

    @Test fun convertHonoursDecoderCropAndPixelStride() {
        // декодер отдаёт 6×2 с полями (crop 1..4 по x) и NV12-подобными плоскостями (шаг пикселя цветности 2)
        val y = ByteArray(12) { (it * 20).toByte() }
        val uv = ByteArray(6) { 128.toByte() }
        val m = VideoYuv.matrix(VideoYuv.STANDARD_BT709, VideoYuv.RANGE_FULL, 2)
        val out = IntArray(8)
        VideoYuv.convert(y, 6, 1, uv, uv, 6, 2, 1, 0, 4, 2, 0, out, 4, 2, m, smooth = false)
        assertEquals(listOf(20, 40, 60, 80, 140, 160, 180, 200), out.map { lum(it) })
    }

    @Test fun decodePolicyForPlayback() {
        assertEquals(960, VideoDecodePolicy.maxSide(true, 1))
        assertEquals(720, VideoDecodePolicy.maxSide(true, 3))
        assertEquals(480, VideoDecodePolicy.maxSide(true, 10))
        assertEquals(1280, VideoDecodePolicy.maxSide(false, 10))
        assertTrue(VideoDecodePolicy.canOpenStream(0))
        assertFalse(VideoDecodePolicy.canOpenStream(VideoDecodePolicy.MAX_STREAMS))
        assertNotNull(VideoYuv.matrix(99, 99, 100))
    }

    @Test fun meshTrackCacheMatchesDirectEvaluation() {
        val mesh = MeshMath.generate(64, 64, 4)
        var a = Animation()
        a = a.withKey(MeshMath.vertexId(7, 3), 0, LayerTransform(dx = 0f, dy = 0f))
        a = a.withKey(MeshMath.vertexId(7, 3), 10, LayerTransform(dx = 0.4f, dy = -0.2f))
        val base = mapOf(MeshMath.vertexId(7, 3) to LayerTransform(dx = 0.1f), MeshMath.vertexId(7, 0) to LayerTransform(dy = 0.05f))
        val cache = MeshTrackCache()
        for (f in listOf(0, 5, 10, 5)) for (fx in listOf(true, false))
            assertArrayEquals(MeshMath.offsets(7, mesh, f, base, a, fx), MeshMath.offsets(7, mesh, f, base, a, fx, cache), 1e-6f)
        // тот же кадр и та же анимация - дорожки не пересчитываются; новая анимация - пересчёт
        val p1 = cache.trackPart(7, mesh.count, 5, a)
        assertTrue(p1 === cache.trackPart(7, mesh.count, 5, a))
        val b = a.withKey(MeshMath.vertexId(7, 3), 10, LayerTransform(dx = 0.8f))
        assertFalse(p1 === cache.trackPart(7, mesh.count, 5, b))
        assertArrayEquals(MeshMath.offsets(7, mesh, 5, base, b, true), MeshMath.offsets(7, mesh, 5, base, b, true, cache), 1e-6f)
        cache.retain(emptySet()); assertEquals(0, cache.size())
    }

    // ---------------------------------------------------------------- 4. библиотека проектов

    private fun p(id: String, name: String, t: Long, folder: String = "") = ProjectMeta(id, name, 100, 100, updatedAt = t, folder = folder)
    private val lib = listOf(
        p("a", "Новый проект 2", 30), p("b", "Учебный проект", 10), p("c", "Ёжик в тумане", 20, "Мультики"),
        p("d", "Кот", 40, "Мультики"), p("e", "Пейзаж", 5)
    )
    /** Показанное имя - как переводит словарь в английском режиме. */
    private val en = mapOf("Новый проект 2" to "New project 2", "Учебный проект" to "Tutorial project")
    private val display: (ProjectMeta) -> String = { en[it.name] ?: it.name }

    @Test fun searchFindsDisplayedNamesCaseInsensitive() {
        assertEquals(listOf("a"), ProjectListMath.visible(lib, "new", "", display).map { it.id })
        assertEquals(listOf("b"), ProjectListMath.visible(lib, "Tu", "", display).map { it.id })
        assertEquals(listOf("b"), ProjectListMath.visible(lib, "TUTORIAL  PROJ", "", display).map { it.id })
        assertEquals(listOf("a"), ProjectListMath.visible(lib, "новый", "", display).map { it.id })   // и по исходному имени
        assertEquals(listOf("c"), ProjectListMath.visible(lib, "ежик", "", display).map { it.id })   // ё = е
        assertEquals(listOf("a", "b"), ProjectListMath.visible(lib, "проект", "", display).map { it.id })
        assertTrue(ProjectListMath.visible(lib, "zzz", "", display).isEmpty())
        assertTrue(ProjectListMath.matches("  ", "что угодно"))
        // старый фильтр тоже понимает показанное имя
        assertEquals(listOf("a"), ProjectListMath.filter(lib, "NEW", "", display).map { it.id })
    }

    @Test fun rootShowsLooseProjectsFolderShowsItsOwn() {
        assertEquals(setOf("a", "b", "e"), ProjectListMath.visible(lib, "", "").map { it.id }.toSet())
        assertEquals(setOf("c", "d"), ProjectListMath.visible(lib, "", "Мультики").map { it.id }.toSet())
        assertEquals(listOf("d"), ProjectListMath.visible(lib, "кот", "").map { it.id })   // поиск в корне - и по папкам
        assertTrue(ProjectListMath.visible(lib, "новый", "Мультики", display).isEmpty())
        assertEquals(mapOf("Мультики" to 2), ProjectListMath.folderCounts(lib))
        assertEquals(listOf("d", "c"), ProjectListMath.folderPreview(lib, "Мультики", ProjectSort.DATE, emptyMap(), emptyList()).map { it.id })
    }

    @Test fun sortsInBothDirections() {
        val sizes = mapOf("a" to 300L, "b" to 100L, "c" to 500L, "d" to 50L)
        assertEquals(listOf("d", "a", "c", "b", "e"), ProjectListMath.sort(lib, ProjectSort.DATE, sizes).map { it.id })
        assertEquals(listOf("e", "b", "c", "a", "d"), ProjectListMath.sort(lib, ProjectSort.DATE_OLD, sizes).map { it.id })
        assertEquals(listOf("c", "a", "b", "d", "e"), ProjectListMath.sort(lib, ProjectSort.SIZE, sizes).map { it.id })
        assertEquals(listOf("e", "d", "b", "a", "c"), ProjectListMath.sort(lib, ProjectSort.SIZE_SMALL, sizes).map { it.id })
        assertEquals(ProjectSort.DATE, ProjectSort.of(null)); assertEquals(ProjectSort.SIZE_SMALL, ProjectSort.of("SIZE_SMALL"))
        assertEquals(ProjectSort.DATE, ProjectSort.of("мусор"))
        for (s in ProjectSort.values()) assertTrue(ProjectListMath.sortLabel(s).isNotBlank())
    }

    @Test fun manualOrderAndDragReorder() {
        // свой порядок: известные - по нему, новые (нет в порядке) - сверху, новые сверху
        val order = listOf("e", "b", "a")
        assertEquals(listOf("d", "c", "e", "b", "a"), ProjectListMath.sort(lib, ProjectSort.MANUAL, emptyMap(), order).map { it.id })
        assertEquals(listOf("b", "c", "a"), ProjectListMath.move(listOf("a", "b", "c"), "a", "c"))
        assertEquals(listOf("c", "a", "b"), ProjectListMath.move(listOf("a", "b", "c"), "c", "a"))
        assertEquals(listOf("a", "b"), ProjectListMath.move(listOf("a", "b"), "x", "a"))
        // перестановка в корне не двигает проекты папки в общем порядке
        val global = listOf("a", "c", "b", "d", "e")
        assertEquals(listOf("e", "c", "a", "d", "b"), ProjectListMath.mergeOrder(global, listOf("e", "a", "b")))
        assertEquals(listOf("x", "a", "c"), ProjectListMath.mergeOrder(listOf("a", "c"), listOf("x", "a")))
        assertEquals(listOf("a", "c"), ProjectListMath.pruneOrder(listOf("a", "zz", "c", "a"), setOf("a", "c")))
        assertEquals(order, ProjectListMath.decodeOrder(ProjectListMath.encodeOrder(order)))
        assertTrue(ProjectListMath.decodeOrder(null).isEmpty())
    }

    @Test fun multiSelect() {
        var sel = emptySet<String>()
        sel = ProjectListMath.toggle(sel, "a"); sel = ProjectListMath.toggle(sel, "c")
        assertEquals(setOf("a", "c"), sel)
        sel = ProjectListMath.toggle(sel, "a")
        assertEquals(setOf("c"), sel)
        assertEquals(setOf("a"), ProjectListMath.pruneSelection(setOf("a", "gone"), lib))
    }
}
