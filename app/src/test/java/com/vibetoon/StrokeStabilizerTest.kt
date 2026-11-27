package com.vibetoon

import com.vibetoon.domain.input.StrokeStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/** Stylus sprint: стабилизация 1–10 - сырой штрих на 1, сглаживание растёт с уровнем, хвост догоняет перо. */
class StrokeStabilizerTest {
    private fun run(level: Int, pts: List<Pair<Float, Float>>, density: Float = 1f): List<StrokeStabilizer.Sample> {
        val s = StrokeStabilizer(level, density)
        val out = ArrayList<StrokeStabilizer.Sample>()
        out += s.begin(pts[0].first, pts[0].second, 0.5f)
        for (p in pts.drop(1)) out += s.add(p.first, p.second, 0.5f)
        out += s.finish()
        return out
    }

    /** Прямая с дрожанием руки ±3 px. */
    private val shaky = (0..200).map { i -> i * 2f to 100f + 3f * sin(i * 1.7f) }

    private fun jitter(out: List<StrokeStabilizer.Sample>): Float {
        val inner = out.filter { it.x in 40f..360f }
        return inner.map { abs(it.y - 100f) }.average().toFloat()
    }

    @Test fun levelOneIsRawStroke() {
        val out = run(1, shaky)
        assertEquals(shaky.size, out.size)
        shaky.zip(out).forEach { (p, s) -> assertEquals(p.first, s.x, 0f); assertEquals(p.second, s.y, 0f) }
        assertTrue(StrokeStabilizer(-5).passthrough)
        assertEquals(10, StrokeStabilizer(99).level)
    }

    @Test fun smoothingGrowsWithLevel() {
        val raw = jitter(run(1, shaky))
        val mid = jitter(run(4, shaky))
        val strong = jitter(run(10, shaky))
        assertTrue("4 сглаживает: $raw -> $mid", mid < raw * 0.7f)
        assertTrue("10 сильнее 4: $mid -> $strong", strong < mid)
        assertTrue("10 почти убирает дрожание: $strong", strong < 0.6f)
    }

    @Test fun releaseNeverLosesTheEnding() {
        for (level in 1..10) {
            val out = run(level, shaky)
            val last = out.last()
            assertEquals("уровень $level: конец ровно в точке отпускания", shaky.last().first, last.x, 0f)
            assertEquals(shaky.last().second, last.y, 0f)
            assertTrue("хвост короткий", out.size <= shaky.size * 3 + StrokeStabilizer.MAX_TAIL + 1)
        }
        // точка без движения (тап) не создаёт лишнего хвоста
        val s = StrokeStabilizer(10); s.begin(5f, 5f, 1f)
        assertTrue(s.finish().isEmpty())
    }

    @Test fun lineDoesNotChaseForeverWhilePenRests() {
        val s = StrokeStabilizer(10, 2f)
        s.begin(0f, 0f, 1f)
        for (i in 1..50) s.add(i * 6f, 0f, 1f)
        val moving = s.lag()
        assertTrue("при движении есть задержка: $moving", moving > s.ropeRadius)
        repeat(40) { s.add(300f, 0f, 1f) }   // перо стоит, события продолжают приходить
        assertTrue("задержка не больше нити: ${s.lag()} / ${s.ropeRadius}", s.lag() <= s.ropeRadius + 0.5f)
    }

    @Test fun strengthDoesNotDependOnEventRate() {
        // одна и та же линия при 60 Гц (шаг 16 px) и 240 Гц (шаг 4 px): задержка к концу почти одинакова
        fun lagAt(step: Float): Float {
            val s = StrokeStabilizer(8); s.begin(0f, 0f, 1f)
            var x = 0f
            while (x < 400f) { x += step; s.add(x, 0f, 1f) }
            return s.lag()
        }
        val slow = lagAt(16f); val fast = lagAt(4f)
        assertTrue("$slow vs $fast", abs(slow - fast) < 3f)
    }

    @Test fun pressureFollowsWithoutRopeLag() {
        val s = StrokeStabilizer(10)
        s.begin(0f, 0f, 0.1f)
        val out = s.add(1f, 0f, 1f) + s.add(1.5f, 0f, 1f) + s.add(2f, 0f, 1f)
        // перо почти не сдвинулось (в пределах нити), но нажим уже вырос
        assertTrue(out.isNotEmpty())
        assertTrue("нажим не отстаёт: ${out.last().pressure}", out.last().pressure > 0.8f)
        val tail = s.finish()
        assertEquals(1f, tail.last().pressure, 1e-6f)
        assertTrue(tail.all { hypot(it.x, it.y) <= 2.0001f })
    }
}
