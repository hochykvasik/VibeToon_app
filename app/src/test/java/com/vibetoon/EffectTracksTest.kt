package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.project.*
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.*
import org.junit.Test

class EffectTracksTest {
    private val layer = 100001

    @Test fun allEffectParametersInterpolateAndTrackGroupsMoveAndDelete() {
        for (def in EffectMath.DEFS) {
            val effects = EffectTracks.assignSlots(listOf(EffectMath.create(def.type)!!))
            val original = Animation().withKey(layer, 0, LayerTransform(dx = 4f)).withKey(layer, 10, LayerTransform(dx = 14f))
            var anim = EffectTracks.snapshot(layer, effects, original, 0)
            anim = EffectTracks.snapshot(layer, effects, anim, 10)
            for ((i, param) in def.params.withIndex()) {
                anim = anim.withKey(EffectTracks.id(layer, effects[0].slot, i), 0, LayerTransform(dx = param.min))
                    .withKey(EffectTracks.id(layer, effects[0].slot, i), 10, LayerTransform(dx = param.max))
            }
            val mid = EffectTracks.at(layer, effects, anim, 5f).single()
            for ((i, param) in def.params.withIndex()) assertEquals(def.title + " " + param.name, (param.min + param.max) / 2f, mid.p[i], 1e-4f)
            assertEquals(9f, anim.evaluate(layer, 5, null).dx, 0.001f)
            assertTrue(TrackGroups.frames(layer, anim.tracks).containsAll(listOf(0, 10)))
            for (id in TrackGroups.ids(layer, anim.tracks)) {
                val t = anim.tracks.getValue(id)
                val moved = AnimationTrack(id, t.keys.map { if (it.frame == 10) it.copy(frame = 20) else it })
                anim = anim.copy(tracks = anim.tracks + (id to moved))
            }
            val movedMid = EffectTracks.at(layer, effects, anim, 10f).single()
            for ((i, param) in def.params.withIndex()) assertEquals((param.min + param.max) / 2f, movedMid.p[i], 1e-4f)
            for (id in TrackGroups.ids(layer, anim.tracks)) anim = anim.withoutKey(id, 20).first
            val held = EffectTracks.at(layer, effects, anim, 10f).single()
            for ((i, param) in def.params.withIndex()) assertEquals(param.min, held.p[i], 1e-4f)
        }
    }

    @Test fun legacyStaticEffectsAndTransformTracksSurviveSaveLoadAndSnapshots() {
        val old = ProjectData(ProjectMeta("p", "old", 100, 100),
            tracks = mapOf(layer to AnimationTrack(layer).upsert(Keyframe.of(0, LayerTransform(dx = 2f))).upsert(Keyframe.of(10, LayerTransform(dx = 12f)))),
            fx = mapOf(layer to listOf(EffectMath.create(EffectMath.WAVE)!!)))
        val legacyJson = ProjectJson.encode(old)
        assertFalse(legacyJson.contains("\"slot\""))
        val loaded = ProjectJson.decode(legacyJson).getOrThrow()
        val effects = loaded.fx.getValue(layer)
        val before = loaded.animation()
        for (f in listOf(0, 5, 10, 100)) {
            val value = EffectTracks.at(layer, effects, before, f.toFloat()).single()
            assertEquals(old.fx.getValue(layer).single().p, value.p)
            assertArrayEquals(EffectMath.records(old.fx.getValue(layer), f / 24f).single(), EffectMath.records(listOf(value), f / 24f).single(), 1e-6f)
        }
        var edited = EffectTracks.snapshot(layer, effects, before, 0)
        edited = EffectTracks.snapshot(layer, effects, edited, 10)
        edited = edited.withKey(EffectTracks.id(layer, effects[0].slot, 0), 10, LayerTransform(dx = 40f))
        val reloaded = ProjectJson.decode(ProjectJson.encode(loaded.copy(tracks = edited.tracks))).getOrThrow()
        assertEquals(7f, reloaded.animation().evaluate(layer, 5, null).dx, 0.001f)
        assertEquals(25f, EffectTracks.at(layer, reloaded.fx.getValue(layer), reloaded.animation(), 5f).single().p[0], 0.001f)
        // История хранит неизменяемые снимки Animation и списка эффектов.
        assertEquals(10f, EffectTracks.at(layer, effects, before, 5f).single().p[0], 0.001f)
        assertEquals(25f, EffectTracks.at(layer, effects, edited, 5f).single().p[0], 0.001f)
    }

    @Test fun easingAndCurvesUseExistingAnimationTrackRules() {
        val fx = EffectTracks.assignSlots(listOf(EffectMath.create(EffectMath.WAVE)!!))
        val id = EffectTracks.id(layer, fx[0].slot, 0)
        val anim = Animation().withKey(id, 0, LayerTransform(dx = 0f)).withKey(id, 10, LayerTransform(dx = 40f)).withEasing(id, 0, EasingType.STEP)
        assertEquals(0f, EffectTracks.at(layer, fx, anim, 5f)[0].p[0], 1e-5f)
        assertEquals(40f, EffectTracks.at(layer, fx, anim, 10f)[0].p[0], 1e-5f)
    }

    @Test fun slotsRemainAttachedToEffectsAfterReorderAndRoundTrip() {
        val fx = EffectTracks.assignSlots(listOf(EffectMath.create(9)!!, EffectMath.create(9)!!))
        val reordered = fx.reversed()
        val anim = Animation().withKey(EffectTracks.id(layer, fx[0].slot, 0), 0, LayerTransform(dx = 35f))
        val d = ProjectData(ProjectMeta("p", "n", 100, 100), tracks = anim.tracks, fx = mapOf(layer to reordered))
        val back = ProjectJson.decode(ProjectJson.encode(d)).getOrThrow()
        val values = EffectTracks.at(layer, back.fx.getValue(layer), back.animation(), 0f)
        assertEquals(10f, values[0].p[0], 1e-5f)
        assertEquals(35f, values[1].p[0], 1e-5f)
    }
}
