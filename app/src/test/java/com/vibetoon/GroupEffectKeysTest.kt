package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.project.*
import org.junit.Assert.*
import org.junit.Test

class GroupEffectKeysTest {
    @Test fun identicalChildTracksAnimateFromBaselineWithoutTouchingTransformsAndRoundTrip() {
        val ids = listOf(100001, 100002, 100003)
        val effects = EffectTracks.assignSlots(listOf(EffectMath.create(EffectMath.BLUR)!!))
        val old = Animation(settings = ProjectSettings(durationFrames = 30))
        val start = effects[0].p[0]
        val anim = GroupEffectKeys.key(ids, effects, old, 10, 0, mapOf(0 to 5f))!!
        assertTrue(old.tracks.isEmpty())
        assertTrue(GroupEffectKeys.synchronized(ids, effects, anim))
        for (id in ids) {
            assertFalse(anim.hasTrack(id))
            assertEquals(start, EffectTracks.at(id, effects, anim, 0f)[0].p[0], .0001f)
            assertEquals(5f, EffectTracks.at(id, effects, anim, 10f)[0].p[0], .0001f)
            assertEquals((start + 5f) / 2, EffectTracks.at(id, effects, anim, 5f)[0].p[0], .0001f)
        }
        val data = ProjectData(ProjectMeta("p", "test", 100, 100), tracks = anim.tracks, fx = ids.associateWith { effects })
        val reopened = ProjectJson.decode(ProjectJson.encode(data)).getOrThrow()
        assertEquals(data.fx, reopened.fx)
        for ((id, track) in anim.tracks) assertEquals(track.keys, reopened.tracks[id]?.keys)
        val undo = old; val redo = anim
        assertTrue(undo.tracks.isEmpty()); assertTrue(redo.tracks.isNotEmpty())
    }

    @Test fun differingKeysOrInvalidFrameRefuseWithoutModifyingAnimation() {
        val ids = listOf(2, 3)
        val effects = EffectTracks.assignSlots(listOf(EffectMath.create(EffectMath.COLOR)!!))
        val anim = GroupEffectKeys.key(ids, effects, Animation(), 5)!!
        val extra = anim.withKey(EffectTracks.id(2, effects[0].slot, 0), 8, com.vibetoon.domain.render.LayerTransform(dx = 4f))
        assertFalse(GroupEffectKeys.synchronized(ids, effects, extra))
        assertNull(GroupEffectKeys.key(ids, effects, extra, 10, 0, mapOf(0 to 1f)))
        assertNull(GroupEffectKeys.key(ids, effects, anim, 120))
        assertNull(GroupEffectKeys.key(ids, effects, anim, 5, 0, mapOf(0 to Float.NaN)))
        assertTrue(extra.hasKey(EffectTracks.id(2, effects[0].slot, 0), 8))
    }
    @Test fun waveKeysMoveAndDeleteTogetherWithoutTouchingTransforms() {
        val ids = listOf(100001, 100002, 100003)
        val effects = EffectTracks.assignSlots(listOf(EffectMath.create(EffectMath.WAVE)!!))
        val old = Animation(settings = ProjectSettings(durationFrames = 30)).withKey(ids.first(), 10, com.vibetoon.domain.render.LayerTransform(dx = 9f))
        val keyed = GroupEffectKeys.key(ids, effects, old, 10, 0, mapOf(0 to 35f))!!
        val moved = GroupEffectKeys.move(ids, effects, keyed, 10, 1)!!
        assertNull(GroupEffectKeys.move(ids, effects, keyed, 10, -10)) // целевой кадр занят исходной формой
        assertEquals(9f, moved.tracks[ids.first()]!!.keyAt(10)!!.dx, 0f)
        for (id in ids) {
            val track = moved.tracks[EffectTracks.id(id, effects[0].slot, 0)]!!
            assertNull(track.keyAt(10)); assertEquals(35f, track.keyAt(11)!!.dx, 0f)
            val records = EffectMath.records(EffectTracks.at(id, effects, moved, 11f), 11f / 24f)
            assertEquals(35f, records.first()[1], 0.001f)
        }
        val without = GroupEffectKeys.delete(ids, effects, moved, 0)!!
        val last = GroupEffectKeys.delete(ids, without.effects, without.animation, 11)!!
        assertEquals(35f, last.effects[0].p[0], .001f)
        assertFalse(GroupEffectKeys.hasKeys(ids, last.animation))
        assertEquals(9f, last.animation.tracks[ids.first()]!!.keyAt(10)!!.dx, 0f)
        assertTrue(GroupEffectKeys.synchronized(ids, last.effects, last.animation))
        assertNotNull(keyed.tracks[EffectTracks.id(ids.first(), effects[0].slot, 0)]!!.keyAt(10))
    }

}
