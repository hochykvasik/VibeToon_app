package com.vibetoon

import com.vibetoon.domain.project.VideoSymbolPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 22: видео как символ - моменты кадров и размер декодирования. */
class Package22VideoSymbolTest {
    @Test fun oneFramePerProjectFrame() {
        val p = VideoSymbolPlan.plan(1000, 24)
        assertEquals(24, p.frames); assertFalse(p.cut)
        assertEquals(0L, p.timesMs[0]); assertEquals(41L, p.timesMs[1]); assertEquals(958L, p.timesMs[23])
        assertTrue(p.timesMs.zipWithNext().all { (a, b) -> b > a })
        // хвост короче кадра даёт ещё один кадр, моменты не выходят за конец видео
        val q = VideoSymbolPlan.plan(1010, 24)
        assertEquals(25, q.frames); assertTrue(q.timesMs.last() <= 1009L)
    }

    @Test fun longVideoIsCutAndEmptyGivesOneFrame() {
        val p = VideoSymbolPlan.plan(60_000, 30)
        assertEquals(VideoSymbolPlan.MAX_FRAMES, p.frames); assertTrue(p.cut)
        val z = VideoSymbolPlan.plan(0, 24)
        assertEquals(1, z.frames); assertEquals(0L, z.timesMs[0]); assertFalse(z.cut)
        assertEquals(1, VideoSymbolPlan.plan(500, 0).frames)   // fps 0 считается за 1
    }

    @Test fun decodeSizeKeepsAspectAndRotation() {
        assertEquals(1280 to 720, VideoSymbolPlan.decodeSize(3840, 2160, 1280))
        assertEquals(640 to 480, VideoSymbolPlan.decodeSize(640, 480, 1280))   // меньше лимита - без увеличения
        assertEquals(2 to 2, VideoSymbolPlan.decodeSize(0, 100, 1280))
        assertEquals(1080 to 1920, VideoSymbolPlan.oriented(1920, 1080, 90))
        assertEquals(1920 to 1080, VideoSymbolPlan.oriented(1920, 1080, 180))
        assertEquals(1080 to 1920, VideoSymbolPlan.oriented(1920, 1080, -90))
    }
}
