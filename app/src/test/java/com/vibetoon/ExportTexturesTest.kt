package com.vibetoon

import com.vibetoon.domain.export.ExportTextures
import com.vibetoon.domain.export.ExportVideo
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.VideoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пакет 29: картинки кадров экспорта (видео, покадровые рисунки, текст): каждый кадр видео-слоя получает свой кадр
 * источника, картинка применяется перед отрисовкой именно своего кадра (сцена N + 1 собирается, пока рисуется N).
 */
class ExportTexturesTest {
    private class Img(val tag: String) { var released = false }

    private class Gpu {
        val shown = HashMap<Int, String>()
        val log = ArrayList<String>()
        var accept = true
    }

    private fun textures(gpu: Gpu, released: MutableList<Img>) = ExportTextures<Img>(
        upload = { id, b, _ -> gpu.log += "$id=${b.tag}"; if (gpu.accept) { gpu.shown[id] = b.tag; true } else false },
        release = { it.released = true; released += it }
    )

    @Test fun prefetchedFrameDoesNotLeakIntoCurrentFrame() {
        val gpu = Gpu(); val rel = ArrayList<Img>()
        val t = textures(gpu, rel)
        // сборка кадров 0 и 1 раньше отрисовки кадра 0 (prefetch MP4)
        val b0 = t.batch(listOf(t.plan(7, "a", 0L) { Img("a") }))
        val b1 = t.batch(listOf(t.plan(7, "b", 0L) { Img("b") }))
        assertTrue(gpu.shown.isEmpty())          // сборка в GPU ничего не ставит
        assertEquals(1, t.commit(b0)); assertEquals("a", gpu.shown[7])   // кадр 0 рисуется со своим рисунком
        assertEquals(1, t.commit(b1)); assertEquals("b", gpu.shown[7])
        assertEquals(2, rel.size); assertTrue(rel.all { it.released })
        assertEquals(0, t.commit(b1))   // повтор кадра (прерванная отрисовка) - ничего
    }

    @Test fun sameKeyIsNotDecodedTwice() {
        val gpu = Gpu(); val rel = ArrayList<Img>()
        val t = textures(gpu, rel)
        var decodes = 0
        val load = { decodes++; Img("x") }
        val i0 = t.plan(1, "x", 0L, load)
        val i1 = t.plan(1, "x", 0L, load)
        assertEquals(1, decodes)
        assertTrue(i0.prepared); assertFalse(i1.prepared)
        t.commit(t.batch(listOf(i0))); t.commit(t.batch(listOf(i1)))
        assertEquals(1, decodes); assertEquals(1, t.uploads)
        assertEquals("x", t.committedKey(1))
    }

    @Test fun skippedPreparationIsLoadedLateWhenTextureDiffers() {
        // кадр 2 = кадр 1 по ключу, но кадр 1 так и не нарисовали (сборка ушла вперёд) - при применении догрузить
        val gpu = Gpu(); val rel = ArrayList<Img>()
        val t = textures(gpu, rel)
        val a = t.batch(listOf(t.plan(1, "a", 0L) { Img("a") }))
        val b = t.batch(listOf(t.plan(1, "b", 0L) { Img("b") }))
        val b2 = t.batch(listOf(t.plan(1, "b", 0L) { Img("b2") }))
        t.commit(a)
        t.discard(b)   // кадр b отменён
        assertEquals(1, t.commit(b2))
        assertEquals("b2", gpu.shown[1])
        assertEquals(1, t.lateLoads)
    }

    @Test fun rejectedUploadIsRetriedOnNextFrame() {
        val gpu = Gpu(); val rel = ArrayList<Img>()
        val t = textures(gpu, rel)
        gpu.accept = false
        t.commit(t.batch(listOf(t.plan(2, "k", 0L) { Img("k1") })))
        assertNull(t.committedKey(2))
        gpu.accept = true
        assertEquals(1, t.commit(t.batch(listOf(t.plan(2, "k", 0L) { Img("k2") }))))   // тот же ключ, но в GPU его нет
        assertEquals("k", t.committedKey(2))
        assertTrue(rel.all { it.released })
    }

    @Test fun failedDecodeKeepsPreviousPicture() {
        val gpu = Gpu(); val rel = ArrayList<Img>()
        val t = textures(gpu, rel)
        t.commit(t.batch(listOf(t.plan(3, "a", 0L) { Img("a") })))
        assertEquals(0, t.commit(t.batch(listOf(t.plan(3, "b", 0L) { null }))))
        assertEquals(0, t.commit(t.batch(listOf(t.plan(3, "c", 0L) { error("decoder") }))))
        assertEquals("a", gpu.shown[3])
    }

    @Test fun discardReleasesPreparedPictures() {
        val gpu = Gpu(); val rel = ArrayList<Img>()
        val t = textures(gpu, rel)
        val b = t.batch(listOf(t.plan(1, "a", 0L) { Img("a") }, t.plan(2, "b", 0L) { Img("b") }))
        t.discard(b)
        assertEquals(2, rel.size)
        assertEquals(0, t.commit(b))   // после отмены не применяется
        assertTrue(gpu.shown.isEmpty())
        assertTrue(b.isApplied)
    }

    @Test fun videoLayerGetsItsOwnSourceFramePerExportFrame() {
        // видео-слой 24 fps, клип с начала: каждый кадр экспорта - свой момент источника (раньше один кадр на весь ролик)
        val clip = Clip(0, 48)
        val keys = (0 until 24).map { f -> ExportVideo.Key("v.mp4", VideoTime.sourceMs(clip, f.toFloat(), 24)) }
        assertEquals(24, keys.toSet().size)
        assertNotEquals(keys.first(), keys.last())
        val gpu = Gpu(); val rel = ArrayList<Img>()
        val t = textures(gpu, rel)
        var decodes = 0
        for (k in keys) t.commit(t.batch(listOf(t.plan(5, k, 0L) { decodes++; Img("${k.timeMs}") })))
        assertEquals(24, decodes)
        assertEquals("${keys.last().timeMs}", gpu.shown[5])
    }

    @Test fun decodeSizeBoundedByExportAndSource() {
        assertEquals(1920 to 1080, ExportVideo.decodeSize(3840, 2160, 1920, 1080))
        assertEquals(1280 to 720, ExportVideo.decodeSize(1280, 720, 1920, 1080))   // не больше источника
        assertEquals(1080 to 1920, ExportVideo.decodeSize(2160, 3840, 1080, 1920))
        val (w, h) = ExportVideo.decodeSize(0, 0, 0, 0)
        assertTrue(w >= 2 && h >= 2)
    }
}
