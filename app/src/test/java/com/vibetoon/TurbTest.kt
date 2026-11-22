package com.vibetoon

import com.vibetoon.domain.anim.Turb
import com.vibetoon.domain.anim.TurbMath
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class TurbTest {
    @Test fun seedStaticWithoutBoil() {
        val t = Turb(boil = 0)
        assertEquals(0f, TurbMath.params(t, 57)[3], 0f)
    }

    @Test fun lineBoilStepsEveryNFrames() {
        val t = Turb(boil = 3)
        assertEquals(0f, TurbMath.params(t, 2)[3], 0f)
        assertEquals(1f, TurbMath.params(t, 3)[3], 0f)
        assertEquals(2f, TurbMath.params(t, 6)[3], 0f)
    }

    @Test fun paramsAreClampedAndScaled() {
        val p = TurbMath.params(Turb(intensity = 99f, size = 0f, complexity = 99, boil = 0), 0)
        assertEquals(0.3f, p[0], 1e-6f)
        assertEquals(1f, p[1], 0f)
        assertEquals(6f, p[2], 0f)
    }

    @Test fun keysAnimateParamsOverFrames() {
        val layer = 7
        val id = TurbMath.paramId(layer, 0)
        val anim = com.vibetoon.domain.anim.Animation(tracks = mapOf(
            id to com.vibetoon.domain.anim.AnimationTrack(id, listOf(
                com.vibetoon.domain.anim.Keyframe.of(0, com.vibetoon.domain.render.LayerTransform(dx = 0f)),
                com.vibetoon.domain.anim.Keyframe.of(10, com.vibetoon.domain.render.LayerTransform(dx = 6f))
            ))
        ))
        val base = Turb(intensity = 2f)
        assertEquals(2f, TurbMath.at(layer, base, 0, anim).intensity, 1e-4f)
        assertEquals(5f, TurbMath.at(layer, base, 5, anim).intensity, 0.05f)
        assertEquals(8f, TurbMath.at(layer, base, 10, anim).intensity, 1e-4f)
    }

    @Test fun jsonRoundTrip() {
        val d = ProjectData(ProjectMeta(id = "p", name = "t", width = 10, height = 10), turbs = mapOf(5 to Turb(4f, 8f, 3, 2)))
        assertEquals(d.turbs, ProjectJson.decode(ProjectJson.encode(d)).getOrThrow().turbs)
    }
}

class CurveTest {
    @org.junit.Test fun customCurveOvershootsAndRoundTrips() {
        val c = com.vibetoon.domain.anim.Curve.parse(listOf(0.3f, 1.6f, 0.7f, 1f))!!        // старый формат из 4 чисел
        val k = com.vibetoon.domain.anim.Keyframe(0, 0f, 0f, 1f, 1f, 0f, 1f, com.vibetoon.domain.anim.EasingType.LINEAR, c.toList())
        org.junit.Assert.assertTrue(k.ease(0.4f) > 1f)
        org.junit.Assert.assertEquals(0f, k.ease(0f), 0f); org.junit.Assert.assertEquals(1f, k.ease(1f), 0f)
        val tr = com.vibetoon.domain.anim.AnimationTrack(5, listOf(k, k.copy(frame = 10, dx = 100f, curve = null)))
        org.junit.Assert.assertTrue(tr.evaluate(4)!!.dx > 100f)
        val d = com.vibetoon.domain.project.ProjectData(
            com.vibetoon.domain.project.ProjectMeta(id = "p", name = "t", width = 10, height = 10), tracks = mapOf(5 to tr)
        )
        val back = com.vibetoon.domain.project.ProjectJson.decode(com.vibetoon.domain.project.ProjectJson.encode(d)).getOrThrow()
        org.junit.Assert.assertEquals(k.curve, back.tracks.getValue(5).keys[0].curve)
        org.junit.Assert.assertNull(back.tracks.getValue(5).keys[1].curve)
    }

    @org.junit.Test fun multiNodeCurvePassesThroughNodes() {
        val c = com.vibetoon.domain.anim.Curve.LINEAR.insertAt(0.5f)
        org.junit.Assert.assertEquals(3, c.nodes.size)
        org.junit.Assert.assertEquals(0.5f, c.eval(0.5f), 1e-3f)                    // линейная форма не изменилась
        // тянем средний узел вверх: кривая проходит ровно через него
        val up = c.withNode(1, c.nodes[1].copy(y = 0.9f))
        org.junit.Assert.assertEquals(0.9f, up.eval(0.5f), 1e-3f)
        org.junit.Assert.assertTrue(up.eval(0.25f) > 0.25f && up.eval(0.75f) > 0.75f)
        org.junit.Assert.assertEquals(2, up.without(1).nodes.size)
        org.junit.Assert.assertEquals(3, up.without(0).without(2).nodes.size)   // крайние узлы не удаляются
    }

    @org.junit.Test fun presetsReachEndsAndBounceOvershootsNever() {
        for ((_, make) in com.vibetoon.domain.anim.Curve.PRESETS) {
            val c = make()
            org.junit.Assert.assertEquals(0f, c.eval(0f), 1e-4f); org.junit.Assert.assertEquals(1f, c.eval(1f), 1e-4f)
            val back = com.vibetoon.domain.anim.Curve.parse(c.toList())!!
            org.junit.Assert.assertEquals(c.eval(0.37f), back.eval(0.37f), 1e-5f)    // формат хранения обратим
        }
        val elastic = com.vibetoon.domain.anim.Curve.PRESETS.first { it.first == "Elastic" }.second()
        org.junit.Assert.assertTrue((1..49).any { elastic.eval(it / 50f) > 1.02f })  // пружина перелетает
    }
}
