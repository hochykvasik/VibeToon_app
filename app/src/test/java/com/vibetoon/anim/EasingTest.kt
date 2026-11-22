package com.vibetoon.anim

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EasingTest {
    private val inOut = EasingType.EASE_IN_OUT

    @Test fun endpointsAreFixedForAllTypes() {
        for (e in EasingType.values()) {
            assertEquals(0f, e.apply(0f), 0f)
            assertEquals(1f, e.apply(1f), 0f)
        }
    }

    @Test fun linearIsIdentity() {
        for (i in 0..10) assertEquals(i / 10f, EasingType.LINEAR.apply(i / 10f), 0f)
    }

    @Test fun easeInOutIsSymmetricAtMiddle() = assertEquals(0.5f, inOut.apply(0.5f), 1e-4f)

    @Test fun easeInOutStartsAndEndsSlowerThanLinear() {
        assertTrue(inOut.apply(0.1f) < 0.1f)
        assertTrue(inOut.apply(0.9f) > 0.9f)
    }

    @Test fun easeInIsSlowAtStartAndEaseOutIsFastAtStart() {
        assertTrue(EasingType.EASE_IN.apply(0.3f) < 0.3f)
        assertTrue(EasingType.EASE_OUT.apply(0.3f) > 0.3f)
        // EASE_IN(0.5) ~ 0.315 для cubic-bezier(0.42, 0, 1, 1)
        assertEquals(0.315f, EasingType.EASE_IN.apply(0.5f), 0.01f)
    }

    @Test fun easeOutMirrorsEaseIn() {
        for (i in 1 until 10) {
            val t = i / 10f
            assertEquals(1f - EasingType.EASE_IN.apply(1f - t), EasingType.EASE_OUT.apply(t), 1e-3f)
        }
    }

    @Test fun allTypesAreMonotonic() {
        for (e in EasingType.values()) {
            var prev = -1f
            for (i in 0..200) {
                val v = e.apply(i / 200f)
                assertTrue("$e not monotonic at $i", v >= prev - 1e-6f)
                prev = v
            }
        }
    }

    @Test fun matchesReferenceBisection() {
        // эталон: решаем x(s) = t бисекцией в double и считаем y(s)
        fun bez(a: Double, b: Double, s: Double): Double {
            val u = 1 - s
            return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s
        }
        for (i in 1 until 100) {
            val t = i / 100.0
            var lo = 0.0
            var hi = 1.0
            repeat(60) { val m = (lo + hi) / 2; if (bez(0.42, 0.58, m) < t) lo = m else hi = m }
            val expected = bez(0.0, 1.0, (lo + hi) / 2)
            assertEquals(expected, inOut.apply(t.toFloat()).toDouble(), 1e-4)
        }
    }

    @Test fun easingComesFromLeftKey() {
        val track = AnimationTrack(1)
            .upsert(Keyframe(0, 0f, 0f, 1f, 1f, 0f, easing = inOut))
            .upsert(Keyframe(40, 100f, 0f, 1f, 1f, 0f))
        assertTrue(track.evaluate(10)!!.dx < 25f)                    // медленный старт
        assertEquals(50f, track.evaluate(20)!!.dx, 0.01f)            // середина без изменений
    }

    @Test fun editingKeepsEasing() {
        val a = Animation().withKey(1, 0, LayerTransform()).withEasing(1, 0, inOut)
        val b = a.withKey(1, 0, LayerTransform(dx = 5f))
        assertEquals(inOut, b.easingAt(1, 0))
        assertEquals(5f, b.tracks[1]!!.keyAt(0)!!.dx, 0f)
    }

    @Test fun opacityIsInterpolatedAndKeyed() {
        val a = Animation().withKey(1, 0, LayerTransform(opacity = 1f)).withKey(1, 10, LayerTransform(opacity = 0f))
        assertEquals(0.5f, a.evaluate(1, 5, null).opacity, 1e-4f)
        assertEquals(0f, a.evaluate(1, 99, null).opacity, 0f)
    }

    @Test fun withEasingIgnoresMissingKey() {
        val a = Animation().withKey(1, 0, LayerTransform())
        assertEquals(a, a.withEasing(1, 7, inOut))   // на кадре 7 ключа нет
    }
}
