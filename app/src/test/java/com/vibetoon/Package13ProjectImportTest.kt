package com.vibetoon

import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.Effect
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.anim.SceneRange
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.project.Bone
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.FontKeys
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectArchive
import com.vibetoon.domain.project.ProjectArchive.Kind
import com.vibetoon.domain.project.ProjectArchive.Reason
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.PsdRef
import com.vibetoon.domain.project.TimeKey
import com.vibetoon.domain.project.VolKey
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Пакет 13: импорт проекта - распознавание, безопасная распаковка, полное восстановление структуры, ошибки. */
class Package13ProjectImportTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("vt-import").toFile()

    /** Мини-проект со всем, что должно пережить импорт: слои, покадровый слой, звук, текст со шрифтом, символ, кости, сцены, эффекты. */
    private fun miniProject(): ProjectData {
        val img = 100000; val cells = 100001; val audio = 100002; val text = 100003; val group = 100004; val inst = 100005; val bone = 200000
        return ProjectData(
            meta = ProjectMeta("orig1", "Мини", 1920, 1080, fps = 24, durationFrames = 96, background = 0xFFFFFFFFL, createdAt = 111L, updatedAt = 222L, loop = false, magnet = true, folder = "Работа"),
            base = mapOf(img to LayerTransform(dx = 10f, dy = -5f, rotation = 15f)),
            tracks = mapOf(img to AnimationTrack(img).upsert(Keyframe.of(0, LayerTransform(dx = 0f))).upsert(Keyframe.of(24, LayerTransform(dx = 300f, opacity = 0.5f))),
                bone to AnimationTrack(bone).upsert(Keyframe.of(12, LayerTransform(rotation = 30f)))),
            media = listOf(
                MediaItem(img, MediaKind.IMAGE, "Фон", "assets/m100000.png", 512, 512, 0f, 0f, 1920f, 1080f),
                MediaItem(cells, MediaKind.IMAGE, "Бег", "assets/c1.png", 256, 256, 100f, 100f, 256f, 256f,
                    cells = listOf(FrameCell(0, "assets/c1.png"), FrameCell(6, "assets/c2.png"))),
                MediaItem(audio, MediaKind.AUDIO, "Музыка", "assets/a.mp3", durationMs = 4000, volKeys = listOf(VolKey(0, 0.2f), VolKey(48, 1f)), muted = true),
                MediaItem(text, MediaKind.TEXT, "Заголовок", "", x = 50f, y = 60f, w = 400f, h = 120f, text = "Привет", font = FontKeys.fileKey("Comic.ttf")),
                MediaItem(group, MediaKind.GROUP, "Символ 1", "", symDur = 12, markers = listOf(FrameMarker(0, "A"), FrameMarker(6, "O"))),
                MediaItem(inst, MediaKind.SYMBOL, "Символ 1", "", w = 1920f, h = 1080f, symRef = group, symLoop = true, timeKeys = listOf(TimeKey(0, 0), TimeKey(12, 6)))
            ),
            clips = mapOf(audio to Clip(0, 48, speed = 2f, offset = 4), img to Clip(0, 96, loop = true)),
            markers = listOf(12, 48),
            links = mapOf(img to bone),
            bones = listOf(Bone(bone, "Плечо", 0f, 0f, 100f, 0f)),
            regroup = listOf(cells to group),
            fx = mapOf(img to listOf(Effect(EffectMath.BLUR, List(8) { if (it == 0) 12f else 0f }))),
            layerProps = mapOf(img to LayerProps(name = "Небо", locked = true), cells to LayerProps(visible = false)),
            scenes = listOf(SceneRange(1, "Начало", 0, 48, layers = setOf(img)), SceneRange(2, "Финал", 48, 96, transition = "dissolve", transitionFrames = 8, background = 0xFF000000.toInt())),
            stackOrder = mapOf(-1 to listOf(text, img))   // пакет 25: смешанный порядок - v8
        )
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun tsproj(d: ProjectData, prefix: String = "", extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray = zip(listOf(
        "tsproj.json" to ProjectArchive.manifest(ProjectJson.VERSION, d.meta.name).toByteArray(),
        "${prefix}project.json" to ProjectJson.encode(d).toByteArray(),
        "${prefix}assets/m100000.png" to ByteArray(64) { 1 },
        "${prefix}assets/c1.png" to ByteArray(64) { 2 },
        "${prefix}thumbnail.png" to ByteArray(16) { 3 },
        "fonts/Comic.ttf" to ByteArray(32) { 4 }
    ) + extra)

    private fun reason(block: () -> Unit): Reason? = try { block(); null } catch (e: ProjectArchive.ImportException) { e.reason }

    @Test fun fullStructureSurvivesImport() {
        val src = miniProject()
        val bytes = tsproj(src, extra = listOf("__MACOSX/._project.json" to ByteArray(4), "../evil.txt" to ByteArray(4), "assets/.DS_Store" to ByteArray(4)))
        assertEquals(Kind.ZIP, ProjectArchive.detect(bytes))
        val staging = tmp()
        val files = ProjectArchive.unzip(bytes.inputStream(), staging)
        assertEquals(6, files)   // манифест, project.json, 2 картинки, миниатюра, шрифт; служебные и ../ отброшены
        assertFalse(File(staging.parentFile, "evil.txt").exists())
        assertFalse(File(staging, "__MACOSX").exists())
        val root = ProjectArchive.locateRoot(staging)
        assertEquals(staging, root)

        val r = ProjectArchive.prepare(root!!, "new42", 5000L, listOf("Мини", "Другой"))
        assertEquals("new42", r.data.meta.id)
        assertEquals("Мини (2)", r.data.meta.name)
        assertTrue(r.renamed)
        assertEquals(5000L, r.data.meta.updatedAt)
        assertEquals(111L, r.data.meta.createdAt)
        assertEquals(ProjectJson.VERSION, r.sourceVersion)
        // всё остальное - ровно как в исходном проекте
        val expected = ProjectJson.decode(ProjectJson.encode(src)).getOrThrow()
        // (AnimationTrack сравнивается по содержимому через JSON: это не data class)
        assertEquals(ProjectJson.encode(expected.copy(meta = expected.meta.copy(id = "new42", name = "Мини (2)", updatedAt = 5000L))), ProjectJson.encode(r.data))
        assertEquals(expected.copy(tracks = emptyMap(), meta = r.data.meta), r.data.copy(tracks = emptyMap()))
        assertEquals(listOf(0, 24), r.data.tracks.getValue(100000).keys.map { it.frame })
        assertEquals(300f, r.data.tracks.getValue(100000).keys.last().dx, 0.001f)
        assertEquals(listOf(12), r.data.tracks.getValue(200000).keys.map { it.frame })
        assertEquals(2, r.data.scenes.size)
        assertEquals(listOf(12, 48), r.data.markers)
        assertEquals("Плечо", r.data.bones.single().name)
        assertEquals(true, r.data.media.first { it.kind == MediaKind.AUDIO }.muted)
        assertEquals(2, r.data.media.first { it.kind == MediaKind.GROUP }.markers.size)
        // отсутствующие файлы перечислены, проект при этом импортируется
        assertEquals(listOf("assets/c2.png", "assets/a.mp3"), r.missing)

        // шрифт из архива добавлен один раз; существующий с тем же именем не перезаписывается
        val fonts = tmp()
        val used = ProjectArchive.usedFontFiles(r.data)
        assertEquals(setOf("Comic.ttf"), used)
        assertEquals(1 to emptyList<String>(), ProjectArchive.restoreFonts(File(staging, "fonts"), fonts, used))
        assertEquals(0 to emptyList<String>(), ProjectArchive.restoreFonts(File(staging, "fonts"), fonts, used))
        assertEquals(0 to listOf("Other.otf"), ProjectArchive.restoreFonts(null, fonts, used + "Other.otf"))
        staging.deleteRecursively(); fonts.deleteRecursively()
    }

    @Test fun nestedFolderZipAndPlainJson() {
        val src = miniProject()
        val staging = tmp()
        ProjectArchive.unzip(tsproj(src, prefix = "Mini/").inputStream(), staging)
        val root = ProjectArchive.locateRoot(staging)
        assertEquals(File(staging, "Mini"), root)
        assertEquals(listOf("assets/c2.png", "assets/a.mp3"), ProjectArchive.prepare(root!!, "n1", 1L, emptyList()).missing)
        staging.deleteRecursively()

        val json = ProjectJson.encode(src).toByteArray()
        assertEquals(Kind.JSON, ProjectArchive.detect(json))
        assertEquals(Kind.JSON, ProjectArchive.detect(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), ' '.code.toByte(), '{'.code.toByte())))
        // отдельный project.json: все медиа отмечены отсутствующими, данные целы
        val r = ProjectArchive.prepareJson(String(json), "n2", 1L, emptyList()) { false }
        assertEquals(listOf("assets/m100000.png", "assets/c1.png", "assets/c2.png", "assets/a.mp3"), r.missing)
        assertEquals("Мини", r.data.meta.name)
        assertFalse(r.renamed)
    }

    @Test fun oldVersionsStillImport() {
        val v1 = """{"format":"vibetoon-project","version":1,"id":"old","name":"Старый","width":800,"height":600,"fps":12,"duration":48,
            |"tracks":{"0":[{"f":0,"dx":1.0},{"f":10,"dx":50.0,"ease":"STEP"}]}}""".trimMargin()
        val r = ProjectArchive.prepareJson(v1, "n3", 9L, emptyList()) { true }
        assertEquals(1, r.sourceVersion)
        assertEquals(800, r.data.meta.width)
        assertEquals(12, r.data.meta.fps)
        assertEquals(9L, r.data.meta.createdAt)   // у старых проектов даты могло не быть
        assertTrue(r.data.scenes.isEmpty())
        assertTrue(r.missing.isEmpty())
        // проект с PSD: исходный PSD-файл обязателен
        val withPsd = ProjectArchive.prepareJson(ProjectJson.encode(ProjectData(ProjectMeta("p", "PSD", 1000, 1000), psd = PsdRef("assets/source.psd", "a.psd", 1000, 1000))), "n4", 1L, emptyList()) { false }
        assertEquals(listOf("assets/source.psd"), withPsd.missing)
    }

    @Test fun clearErrors() {
        assertEquals(Reason.NEWER_VERSION, reason { ProjectArchive.prepareJson("""{"version":99,"id":"x"}""", "n", 1L, emptyList()) { true } })
        assertEquals(Reason.DAMAGED, reason { ProjectArchive.prepareJson("{не json", "n", 1L, emptyList()) { true } })
        assertEquals(Reason.DAMAGED, reason { ProjectArchive.prepareJson("""{"version":2}""", "n", 1L, emptyList()) { true } })   // нет id
        assertEquals(Kind.UNKNOWN, ProjectArchive.detect("hello".toByteArray()))
        assertEquals(Kind.UNKNOWN, ProjectArchive.detect(ByteArray(0)))
        // ZIP без project.json
        val staging = tmp()
        ProjectArchive.unzip(zip(listOf("photo.png" to ByteArray(10))).inputStream(), staging)
        assertNull(ProjectArchive.locateRoot(staging))
        assertEquals(Reason.NOT_PROJECT, reason { ProjectArchive.prepare(staging, "n", 1L, emptyList()) })
        staging.deleteRecursively()
        // обрезанный архив (недокачанный файл)
        val big = zip(listOf("project.json" to ProjectJson.encode(miniProject()).toByteArray(), "assets/big.bin" to java.util.Random(1).let { r -> ByteArray(200_000).also { r.nextBytes(it) } }))
        val cut = big.copyOf(big.size * 6 / 10)
        val st2 = tmp()
        assertEquals(Reason.DAMAGED, reason { ProjectArchive.unzip(cut.inputStream(), st2) })
        // лимиты (защита от «ZIP-бомбы»)
        assertEquals(Reason.TOO_LARGE, reason { ProjectArchive.unzip(tsproj(miniProject()).inputStream(), tmp(), maxEntries = 2) })
        assertEquals(Reason.TOO_LARGE, reason { ProjectArchive.unzip(big.inputStream(), tmp(), maxBytes = 1000) })
        st2.deleteRecursively()
        // у каждой причины - своё непустое сообщение
        assertEquals(Reason.values().size, Reason.values().map { it.message }.toSet().size)
        assertTrue(ProjectArchive.isNoSpace(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertFalse(ProjectArchive.isNoSpace(java.io.IOException("other")))
    }

    @Test fun psdHeaderGivesCanvas() {
        val head = ByteArray(26)
        "8BPS".toByteArray().copyInto(head)
        head[5] = 1; head[13] = 3
        fun be(o: Int, v: Int) { head[o] = (v ushr 24).toByte(); head[o + 1] = (v ushr 16).toByte(); head[o + 2] = (v ushr 8).toByte(); head[o + 3] = v.toByte() }
        be(14, 1080); be(18, 1920)
        assertEquals(Kind.PSD, ProjectArchive.detect(head))
        assertEquals(1920 to 1080, ProjectArchive.psdSize(head))
        assertEquals(4096 to 1024, ProjectArchive.psdCanvas(8000, 2000))
        assertEquals(1920 to 1080, ProjectArchive.psdCanvas(1921, 1081))
        assertEquals(64 to 64, ProjectArchive.psdCanvas(10, 10))
        assertNull(ProjectArchive.psdSize(ByteArray(10)))
    }

    @Test fun namesJunkAndManifest() {
        assertEquals("A", ProjectArchive.uniqueName("A", listOf("B")))
        assertEquals("A (3)", ProjectArchive.uniqueName("A", listOf("A", "A (2)")))
        assertEquals("Проект", ProjectArchive.uniqueName("  ", emptyList()))
        assertTrue(ProjectArchive.isJunk("__MACOSX/assets/._a.png"))
        assertTrue(ProjectArchive.isJunk("assets/.DS_Store"))
        assertTrue(ProjectArchive.isJunk("Thumbs.db"))
        assertFalse(ProjectArchive.isJunk("assets/m100000.png"))
        val m = ProjectArchive.manifest(6, "Мини")
        assertTrue(m.contains("\"format\":2") || m.contains("\"format\": 2"))
        assertNotNull(ProjectArchive.versionOf(ProjectJson.encode(miniProject())))
        assertEquals(ProjectJson.VERSION, ProjectArchive.versionOf(ProjectJson.encode(miniProject())))   // пакет 25: смешанный порядок - v8
        assertTrue(ProjectArchive.referencedFiles(miniProject()).none { it.isEmpty() })
    }

    @Test fun projectJsonFailureIsNotSilentlyIgnored() {
        try {
            ProjectArchive.prepareJson("""{"version":${ProjectJson.VERSION + 1},"id":"x"}""", "n", 1L, emptyList()) { true }
            fail("newer version must be rejected")
        } catch (e: ProjectArchive.ImportException) {
            assertEquals(Reason.NEWER_VERSION, e.reason)
            assertTrue(e.message!!.contains("новее") || e.message!!.contains("более новой"))
        }
    }
}
