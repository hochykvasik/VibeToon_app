package com.vibetoon

import com.vibetoon.domain.anim.PlaybackMath
import com.vibetoon.domain.anim.SnapMath
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorMath
import com.vibetoon.domain.vector.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorLayerTest {
    @Test fun tapWithoutMovementMakesNoShape() {
        assertNull(VectorMath.fromGesture(VecKind.PATH, listOf(10f, 10f, 10.5f, 10.5f), 0xFF000000L, 0L, 8f))
        assertNull(VectorMath.fromGesture(VecKind.LINE, listOf(10f, 10f, 11f, 11f), 0xFF000000L, 0L, 8f))
        assertNull(VectorMath.fromGesture(VecKind.RECT, listOf(10f, 10f, 10f, 80f), 0xFF000000L, 0L, 8f))
    }

    @Test fun gestureKindsKeepTheirPoints() {
        val line = VectorMath.fromGesture(VecKind.LINE, listOf(0f, 0f, 5f, 5f, 40f, 30f), 0xFFFF0000L, 0x66FF0000L, 6f)!!
        assertEquals(listOf(0f, 0f, 40f, 30f), line.pts)
        assertEquals(0L, line.fill)   // у линии заливки нет
        val rect = VectorMath.fromGesture(VecKind.RECT, listOf(10f, 10f, 90f, 60f), 0xFF00FF00L, 0x6600FF00L, 4f)!!
        assertEquals(0x6600FF00L, rect.fill)
        val path = VectorMath.fromGesture(VecKind.PATH, listOf(0f, 0f, 0.5f, 0.5f, 30f, 30f, 60f, 10f), 0xFF000000L, 0L, 8f)!!
        assertEquals(VecKind.PATH, path.kind)
        assertTrue(path.pts.size >= 4)
    }

    @Test fun simplifyKeepsEndpointsAndDropsNoise() {
        val pts = listOf(0f, 0f, 0.5f, 0f, 1f, 0f, 10f, 0f, 20f, 0f)
        val s = VectorMath.simplify(pts, 2f)
        assertEquals(0f, s[0], 0f); assertEquals(20f, s[s.size - 2], 0f)
        assertTrue(s.size < pts.size)
    }

    @Test fun undoLastAndClear() {
        val a = VectorShape(VecKind.LINE, listOf(0f, 0f, 10f, 10f))
        val b = VectorShape(VecKind.ELLIPSE, listOf(0f, 0f, 20f, 20f))
        val list = VectorMath.withShape(VectorMath.withShape(emptyList(), a), b)
        assertEquals(listOf(a), VectorMath.withoutLast(list))
        assertTrue(VectorMath.withoutLast(emptyList()).isEmpty())
    }

    @Test fun vectorLayerSurvivesJson() {
        val shapes = listOf(
            VectorShape(VecKind.PATH, listOf(1f, 2f, 3f, 4f, 5f, 9f), 0xFFAA0000L, 0L, 5f),
            VectorMath.preset("STAR", 100f, 100f, 80f, 0xFF112233L, 0xFF445566L, 3f)
        )
        val item = MediaItem(100001, MediaKind.IMAGE, "Вектор 1", "assets/m100001.png", 100, 50, 0f, 0f, 100f, 50f, vector = true, vectors = shapes)
        val bg = MediaItem(100002, MediaKind.IMAGE, "Фон 1", "assets/m100002.png", 100, 50, 0f, 0f, 100f, 50f, background = true)
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(ProjectMeta("p", "n", 100, 50), media = listOf(item, bg)))).getOrThrow()
        val v = back.media.first { it.id == 100001 }
        assertTrue(v.vector)
        assertEquals(shapes, v.vectors)
        assertTrue(back.media.first { it.id == 100002 }.background)
    }

    @Test fun loopAndMagnetSurviveJson() {
        val def = ProjectJson.decode(ProjectJson.encode(ProjectData(ProjectMeta("p", "n", 10, 10)))).getOrThrow().meta
        assertTrue(def.loop); assertFalse(def.magnet)
        val m = ProjectMeta("p", "n", 10, 10, loop = false, magnet = true)
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(m))).getOrThrow().meta
        assertFalse(back.loop); assertTrue(back.magnet)
    }

    @Test fun playbackLoopsOrStopsAtLastFrame() {
        val sec = 1_000_000_000L
        // 24 fps, 48 кадров: через 3 с цикл возвращает на кадр 24, без цикла держится на последнем (47)
        assertEquals(24, PlaybackMath.frameAt(3 * sec, 24, 0, 48, loop = true))
        assertEquals(47, PlaybackMath.frameAt(3 * sec, 24, 0, 48, loop = false))
        assertEquals(10, PlaybackMath.frameAt(0, 24, 10, 48, loop = false))
    }

    @Test fun magnetSnapsWithinThresholdOnly() {
        val targets = listOf(0, 30, 90)
        assertEquals(30, SnapMath.snap(32, targets, 3))
        assertEquals(40, SnapMath.snap(40, targets, 3))
        assertEquals(0, SnapMath.snap(2, targets, 3))
        assertEquals(5, SnapMath.snap(5, targets, 0))   // порог 0 - магнит выключен
        assertEquals(30, SnapMath.snap(31, listOf(30, 32), 2))   // при равном расстоянии - левая цель
        assertNotNull(SnapMath)
    }
}
