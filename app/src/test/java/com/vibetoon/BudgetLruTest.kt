package com.vibetoon

import com.vibetoon.domain.perf.BudgetLru
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Спринт 31: LRU-кэш декодированных рисунков с бюджетом в байтах. */
class BudgetLruTest {
    private class Blob(val bytes: Long)

    private fun lru(budget: Long, evicted: MutableList<String> = ArrayList()) =
        BudgetLru<String, Blob>(budget, { it.bytes }) { k, _ -> synchronized(evicted) { evicted += k } }

    @Test fun evictsLeastRecentlyUsedFirst() {
        val ev = ArrayList<String>()
        val c = lru(100, ev)
        assertTrue(c.put("a", Blob(40)))
        assertTrue(c.put("b", Blob(40)))
        c.get("a")                       // a свежее b
        assertTrue(c.put("c", Blob(40)))  // 120 > 100: вытесняется b
        assertEquals(listOf("b"), ev)
        assertNull(c.get("b"))
        assertEquals(80L, c.sizeBytes)
        assertEquals(listOf("a", "c"), c.keys())
    }

    @Test fun refusesItemsLargerThanHalfBudget() {
        val c = lru(100)
        assertFalse(c.put("big", Blob(51)))
        assertTrue(c.put("ok", Blob(50)))
        assertFalse(c.put("zero", Blob(0)))
        assertEquals(1, c.count())
        assertFalse(lru(0).put("x", Blob(1)))   // нулевой бюджет - кэш выключен
    }

    @Test fun replacingKeyKeepsAccountingExact() {
        val ev = ArrayList<String>()
        val c = lru(100, ev)
        c.put("a", Blob(30))
        c.put("a", Blob(20))
        assertEquals(20L, c.sizeBytes)
        assertEquals(listOf("a"), ev)   // прежнее значение отдано на освобождение
        assertEquals(1, c.count())
    }

    @Test fun removeIfDropsAllVersionsOfFile() {
        val c = lru(1000)
        c.put("f.png@1", Blob(10)); c.put("f.png@2", Blob(10)); c.put("g.png@1", Blob(10))
        c.removeIf { it.startsWith("f.png") }
        assertEquals(listOf("g.png@1"), c.keys())
        assertEquals(10L, c.sizeBytes)
    }

    @Test fun resizeTrimsImmediatelyAndClearEmpties() {
        val c = lru(100)
        c.put("a", Blob(30)); c.put("b", Blob(30)); c.put("c", Blob(30))
        c.resize(50)
        assertEquals(listOf("c"), c.keys())
        c.resize(0)
        assertEquals(0, c.count()); assertEquals(0L, c.sizeBytes)
        c.resize(100); c.put("d", Blob(10)); c.clear()
        assertEquals(0, c.count()); assertEquals(0L, c.sizeBytes)
    }

    @Test fun hitsAndMissesCountedContainsDoesNotTouchOrder() {
        val c = lru(100)
        c.put("a", Blob(10)); c.put("b", Blob(10))
        assertTrue(c.contains("a"))
        assertEquals(listOf("a", "b"), c.keys())   // contains не делает a свежим
        c.get("a"); c.get("zz")
        assertEquals(1L, c.hits()); assertEquals(1L, c.misses())
    }

    @Test fun concurrentDecodersKeepBudget() {
        val c = lru(1000)
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { t -> pool.execute { repeat(500) { i -> c.put("k${t}_${i % 50}", Blob(30)); c.get("k${t}_${(i + 7) % 50}") } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        assertTrue(c.sizeBytes <= 1000)
        assertEquals(c.count() * 30L, c.sizeBytes)
    }
}
