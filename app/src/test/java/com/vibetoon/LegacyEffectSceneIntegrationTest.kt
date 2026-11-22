package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.project.*
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.*
import org.junit.Test

/** JSON -> вычисление анимации -> данные сцены для рендерера/экспорта, без Android GL. */
class LegacyEffectSceneIntegrationTest {
    @Test fun oldStaticEffectAndAnimatedTransformSurviveSceneAndRoundTrip() {
        val layer = 100001
        val original = ProjectData(ProjectMeta("legacy", "Legacy", 100, 100),
            tracks = mapOf(layer to AnimationTrack(layer).upsert(Keyframe.of(0, LayerTransform(dx = 0f))).upsert(Keyframe.of(20, LayerTransform(dx = 20f)))),
            fx = mapOf(layer to listOf(EffectMath.create(EffectMath.WAVE)!!)))
        val loaded = ProjectJson.decode(ProjectJson.encode(original)).getOrThrow()
        val leaf = LeafNode(layer, "layer", true, 1f, "norm", false, 0, 0, 100, 100)
        fun scene(data: ProjectData, frame: Int): com.vibetoon.domain.render.SceneData {
            val animation = data.animation()
            val fx = EffectTracks.at(layer, data.fx.getValue(layer), animation, frame.toFloat())
            return SceneBuilder.buildTree(listOf(leaf), 100, 100,
                transforms = mapOf(layer to animation.evaluate(layer, frame, null)),
                fx = mapOf(layer to EffectMath.records(fx, frame / 24f)))
        }
        assertEquals(10f, scene(loaded, 10).selectionQuads.getValue(layer)[0], 1e-4f)
        assertEquals(10f, scene(loaded, 0).fxData[1], 1e-4f)
        assertEquals(10f, scene(loaded, 20).fxData[1], 1e-4f)
        var anim = EffectTracks.snapshot(layer, loaded.fx.getValue(layer), loaded.animation(), 0)
        anim = EffectTracks.snapshot(layer, loaded.fx.getValue(layer), anim, 20)
        anim = anim.withKey(EffectTracks.id(layer, loaded.fx.getValue(layer)[0].slot, 0), 20, LayerTransform(dx = 30f))
        val updated = ProjectJson.decode(ProjectJson.encode(loaded.copy(tracks = anim.tracks))).getOrThrow()
        assertEquals(20f, scene(updated, 10).fxData[1], 1e-4f)
        assertEquals(scene(loaded, 10).selectionQuads.getValue(layer)[0], scene(updated, 10).selectionQuads.getValue(layer)[0], 1e-4f)
    }
}
