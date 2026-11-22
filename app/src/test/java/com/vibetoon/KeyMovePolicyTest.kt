package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.*
import org.junit.Test

class KeyMovePolicyTest {
    @Test fun rejectsCollisionsOnGroupedTracksAndOutOfBounds() {
        val fx = EffectTracks.id(7, 0, 0)
        fun track(id: Int, vararg frames: Int) = AnimationTrack(id, frames.map { Keyframe.of(it, LayerTransform()) })
        val a = Animation(settings = ProjectSettings(durationFrames = 20), tracks = mapOf(
            7 to track(7, 2, 4), fx to track(fx, 2, 5)))
        assertFalse(KeyMovePolicy.canMove(a, setOf(7 to 2), 3))
        assertTrue(KeyMovePolicy.canMove(a, setOf(7 to 2, 7 to 4, 7 to 5), 1))
        assertFalse(KeyMovePolicy.canMove(a, setOf(7 to 2), -3))
        assertFalse(KeyMovePolicy.canMove(a, setOf(7 to 4), Int.MAX_VALUE))
        assertEquals(5, a.tracks[fx]!!.keys.last().frame)
    }
    @Test fun pasteIsAtomicAndRejectsCollisionsDuplicatesAndOverflow() {
        val anim = Animation(settings = ProjectSettings(durationFrames = 20), tracks = mapOf(
            7 to AnimationTrack(7, listOf(Keyframe.of(5, LayerTransform())))))
        fun d(id: Int, f: Long) = KeyPastePolicy.Destination(id, f)
        assertTrue(KeyPastePolicy.canPaste(anim, listOf(d(7, 3), d(8, 5))))
        assertFalse(KeyPastePolicy.canPaste(anim, listOf(d(7, 3), d(7, 5))))
        assertFalse(KeyPastePolicy.canPaste(anim, listOf(d(7, 3), d(7, 3))))
        assertFalse(KeyPastePolicy.canPaste(anim, listOf(d(7, Long.MAX_VALUE))))
        assertFalse(KeyPastePolicy.canPaste(anim, emptyList()))
        assertEquals(5, anim.tracks[7]!!.keys.single().frame)
    }
    @Test fun stretchRejectsCollisionsWithSelectedAndUnselectedGroupedKeys() {
        val fx = EffectTracks.id(7, 0, 0)
        fun track(id: Int, vararg frames: Int) = AnimationTrack(id, frames.map { Keyframe.of(it, LayerTransform()) })
        val a = Animation(settings = ProjectSettings(durationFrames = 20), tracks = mapOf(7 to track(7, 2, 4), fx to track(fx, 2, 4, 5)))
        val selected = setOf(7 to 2, 7 to 4)
        assertFalse(KeyMovePolicy.canRemap(a, selected) { 2 })
        assertFalse(KeyMovePolicy.canRemap(a, selected) { if (it == 4) 5 else it })
        assertFalse(KeyMovePolicy.canRemap(a, selected) { 20 })
        assertTrue(KeyMovePolicy.canRemap(a, selected) { if (it == 4) 6 else it })
        assertEquals(listOf(2, 4, 5), a.tracks[fx]!!.keys.map { it.frame })
    }

}
