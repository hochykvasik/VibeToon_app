package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.KeyList
import com.vibetoon.domain.anim.WarpMath
import com.vibetoon.domain.project.AssetCatalog
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.Filmstrip
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.SequenceImport
import com.vibetoon.domain.project.VideoTime
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 11: плёнка и звук видео на таймлайне, список ключей, секвенции картинок, папки библиотеки ассетов. */
class Package11MediaKeyListTest {

    // ---- время источника видео и плёнка ----

    @Test fun videoTimeFollowsClipRetiming() {
        val c = Clip(10, 40)
        assertEquals(0L, VideoTime.sourceMs(c, 10f, 30))
        assertEquals(500L, VideoTime.sourceMs(c, 25f, 30))                       // 15 кадров при 30 fps
        assertEquals(1000L, VideoTime.sourceMs(c.copy(speed = 2f), 25f, 30))      // 2x - вдвое дальше
        assertEquals(VideoTime.sourceMs(c, 39f, 30), VideoTime.sourceMs(c.copy(reverse = true), 10f, 30))   // реверс: начало = конец
        assertEquals(0L, VideoTime.sourceMs(c.copy(freeze = true), 30f, 30))     // стоп-кадр
        assertEquals(1000L, VideoTime.sourceMs(c.copy(offset = 30), 10f, 30))    // смещение после разрезания
        assertEquals(0L, VideoTime.sourceMs(c, 0f, 30))                          // до клипа - его первый кадр
    }

    @Test fun filmstripTilesCoverOnlyTheVisibleClip() {
        val c = Clip(0, 100)
        val all = Filmstrip.tiles(c, 30, frameW = 10f, tileW = 100f, vis0 = 0f, vis1 = 1000f, durationMs = 10_000)
        assertEquals(10, all.size)                                               // 1000 px клипа / 100 px плитки
        assertEquals(listOf(0f, 100f, 200f), all.take(3).map { it.x })
        assertEquals(166L, Filmstrip.quantize(166L, 1))
        assertEquals(100L, all[0].sourceMs)                                      // середина первой плитки: 5 кадров = 166 мс -> 100
        val vis = Filmstrip.tiles(c, 30, 10f, 100f, vis0 = 350f, vis1 = 520f, durationMs = 10_000)
        assertEquals(listOf(300f, 400f, 500f), vis.map { it.x })                 // плитки привязаны к началу клипа, а не к прокрутке
        val tail = Filmstrip.tiles(Clip(0, 95), 30, 10f, 100f, 900f, 2000f, 10_000)
        assertEquals(50f, tail.single().width)                                   // последняя плитка обрезана по концу клипа
        assertTrue(Filmstrip.tiles(c, 30, 10f, 100f, 1200f, 1500f, 10_000).isEmpty())   // клип вне экрана
        val short = Filmstrip.tiles(c, 30, 10f, 100f, 0f, 1000f, durationMs = 1000)
        assertTrue(short.all { it.sourceMs <= 999 })                             // не дальше конца файла
        assertTrue(Filmstrip.tiles(c, 30, 10f, 0f, 0f, 1000f, 1000).isEmpty())
    }

    // ---- звук видео: модель и сохранение ----

    @Test fun videoHasSoundTrackAndMuteIsSaved() {
        val video = MediaItem(100_001, MediaKind.VIDEO, "v", "assets/v.mp4", muted = true)
        val audio = MediaItem(100_002, MediaKind.AUDIO, "a", "assets/a.mp3")
        val image = MediaItem(100_003, MediaKind.IMAGE, "i", "assets/i.png")
        assertTrue(video.hasSoundTrack); assertTrue(audio.hasSoundTrack); assertFalse(image.hasSoundTrack)
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(ProjectMeta("p", "n", 100, 100), media = listOf(video, audio)))).getOrThrow()
        assertTrue(back.media.first { it.id == 100_001 }.muted)
        assertFalse(back.media.first { it.id == 100_002 }.muted)
    }

    // ---- список ключей ----

    @Test fun keyListMergesLayerTracksPerFrame() {
        var a = Animation()
        a = a.withKey(2, 0, LayerTransform(dx = 10f, rotation = Math.PI.toFloat() / 2f))
        a = a.withKey(2, 12, LayerTransform(dx = 20f))
        a = a.withKey(WarpMath.pointId(2, 3), 12, LayerTransform(dx = 1f))
        a = a.withKey(WarpMath.pointId(2, 3), 20, LayerTransform(dx = 2f))
        val rows = KeyList.rows(2, a.tracks)
        assertEquals(listOf(0, 12, 20), rows.map { it.frame })
        assertEquals(listOf(1, 2, 1), rows.map { it.tracks })
        assertNull(rows[2].transform)                                            // на кадре 20 только ключ деформации
        assertTrue(KeyList.summary(rows[0].transform!!).contains("R 90°"))
        val eased = KeyList.withEasing(a, 2, 12, EasingType.EASE_IN)
        assertEquals(EasingType.EASE_IN, eased.easingAt(2, 12))
        assertEquals(EasingType.EASE_IN, eased.easingAt(WarpMath.pointId(2, 3), 12))   // ромб = все дорожки слоя на кадре
        assertEquals(EasingType.LINEAR, eased.easingAt(2, 0))
        val all = KeyList.withEasingAll(a, 2, EasingType.STEP)
        assertTrue(KeyList.rows(2, all.tracks).all { it.easing == EasingType.STEP && !it.custom })
    }

    @Test fun keyListOfBoneUsesOnlyItsTrack() {
        val bone = 200_001
        var a = Animation().withKey(bone, 4, LayerTransform(rotation = 0.5f))
        a = a.withCurve(bone, 4, listOf(0.1f, 0.2f, 0.3f, 0.4f))
        val rows = KeyList.rows(bone, a.tracks, isBone = true)
        assertEquals(listOf(4), rows.map { it.frame })
        assertTrue(rows[0].custom)
        val eased = KeyList.withEasing(a, bone, 4, EasingType.EASE_OUT, isBone = true)
        assertEquals(EasingType.EASE_OUT, eased.easingAt(bone, 4))
        assertNull(eased.curveAt(bone, 4))                                       // своя кривая сбрасывается
        assertTrue(KeyList.rows(7, a.tracks).isEmpty())
    }

    // ---- секвенции картинок ----

    @Test fun sequenceIsOrderedNaturally() {
        val names = listOf("walk_10.png", "walk_2.png", "Walk_1.png", "walk_02b.png")
        assertEquals(listOf("Walk_1.png", "walk_2.png", "walk_02b.png", "walk_10.png"), SequenceImport.order(names).map { names[it] })
        assertEquals(listOf(FrameCell(0, "a"), FrameCell(2, "b"), FrameCell(4, "c")), SequenceImport.cells(listOf("a", "b", "c"), hold = 2))
        assertEquals(listOf(0, 1), SequenceImport.cells(listOf("a", "b"), hold = 0).map { it.start })
        assertEquals("walk", SequenceImport.title("walk_001.png"))
        assertEquals("frame", SequenceImport.title("frame 12.jpg"))
        assertEquals("0001", SequenceImport.title("0001.png"))                  // имя из одних цифр не пропадает
    }

    // ---- библиотека ассетов: папки ----

    @Test fun assetLibraryFolders() {
        val used = mapOf("m100001_a.png" to "Голова", "m100002_v.mp4" to "Клип", "m100003_s.mp3" to "Музыка")
        val files = listOf("m100001_a.png", "m100002_v.mp4", "m100003_s.mp3", "m100004_old.mp4", "source.psd", "m100003_s.mp3.wave", "st2_1_0.png")
        val lib = AssetCatalog.library(files, used, emptySet())
        assertEquals(setOf("Голова", "Клип", "Музыка", "source.psd", "m100004_old.mp4"), lib.map { it.title }.toSet())
        assertEquals(listOf("Клип", "m100004_old.mp4"), AssetCatalog.folder(lib, AssetCatalog.Type.VIDEO).map { it.title })
        assertEquals(AssetCatalog.Kind.UNUSED, lib.first { it.file == "m100004_old.mp4" }.kind)
        assertEquals(AssetCatalog.Type.AUDIO, lib.first { it.title == "Музыка" }.type)
        assertEquals(AssetCatalog.Type.PSD, lib.first { it.file == "source.psd" }.type)
        assertEquals(2, AssetCatalog.serviceCount(files, used, emptySet()))     // кэш волны и миниатюра маркера
        assertNull(AssetCatalog.typeOf("x.wave"))
    }
}
