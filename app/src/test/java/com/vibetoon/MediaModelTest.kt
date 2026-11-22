package com.vibetoon

import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMath
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaModelTest {
    private val meta = ProjectMeta("p1", "Проект", 1080, 1920)

    @Test fun mediaRoundTripsThroughJson() {
        val items = listOf(
            MediaItem(MEDIA_ID_BASE, MediaKind.IMAGE, "cat.png", "assets/m100000.png", 3000, 2000, 90f, 700f, 900f, 600f),
            MediaItem(MEDIA_ID_BASE + 1, MediaKind.VIDEO, "clip.mp4", "assets/m100001.mp4", 1920, 1080, 0f, 0f, 864f, 486f, 4200L),
            MediaItem(MEDIA_ID_BASE + 2, MediaKind.AUDIO, "song.mp3", "assets/m100002.mp3", durationMs = 61000L)
        )
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, media = items))).getOrThrow()
        assertEquals(items, back.media)
    }

    @Test fun unknownMediaKindIsSkippedNotFatal() {
        val text = """{"id":"x","media":[{"id":100000,"kind":"HOLOGRAM"},{"id":100001,"kind":"AUDIO","name":"a","file":"f"}]}"""
        val media = ProjectJson.decode(text).getOrThrow().media
        assertEquals(1, media.size)
        assertEquals(MediaKind.AUDIO, media[0].kind)
    }

    @Test fun mediaWithoutIdIsSkipped() {
        val text = """{"id":"x","media":[{"kind":"IMAGE","name":"no id"}]}"""
        assertTrue(ProjectJson.decode(text).getOrThrow().media.isEmpty())
    }

    @Test fun oldProjectsWithoutMediaStillLoad() {
        assertTrue(ProjectJson.decode("""{"id":"old","width":720,"height":1280}""").getOrThrow().media.isEmpty())
    }

    // ---- размещение на холсте ----

    @Test fun bigImageIsShrunkToEightyPercentAndCentered() {
        val r = ProjectMath.fitInside(4000, 2000, 1000, 1000)
        assertEquals(800f, r[2], 1e-3f)     // ширина 80% холста
        assertEquals(400f, r[3], 1e-3f)     // пропорции сохранены
        assertEquals(100f, r[0], 1e-3f)     // по центру
        assertEquals(300f, r[1], 1e-3f)
    }

    @Test fun smallImageIsNotUpscaled() {
        val r = ProjectMath.fitInside(100, 50, 1000, 1000)
        assertEquals(100f, r[2], 1e-3f)
        assertEquals(50f, r[3], 1e-3f)
        assertEquals(450f, r[0], 1e-3f)
    }

    @Test fun tallImageFitsByHeight() {
        val r = ProjectMath.fitInside(1000, 4000, 1000, 1000)
        assertEquals(800f, r[3], 1e-3f)
        assertEquals(200f, r[2], 1e-3f)
    }

    @Test fun audioLengthInFrames() {
        assertEquals(120, ProjectMath.framesFor(5000L, 24))
        assertEquals(1, ProjectMath.framesFor(10L, 24))   // не меньше одного кадра
    }

    // ---- сцена ----

    private fun leaf(id: Int, x: Int, y: Int, w: Int, h: Int, parent: Int = -1) =
        LayerInfo(id, "n$id", x, y, w, h, 1f, true, 0, parent, "norm", false)

    @Test fun mediaLayersAreNotFittedLikeThePsd() {
        // PSD вписывается матрицей масштаба 0.5; медиа-слой стоит в координатах холста как есть
        val fit = Affine2D.scale(0.5f, 0.5f)
        val layers = listOf(leaf(0, 100, 100, 200, 200), leaf(MEDIA_ID_BASE, 40, 60, 300, 200))
        val s = SceneBuilder.build(layers, 1000, 1000, emptyMap()).let {
            SceneBuilder.buildTree(com.vibetoon.domain.model.SceneTree.build(layers), 1000, 1000, emptyMap(), fit)
        }
        val ids = s.ops.toList().chunked(4).map { it[1] }
        assertEquals(listOf(0, MEDIA_ID_BASE), ids)                     // медиа поверх PSD
        // PSD-слой: x = 100 * 0.5; медиа: x = 40 (без масштаба вписывания)
        assertEquals(50f, s.params[4], 1e-3f)
        assertEquals(40f, s.params[8 + 4], 1e-3f)
        assertEquals(300f, s.params[8 + 0], 1e-3f)                      // ширина медиа не уменьшена
    }
}
