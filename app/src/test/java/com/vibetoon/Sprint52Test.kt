package com.vibetoon

import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.anim.StepGestures
import com.vibetoon.domain.perf.StampCache
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.JsonException
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.MiniJson
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectListMath
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.ProjectSort
import com.vibetoon.domain.project.SymbolFormCache
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 52: удержание «− / +» и ползунок «Новый кадр» (математика шага), быстрый перенос проекта в папку и обратно
 * (заголовок project.json читается и правится без разбора всего проекта, кэш размеров по штампу файлов, превью папок
 * за один проход).
 */
class Sprint52Test {
    private fun rich(folder: String = "", name: String = "Мой «проект»\n\"2\""): ProjectData {
        val meta = ProjectMeta("ab12cd34", name, 1920, 1080, fps = 30, durationFrames = 240, background = 0xFF112233L,
            createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_123_456L, loop = false, magnet = true, folder = folder)
        val media = (0 until 40).map { i ->
            MediaItem(100_000 + i, MediaKind.IMAGE, "Слой {$i} [x]", "assets/m$i.png", 64, 64, i * 1f, 2f, 64f, 64f,
                cells = if (i % 3 == 0) listOf(FrameCell(0, "assets/a$i.png"), FrameCell(4, "assets/b$i.png", hold = 2)) else emptyList())
        }
        val tracks = media.associate { m -> m.id to AnimationTrack(m.id, (0 until 6).map { f -> Keyframe.of(f * 10, LayerTransform(dx = f * 1.5f, dy = -f.toFloat())) }) }
        return ProjectData(meta, base = media.associate { it.id to LayerTransform(dx = 3f) }, tracks = tracks, media = media,
            clips = mapOf(100_001 to Clip(5, 50)), markers = listOf(24, 48))
    }

    /** Дерево JSON: порядок ключей словарей (слои, треки) после чтения не важен. */
    private fun tree(text: String): Any? = MiniJson.parse(text)

    // ---- заголовок project.json без полного разбора ----

    @Test fun topLevelScannerSkipsNestedValuesAndValidates() {
        val text = """{ "a": {"x": [1, {"y": "}]\""}]}, "b" : "q\u0041", "c": [ ], "d": -1.5e3 }"""
        val ms = MiniJson.topLevel(text)
        assertEquals(listOf("a", "b", "c", "d"), ms.map { it.key })
        assertEquals("\"q\\u0041\"", text.substring(ms[1].valueStart, ms[1].valueEnd))
        assertEquals("qA", MiniJson.parse(text.substring(ms[1].valueStart, ms[1].valueEnd)))
        for (bad in listOf("""{"a": [1, 2}""", """{"a": "x""", """{"a": 1} x""", "[1]", """{"a" 1}""", "")) {
            try { MiniJson.topLevel(bad); throw AssertionError("принят битый текст: $bad") } catch (e: JsonException) { }
        }
    }

    @Test fun metaOnlyDecodeMatchesFullDecode() {
        for (d in listOf(rich(), rich(folder = "Папка \"1\""), ProjectData(ProjectMeta("x1", "P", 10, 10)))) {
            val text = ProjectJson.encode(d)
            assertEquals(ProjectJson.decode(text).getOrThrow().meta, ProjectJson.decodeMeta(text).getOrThrow())
        }
        // версия новее известной и обрезанный файл - отказ, как у полного чтения
        val text = ProjectJson.encode(rich())
        val newer = text.replaceFirst(Regex("\"version\": *\\d+"), "\"version\": ${ProjectJson.VERSION + 1}")
        assertTrue(ProjectJson.decodeMeta(newer).isFailure)
        assertTrue(ProjectJson.decodeMeta(text.dropLast(40)).isFailure)
        assertTrue(ProjectJson.decodeMeta("""{"name": "без id"}""").isFailure)
    }

    @Test fun folderPatchChangesOnlyTheFolder() {
        val d = rich()
        val text = ProjectJson.encode(d)
        val inFolder = ProjectJson.withTopLevel(text, mapOf("folder" to "ого"))!!
        assertEquals(tree(ProjectJson.encode(d.copy(meta = d.meta.copy(folder = "ого")))), tree(ProjectJson.encode(ProjectJson.decode(inFolder).getOrThrow())))
        // остальной текст - байт в байт (слои, ключи, клипы не пересериализуются)
        assertTrue(inFolder.startsWith(text.substringBefore("\"media\"").substringBeforeLast(",")))
        assertTrue(inFolder.endsWith(text.substring(text.indexOf("\"media\""))))
        // другая папка, затем вынос из папки - текст как исходный
        val other = ProjectJson.withTopLevel(inFolder, mapOf("folder" to "Работы \"2\""))!!
        assertEquals("Работы \"2\"", ProjectJson.decodeMeta(other).getOrThrow().folder)
        val back = ProjectJson.withTopLevel(other, mapOf("folder" to null))!!
        assertEquals(text, back)
        assertEquals(text, ProjectJson.withTopLevel(text, mapOf("folder" to null)))   // уже не в папке
        // компактный JSON тоже правится
        val compact = MiniJson.stringify(MiniJson.parse(text), pretty = false)
        assertEquals("x", ProjectJson.decodeMeta(ProjectJson.withTopLevel(compact, mapOf("folder" to "x"))!!).getOrThrow().folder)
    }

    @Test fun renamePatchAndRefusals() {
        val text = ProjectJson.encode(rich())
        val renamed = ProjectJson.withTopLevel(text, mapOf("name" to "Новое имя", "updatedAt" to 1_800_000_000_000L))!!
        val m = ProjectJson.decodeMeta(renamed).getOrThrow()
        assertEquals("Новое имя", m.name)
        assertEquals(1_800_000_000_000L, m.updatedAt)
        val again = ProjectJson.decode(renamed).getOrThrow()
        assertEquals(tree(ProjectJson.encode(rich().copy(meta = m))), tree(ProjectJson.encode(again)))
        // id, версия и не-заголовочные поля так не правятся; битый текст - null (полная перезапись)
        assertNull(ProjectJson.withTopLevel(text, mapOf("id" to "zz")))
        assertNull(ProjectJson.withTopLevel(text, mapOf("media" to null)))
        assertNull(ProjectJson.withTopLevel(text.dropLast(5), mapOf("folder" to "x")))
    }

    // ---- кэш размеров и список проектов ----

    @Test fun stampCacheRecomputesOnlyChangedEntries() {
        val c = StampCache<String, Long, Long>()
        var walks = 0
        assertEquals(100L, c.get("a", 1L) { walks++; 100L })
        assertEquals(100L, c.get("a", 1L) { walks++; 999L })
        assertEquals(1, walks)
        // известная правка (новая длина project.json): без обхода папки
        assertTrue(c.update("a", 1L, 2L) { it + 5 })
        assertEquals(105L, c.get("a", 2L) { walks++; 0L })
        assertEquals(1, walks)
        // штамп не совпал - запись сбрасывается и пересчитается
        assertFalse(c.update("a", 7L, 8L) { it })
        assertNull(c.peek("a", 2L))
        assertEquals(50L, c.get("a", 9L) { walks++; 50L })
        c.put("b", 1L, 1L); c.retain(setOf("b"))
        assertEquals(1, c.size())
    }

    @Test fun folderPreviewsInOnePassAndOptimisticMove() {
        val all = (1..9).map { ProjectMeta("p$it", "P$it", 10, 10, updatedAt = it.toLong(), folder = if (it % 3 == 0) "" else "F${it % 3}") }
        val sizes = all.associate { it.id to it.updatedAt * 10 }
        for (sort in ProjectSort.values()) {
            val one = ProjectListMath.folderPreviews(all, sort, sizes, listOf("p4", "p1"))
            for (f in listOf("F1", "F2")) assertEquals(ProjectListMath.folderPreview(all, f, sort, sizes, listOf("p4", "p1")), one[f])
            assertNull(one[""])
        }
        val moved = ProjectListMath.withFolder(all, listOf("p3", "p1"), "F2")
        assertEquals("F2", moved.first { it.id == "p3" }.folder)
        assertEquals("F2", moved.first { it.id == "p1" }.folder)
        assertEquals(all.filter { it.id !in setOf("p1", "p3") }, moved.filter { it.id !in setOf("p1", "p3") })
        assertEquals(all.map { it.id }, moved.map { it.id })   // порядок не меняется
        assertEquals("", ProjectListMath.withFolder(moved, listOf("p3"), "").first { it.id == "p3" }.folder)
    }

    @Test fun symbolFormsGiveBackMemoryButKeepVisible() {
        val c = SymbolFormCache(10_000)
        val k = (0 until 5).map { SymbolFormCache.Key(1, it, false) }
        k.forEachIndexed { i, key -> c.put(key, SymbolFormCache.Entry(100 + i, FloatArray(4), 1000, c.gen), emptySet()) }
        c.invalidate()   // формы устарели
        c.put(k[4], SymbolFormCache.Entry(200, FloatArray(4), 1000, c.gen), emptySet())   // свежая
        assertEquals(5000L, c.usedBytes())
        val drop = c.trim(2000, setOf(k[0]))
        assertTrue(100 !in drop)            // на экране - остаётся
        assertTrue(200 !in drop)            // свежая уходит последней
        assertEquals(3, drop.size)
        assertEquals(2000L, c.usedBytes())
        assertTrue(c.trim(5000, emptySet()).isEmpty())
        assertEquals(2, c.trim(0, emptySet()).size)
        assertEquals(0L, c.usedBytes())
    }

    // ---- удержание «− / +» и ползунок ----

    @Test fun holdRepeatAccelerates() {
        assertEquals(170L, StepGestures.delayAfter(0))
        assertTrue((0 until 40).all { StepGestures.delayAfter(it + 1) <= StepGestures.delayAfter(it) })
        assertEquals(StepGestures.MIN_DELAY_MS, StepGestures.delayAfter(100))
        assertEquals(1, StepGestures.stepAfter(0)); assertEquals(2, StepGestures.stepAfter(12)); assertEquals(4, StepGestures.stepAfter(30))
        // удержание: с длины 48 до 1 - меньше 3 секунд (вместе с паузой перед повтором)
        var len = 48; var t = StepGestures.FIRST_DELAY_MS; var n = 0
        len -= 1
        while (len > 1) { len = (len - StepGestures.stepAfter(n)).coerceAtLeast(1); t += StepGestures.delayAfter(n); n++ }
        assertTrue("$t мс", t < 3000)
        assertNotNull(t)
    }

    @Test fun dragActsAsSlider() {
        assertEquals(5, StepGestures.dragValue(2, 36f, 12f, 1, 48))
        assertEquals(1, StepGestures.dragValue(2, -100f, 12f, 1, 48))
        assertEquals(48, StepGestures.dragValue(40, 1000f, 12f, 1, 48))
        assertEquals(2, StepGestures.dragValue(2, 11f, 12f, 1, 48))       // меньше шага - без изменений
        assertEquals(2, StepGestures.dragValue(2, Float.NaN, 12f, 1, 48))
        assertEquals(3, StepGestures.dragValue(3, 50f, 0f, 1, 48))
    }
}
