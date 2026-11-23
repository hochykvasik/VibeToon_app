package com.vibetoon

import com.vibetoon.domain.render.FrameTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Метрики производительности: скользящее окно времени кадра. */
class FrameTimerTest {
    @Test fun averageMaxPercentile() {
        val t = FrameTimer(10)
        for (ms in listOf(10f, 20f, 30f, 40f)) t.add(ms)
        assertEquals(4, t.count)
        assertEquals(25f, t.average(), 1e-4f)
        assertEquals(40f, t.max(), 0f)
        assertEquals(30f, t.percentile(0.95f), 0f)   // (n-1)*p = 2.85 -> индекс 2
        assertEquals(10f, t.percentile(0f), 0f)
        assertEquals(40f, t.percentile(1f), 0f)
    }

    @Test fun overBudgetShare() {
        val t = FrameTimer(8)
        for (ms in listOf(10f, 40f, 12f, 50f)) t.add(ms)
        assertEquals(0.5f, t.overBudget(1000f / 30f), 1e-4f)
    }

    @Test fun ringKeepsOnlyLastWindow() {
        val t = FrameTimer(3)
        for (ms in listOf(100f, 100f, 1f, 2f, 3f)) t.add(ms)
        assertEquals(3, t.count)
        assertEquals(2f, t.average(), 1e-4f)
        assertEquals(3f, t.max(), 0f)
    }

    @Test fun ignoresBadSamplesAndClears() {
        val t = FrameTimer(4)
        t.add(Float.NaN); t.add(-1f); t.add(Float.POSITIVE_INFINITY)
        assertEquals(0, t.count)
        assertEquals(0f, t.average(), 0f); assertEquals(0f, t.percentile(0.5f), 0f); assertEquals(0f, t.overBudget(1f), 0f)
        t.add(5f); t.clear()
        assertEquals(0, t.count)
    }
}
