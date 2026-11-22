package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.project.*
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.*
import org.junit.Test

class AutoKeyEditsTest {
    private val layer = 100001

    @Test fun firstTransformAndBoneKeysUpdateWithoutMutatingHistory() {
        val before = Animation()
        val first = AutoKeyEdits.transform(before, layer, 10, LayerTransform(), LayerTransform(dx = 12f, opacity = .4f), emptyList())
        assertFalse(before.hasTrack(layer))
        assertEquals(12f, first.evaluate(layer, 10, null).dx, .0001f)
        assertEquals(.4f, first.evaluate(layer, 10, null).opacity, .0001f)
        val edited = AutoKeyEdits.transform(first, layer, 10, LayerTransform(dx = 12f, opacity = .4f), LayerTransform(dx = 22f), emptyList())
        assertEquals(2, edited.tracks.getValue(layer).keys.size)
        assertEquals(0f, edited.evaluate(layer, 0, null).dx, .0001f)
        assertEquals(12f, first.evaluate(layer, 10, null).dx, .0001f) // undo snapshot
        assertEquals(22f, edited.evaluate(layer, 10, null).dx, .0001f) // redo snapshot
        val boneId = 2_000_000
        val bone = AutoKeyEdits.transform(edited, boneId, 6, LayerTransform(), LayerTransform(rotation = 1f), emptyList())
        assertEquals(1f, bone.evaluate(boneId, 6, null).rotation, .0001f)
    }

    @Test fun autoEffectKeysAllSupportedParametersAndKeepsOtherEffects() {
        for (def in EffectMath.DEFS) {
            val effects = EffectTracks.assignSlots(listOf(EffectMath.create(def.type)!!, EffectMath.create(def.type)!!))
            val effect = effects[0]
            for ((i, param) in def.params.withIndex()) {
                val old = Animation()
                val changed = AutoKeyEdits.effect(old, layer, 12, LayerTransform(dx = 5f), effects, effect, mapOf(i to param.max))
                assertTrue(changed.hasKey(layer, 12))
                assertEquals(param.max, EffectTracks.at(layer, effects, changed, 12f)[0].p[i], .0001f)
                assertEquals(effect.p[i], EffectTracks.at(layer, effects, changed, 0f)[0].p[i], .0001f)
                assertEquals(effects[1].p[i], EffectTracks.at(layer, effects, changed, 12f)[1].p[i], .0001f)
                assertFalse(old.hasTrack(layer))
            }
        }
    }

    @Test fun easingCurveAndProjectSerializationSurviveAutoKeyUpdate() {
        val fx = EffectTracks.assignSlots(listOf(EffectMath.create(EffectMath.WAVE)!!))
        val track = EffectTracks.id(layer, fx[0].slot, 0)
        val before = AutoKeyEdits.effect(Animation(), layer, 8, LayerTransform(), fx, fx[0], mapOf(0 to 10f))
            .withCurve(layer, 8, listOf(.1f, .2f, .8f, .9f)).withEasing(track, 8, EasingType.LINEAR)
        val after = AutoKeyEdits.effect(before, layer, 8, LayerTransform(), fx, fx[0], mapOf(0 to 20f))
        assertEquals(before.curveAt(layer, 8), after.curveAt(layer, 8))
        val project = ProjectData(ProjectMeta("p", "test", 100, 100), tracks = after.tracks, fx = mapOf(layer to fx))
        val restored = ProjectJson.decode(ProjectJson.encode(project)).getOrThrow()
        assertEquals(20f, EffectTracks.at(layer, restored.fx.getValue(layer), restored.animation(), 8f)[0].p[0], .001f)
        assertEquals(10f, EffectTracks.at(layer, fx, before, 8f)[0].p[0], .001f)
    }

    @Test fun transformedClipRequiresCompatibleTimelineFrame() {
        assertTrue(AutoKeyEdits.canKeyTransform(10, 10f))
        assertFalse(AutoKeyEdits.canKeyTransform(10, 10.5f))
        assertFalse(AutoKeyEdits.canKeyTransform(10, 20f))
    }
}
