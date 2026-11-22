package com.vibetoon.anim

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.anim.PlaybackMath
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationTest {
    private val k0 = Keyframe(0, 0f, 0f, 1f, 1f, 0f)
    private val k30 = Keyframe(30, 300f, -60f, 3f, 3f, 3f)

    @Test fun emptyTrackIsNull() = assertNull(AnimationTrack(1).evaluate(5))

    @Test fun singleKeyHoldsEverywhere() {
        val t = AnimationTrack(1).upsert(k30)
        assertEquals(300f, t.evaluate(0)!!.dx, 0f)
        assertEquals(300f, t.evaluate(99)!!.dx, 0f)
    }

    @Test fun holdBeforeFirstAndAfterLast() {
        val t = AnimationTrack(1).upsert(Keyframe(10, 5f, 0f, 1f, 1f, 0f)).upsert(Keyframe(20, 15f, 0f, 1f, 1f, 0f))
        assertEquals(5f, t.evaluate(0)!!.dx, 0f)
        assertEquals(15f, t.evaluate(50)!!.dx, 0f)
    }

    @Test fun midpointIsLinear() {
        val t = AnimationTrack(1).upsert(k0).upsert(k30)
        val m = t.evaluate(15)!!
        assertEquals(150f, m.dx, 0.001f)
        assertEquals(-30f, m.dy, 0.001f)
        assertEquals(2f, m.scaleX, 0.001f)
        assertEquals(2f, m.scaleY, 0.001f)
        assertEquals(1.5f, m.rotation, 0.001f)
    }

    @Test fun exactKeyReturnsKeyValues() {
        val t = AnimationTrack(1).upsert(k0).upsert(k30)
        assertEquals(300f, t.evaluate(30)!!.dx, 0f)
        assertEquals(0f, t.evaluate(0)!!.dx, 0f)
    }

    @Test fun rotationIsNotWrapped() {
        val full = (4 * Math.PI).toFloat()
        val t = AnimationTrack(1).upsert(Keyframe(0, 0f, 0f, 1f, 1f, 0f)).upsert(Keyframe(40, 0f, 0f, 1f, 1f, full))
        assertEquals(full / 4f, t.evaluate(10)!!.rotation, 0.0001f)
    }

    @Test fun upsertReplacesKeyOnSameFrame() {
        val t = AnimationTrack(1).upsert(k0).upsert(k0.copy(dx = 9f))
        assertEquals(1, t.keys.size)
        assertEquals(9f, t.keyAt(0)!!.dx, 0f)
    }

    @Test fun keysStaySorted() {
        val t = AnimationTrack(1).upsert(k30).upsert(k0)
        assertEquals(listOf(0, 30), t.keys.map { it.frame })
    }

    @Test fun trackWinsOverBase() {
        val a = Animation().withKey(1, 0, LayerTransform(dx = 7f))
        assertEquals(7f, a.evaluate(1, 50, LayerTransform(dx = 999f)).dx, 0f)
        assertEquals(999f, a.evaluate(2, 50, LayerTransform(dx = 999f)).dx, 0f)
        assertEquals(LayerTransform(), a.evaluate(2, 0, null))
    }

    @Test fun removingLastKeyReturnsItsValueForBase() {
        val a = Animation().withKey(1, 5, LayerTransform(dx = 42f))
        val (a2, rest) = a.withoutKey(1, 5)
        assertFalse(a2.hasTrack(1))
        assertEquals(42f, rest!!.dx, 0f)
    }

    @Test fun removingNonLastKeyKeepsTrack() {
        val a = Animation().withKey(1, 0, LayerTransform()).withKey(1, 30, LayerTransform(dx = 1f))
        val (a2, rest) = a.withoutKey(1, 30)
        assertTrue(a2.hasTrack(1))
        assertNull(rest)
        assertNotNull(a2.tracks[1]!!.keyAt(0))
    }

    @Test fun playbackFollowsClockAndLoops() {
        // 24 fps, 120 кадров: через 1 с кадр 24, через 5 с снова начало
        assertEquals(24, PlaybackMath.frameAt(1_000_000_000L, 24, 0, 120))
        assertEquals(0, PlaybackMath.frameAt(5_000_000_000L, 24, 0, 120))
        assertEquals(10, PlaybackMath.frameAt(0L, 24, 10, 120))
        // пропуск кадров отрисовки не замедляет анимацию: считаем по времени, а не по числу вызовов
        assertEquals(60, PlaybackMath.frameAt(2_500_000_000L, 24, 0, 120))
    }
}
