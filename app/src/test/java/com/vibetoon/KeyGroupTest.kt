package com.vibetoon

import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.anim.MeshMath
import com.vibetoon.domain.anim.PuppetMath
import com.vibetoon.domain.anim.TrackGroups
import com.vibetoon.domain.anim.WarpMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGroupTest {
    private fun key(f: Int) = Keyframe(f, 0f, 0f, 1f, 1f, 0f)
    private fun tr(id: Int, vararg frames: Int) = id to AnimationTrack(id, frames.map { key(it) })

    @Test fun groupCollectsAllTracksOfTheLayerOnly() {
        val tracks = mapOf(
            tr(7, 0, 10), tr(MeshMath.vertexId(7, 3), 10, 20), tr(PuppetMath.pinId(7, 1), 20), tr(WarpMath.pointId(7, 2), 30),
            tr(8, 5), tr(MeshMath.vertexId(8, 0), 6)
        )
        assertEquals(setOf(0, 10, 20, 30), TrackGroups.frames(7, tracks))
        assertEquals(4, TrackGroups.ids(7, tracks).size)
        assertEquals(setOf(5, 6), TrackGroups.frames(8, tracks))
    }

    @Test fun remapKeepsSlotOnAnotherLayer() {
        assertEquals(9, TrackGroups.remap(7, 7, 9))
        assertEquals(MeshMath.vertexId(9, 3), TrackGroups.remap(MeshMath.vertexId(7, 3), 7, 9))
        assertEquals(PuppetMath.pinId(9, 1), TrackGroups.remap(PuppetMath.pinId(7, 1), 7, 9))
        assertEquals(WarpMath.pointId(9, 2), TrackGroups.remap(WarpMath.pointId(7, 2), 7, 9))
        assertTrue(TrackGroups.remap(MeshMath.vertexId(8, 3), 7, 9) == null)
    }
}
