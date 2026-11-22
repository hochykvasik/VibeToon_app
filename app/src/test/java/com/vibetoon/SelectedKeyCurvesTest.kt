package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.*
import org.junit.Test

class SelectedKeyCurvesTest {
    @Test fun appliesToSelectedFramesOnAllGroupedTracksOnly() {
        val fx = EffectTracks.id(7, 0, 0)
        fun track(id: Int) = AnimationTrack(id, listOf(Keyframe.of(2, LayerTransform()), Keyframe.of(8, LayerTransform())))
        val original = Animation(tracks = mapOf(7 to track(7), fx to track(fx), 9 to track(9)))
        val targets = SelectedKeyCurves.targets(original, setOf(7 to 2, 7 to 8, 9 to 99))
        assertEquals(setOf(7 to 2, 7 to 8, fx to 2, fx to 8), targets.toSet())
        val data = Curve.LINEAR.toList()
        val result = SelectedKeyCurves.apply(original, targets, data)
        for ((id, frame) in targets) assertEquals(data, result.tracks[id]!!.keyAt(frame)!!.curve)
        assertNull(result.tracks[9]!!.keyAt(2)!!.curve)
        assertNull(original.tracks[7]!!.keyAt(2)!!.curve)
    }
    @Test fun missingOrStaleSelectionChangesNothing() {
        val a = Animation()
        assertTrue(SelectedKeyCurves.targets(a, setOf(12 to 2)).isEmpty())
        assertEquals(a, SelectedKeyCurves.apply(a, listOf(12 to 2), Curve.LINEAR.toList()))
    }
}
