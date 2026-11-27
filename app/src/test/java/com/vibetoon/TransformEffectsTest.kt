package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.Effect
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.anim.EffectTracks
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.SymbolForms
import com.vibetoon.domain.project.SymbolMath
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 6: «Ось растяжения» и «Качание» (Swing) - эффекты-трансформации на CPU; новый экземпляр символа без авто-ключей. */
class TransformEffectsTest {
    private val eps = 1e-3f
    private fun swing(freq: Float, a1: Float, a2: Float, phase: Float = 0f, wave: Int = EffectMath.WAVE_SINE, slot: Int = -1) =
        Effect(EffectMath.SWING, listOf(freq, a1, a2, phase, wave.toFloat(), 0f, 0f, 0f), slot)
    private fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()

    @Test fun transformEffectsAreNotShaderPasses() {
        val list = listOf(EffectMath.create(EffectMath.BLUR)!!, EffectMath.create(EffectMath.STRETCH_AXIS)!!, EffectMath.create(EffectMath.SWING)!!)
        assertEquals(1, EffectMath.records(list, 0f).size)
        assertTrue(EffectMath.isTransform(EffectMath.SPIN) && EffectMath.isTransform(EffectMath.SWING) && EffectMath.isTransform(EffectMath.STRETCH_AXIS))
        // параметры по умолчанию - как в Alight Motion: масштаб/угол; частота, углы, фаза, волна
        assertEquals(listOf(1.25f, 0f), EffectMath.create(EffectMath.STRETCH_AXIS)!!.p.take(2))
        assertEquals(4, EffectMath.def(EffectMath.SWING)!!.params[4].options.size)
    }

    @Test fun swingWaves() {
        assertEquals(0f, EffectMath.swingWave(EffectMath.WAVE_SINE, 0.0), eps)
        assertEquals(0.5f, EffectMath.swingWave(EffectMath.WAVE_SINE, 0.25), eps)
        assertEquals(1f, EffectMath.swingWave(EffectMath.WAVE_SINE, 0.5), eps)
        assertEquals(0f, EffectMath.swingWave(EffectMath.WAVE_SINE, 3.0), eps)          // период 1
        assertEquals(0.5f, EffectMath.swingWave(EffectMath.WAVE_TRIANGLE, 0.25), eps)
        assertEquals(1f, EffectMath.swingWave(EffectMath.WAVE_TRIANGLE, 0.5), eps)
        assertEquals(0f, EffectMath.swingWave(EffectMath.WAVE_SQUARE, 0.4), eps)
        assertEquals(1f, EffectMath.swingWave(EffectMath.WAVE_SQUARE, 0.6), eps)
        assertEquals(0.75f, EffectMath.swingWave(EffectMath.WAVE_SAW, 1.75), eps)
        assertEquals(0.5f, EffectMath.swingWave(EffectMath.WAVE_SINE, -0.25), eps)      // отрицательная фаза
    }

    @Test fun swingBetweenTwoAnglesOverTime() {
        val fx = listOf(swing(1f, -30f, 30f))
        val anim = Animation()
        assertEquals(-30f, deg(EffectTracks.swingAngle(1, fx, anim, 0, 24)), eps)
        assertEquals(0f, deg(EffectTracks.swingAngle(1, fx, anim, 6, 24)), eps)
        assertEquals(30f, deg(EffectTracks.swingAngle(1, fx, anim, 12, 24)), eps)
        assertEquals(-30f, deg(EffectTracks.swingAngle(1, fx, anim, 24, 24)), eps)
        // начальная фаза 0.5 - старт с «Угол 2»
        assertEquals(30f, deg(EffectTracks.swingAngle(1, listOf(swing(1f, -30f, 30f, phase = 0.5f)), anim, 0, 24)), eps)
        // частота 0 - стоит на «Угол 1»; Spin и Swing складываются
        assertEquals(-30f, deg(EffectTracks.swingAngle(1, listOf(swing(0f, -30f, 30f)), anim, 50, 24)), eps)
        val both = fx + Effect(EffectMath.SPIN, List(8) { if (it == 0) 90f else 0f })
        assertEquals(30f + 45f, deg(EffectTracks.extraRotation(1, both, anim, 12, 24)), 0.01f)
    }

    @Test fun keyedSwingParametersAreUsed() {
        val layer = 5
        val fx = listOf(swing(1f, 0f, 10f, slot = 0))
        // ключи на «Угол 2» (параметр 2): 10° -> 50° за 24 кадра; частота без ключей
        val anim = Animation().withKey(EffectTracks.id(layer, 0, 2), 0, LayerTransform(dx = 10f))
            .withKey(EffectTracks.id(layer, 0, 2), 24, LayerTransform(dx = 50f))
        val a = deg(EffectTracks.swingAngle(layer, fx, anim, 12, 24))   // полцикла: весь путь к текущему «Угол 2»
        assertTrue("угол $a", a > 10f + eps && a < 50f)
        // ключ частоты: фаза - интеграл, поэтому смена частоты не даёт скачка угла между соседними кадрами
        val fAnim = Animation().withKey(EffectTracks.id(layer, 0, 0), 0, LayerTransform(dx = 0.5f))
            .withKey(EffectTracks.id(layer, 0, 0), 48, LayerTransform(dx = 3f))
        var prev = deg(EffectTracks.swingAngle(layer, fx, fAnim, 0, 24))
        for (f in 1..48) {
            val cur = deg(EffectTracks.swingAngle(layer, fx, fAnim, f, 24))
            assertTrue("скачок на кадре $f: $prev -> $cur", kotlin.math.abs(cur - prev) < 5f)
            prev = cur
        }
    }

    @Test fun stretchMatrixAlongAxis() {
        val h = EffectMath.stretchMatrix(2f, 0f)
        assertEquals(2f, h.a, eps); assertEquals(1f, h.d, eps); assertEquals(0f, h.b, eps)
        val v = EffectMath.stretchMatrix(2f, 90f)
        assertEquals(1f, v.a, eps); assertEquals(2f, v.d, eps)
        val diag = EffectMath.stretchMatrix(3f, 45f)
        assertEquals(3f, diag.a * diag.d - diag.b * diag.c, eps)   // площадь растёт ровно в k раз
        // точка на оси растягивается, поперёк - нет
        assertEquals(3f * 0.7071f, diag.applyX(0.7071f, 0.7071f), 1e-3f)
        assertEquals(-0.7071f, diag.applyX(-0.7071f, 0.7071f), 1e-3f)
        assertNull(EffectTracks.stretch(1, listOf(swing(1f, 0f, 1f)), Animation(), 0))
    }

    @Test fun sceneAppliesStretchAroundPivot() {
        val img = LeafNode(id = 100_001, name = "i", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 0, y = 0, w = 100, h = 100)
        val fx = mapOf(100_001 to listOf(Effect(EffectMath.STRETCH_AXIS, listOf(2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))))
        val st = EffectTracks.stretches(fx, Animation(), 0)
        val sc = SceneBuilder.buildTree(listOf(img), 200, 200, emptyMap(), stretches = st)
        val q = sc.selectionQuads.getValue(100_001)
        // ширина 100 -> 200 вокруг центра 50: от -50 до 150, высота не меняется
        assertEquals(-50f, q[0], eps); assertEquals(0f, q[1], eps)
        assertEquals(150f, q[2], eps); assertEquals(0f, q[3], eps)
        // поворот слоя: ось растяжения в осях слоя поворачивается вместе с ним
        val rot = SceneBuilder.buildTree(listOf(img), 200, 200, mapOf(100_001 to LayerTransform(rotation = (Math.PI / 2).toFloat())), stretches = st)
        val r = rot.selectionQuads.getValue(100_001)
        assertEquals(100f, r[0], eps); assertEquals(-50f, r[1], eps)   // (0,0) -> растяжение (-100,-50) -> поворот 90° -> (100,-50)
    }

    @Test fun newSymbolInstanceHoldsFirstFormWithoutKeys() {
        val plan = SymbolMath.framesPlan(listOf(1, 2, 3), listOf("30", "31", "39"))
        for (f in 0 until 200) {
            // без ключей и без цикла - всегда первая форма, даже если символ анимирован
            val local = SymbolForms.frameAt(emptyList(), f, plan.duration, animated = false, loop = false, markers = plan.markers)
            assertEquals(0, local)
            assertEquals("30", SymbolForms.shownMarker(plan.markers, local)!!.name)
        }
        assertTrue(SymbolMath.formRuns(emptyList(), 100).isEmpty())   // на таймлайне нет «расставленных» форм
        assertEquals(listOf(FrameMarker(0, "30"), FrameMarker(1, "31"), FrameMarker(2, "39")), plan.markers)
    }
}
