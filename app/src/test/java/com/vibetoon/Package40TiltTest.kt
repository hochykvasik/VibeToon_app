package com.vibetoon

import com.vibetoon.domain.input.TiltMath
import com.vibetoon.domain.paint.BrushParams
import com.vibetoon.domain.paint.DabRotation
import com.vibetoon.domain.paint.DabStroker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 40: наклон пера меняет кисть - толщина, штриховка (плотность), плоское перо (сплющивание и поворот). */
class Package40TiltTest {
    private val rad45 = (Math.PI / 4).toFloat()
    private val rad60 = (Math.PI / 3).toFloat()

    @Test fun noSensorOrOffModeKeepsBrushUnchanged() {
        assertEquals(0f, TiltMath.norm(Float.NaN), 0f)
        assertEquals(0f, TiltMath.norm(0f), 0f)
        assertEquals(0f, TiltMath.norm(-1f), 0f)
        val off = TiltMath.settings(0, 100)
        assertEquals(1f, TiltMath.sizeFactor(off, 1f), 0f)
        assertEquals(1f, TiltMath.flowFactor(off, 1f), 0f)
        assertEquals(0.7f, TiltMath.roundness(off, 1f, 0.7f), 0f)
        assertTrue(TiltMath.angle(off, 1f).isNaN())
        // сила 0 - тоже без изменений
        val zero = TiltMath.settings(TiltMath.Mode.SIZE.id, 0)
        assertEquals(1f, TiltMath.sizeFactor(zero, 1f), 0f)
        // неизвестный режим из старой/чужой копии настроек - «Не влияет»
        assertEquals(TiltMath.Mode.OFF, TiltMath.Mode.of(42))
    }

    @Test fun tiltNormalisesToSixtyDegrees() {
        assertEquals(0.75f, TiltMath.norm(rad45), 1e-3f)
        assertEquals(1f, TiltMath.norm(rad60), 1e-3f)
        assertEquals(1f, TiltMath.norm(1.5f), 0f)   // сильнее 60° - максимум
    }

    @Test fun sizeModeWidensWithTiltAndStrength() {
        val s = TiltMath.settings(TiltMath.Mode.SIZE.id, 100)
        assertEquals(1f, TiltMath.sizeFactor(s, 0f), 1e-4f)
        assertEquals(2.5f, TiltMath.sizeFactor(s, 1f), 1e-4f)
        val half = TiltMath.settings(TiltMath.Mode.SIZE.id, 50)
        assertEquals(1.75f, TiltMath.sizeFactor(half, 1f), 1e-4f)
        assertEquals(1f, TiltMath.flowFactor(s, 1f), 0f)   // толщина не трогает плотность
        assertTrue(TiltMath.sizeFactor(s, 0.3f) < TiltMath.sizeFactor(s, 0.6f))
    }

    @Test fun shadingIsWiderAndLighter() {
        val s = TiltMath.settings(TiltMath.Mode.SHADING.id, 100)
        assertEquals(3.5f, TiltMath.sizeFactor(s, 1f), 1e-4f)
        assertEquals(0.3f, TiltMath.flowFactor(s, 1f), 1e-4f)
        assertEquals(1f, TiltMath.flowFactor(s, 0f), 1e-4f)
        assertTrue(TiltMath.flowFactor(s, 1f) >= TiltMath.MIN_FLOW)
    }

    @Test fun flatNibFlattensAndFollowsPenDirection() {
        val s = TiltMath.settings(TiltMath.Mode.ANGLE.id, 100)
        assertEquals(1f, TiltMath.roundness(s, 0f, 1f), 1e-4f)
        assertEquals(TiltMath.MIN_ROUNDNESS, TiltMath.roundness(s, 1f, 1f), 1e-4f)
        // уже сплющенная кисть (квадратный маркер 0.4) не становится круглее
        assertEquals(0.4f, TiltMath.roundness(s, 0.1f, 0.4f), 1e-4f)
        // перо смотрит вверх -> длинная ось штампа вертикальна (-90° = 270°), вправо -> 0°
        assertEquals((3 * Math.PI / 2).toFloat(), TiltMath.angle(s, 0f), 1e-4f)
        assertEquals(0f, TiltMath.angle(s, (Math.PI / 2).toFloat()), 1e-4f)
        // поворот холста вычитается
        assertEquals((3 * Math.PI / 2).toFloat(), TiltMath.angle(s, (Math.PI / 2).toFloat(), (Math.PI / 2).toFloat()), 1e-4f)
        assertTrue(TiltMath.angle(s, Float.NaN).isNaN())
        assertEquals(1f, TiltMath.sizeFactor(s, 1f), 0f)
    }

    @Test fun smoothingDampsSensorJitter() {
        assertEquals(0.5f, TiltMath.smooth(Float.NaN, 0.5f), 0f)
        assertEquals(0.4f, TiltMath.smooth(0.4f, Float.NaN), 0f)
        val v = TiltMath.smooth(0f, 1f)
        assertTrue(v > 0f && v < 1f)
    }

    @Test fun describeExplainsEffect() {
        assertEquals("Датчика наклона нет: кисть рисует без изменений", TiltMath.describe(TiltMath.settings(1, 60), Float.NaN))
        assertEquals("Наклон пера 45°: не влияет на кисть (выберите режим ниже)", TiltMath.describe(TiltMath.settings(0, 60), rad45))
        assertEquals("Наклон пера 60° → толщина ×2.5", TiltMath.describe(TiltMath.settings(1, 100), rad60))
        assertEquals("Наклон пера 60° → штриховка: толщина ×3.5, плотность 30 %", TiltMath.describe(TiltMath.settings(2, 100), rad60))
        assertEquals("Наклон пера 60° → штамп сплющен до 20 % и повёрнут по перу", TiltMath.describe(TiltMath.settings(3, 100), rad60))
    }

    private fun stroke(st: DabStroker): FloatArray {
        val out = ArrayList<Float>()
        out += st.begin(0f, 0f, 1f).toList()
        for (i in 1..20) out += st.add(i * 10f, 0f, 1f).toList()
        out += st.end().toList()
        return out.toFloatArray()
    }

    @Test fun stockStrokerIsIdenticalWithNeutralTilt() {
        val p = BrushParams(size = 20f, rotation = DabRotation.NONE)
        val a = stroke(DabStroker(p))
        val b = stroke(DabStroker(p).also { it.setTilt(1f, 1f, Float.NaN) })
        assertArrayEquals(a, b, 0f)
    }

    @Test fun strokerAppliesTiltToRadiusFlowAndRotation() {
        val p = BrushParams(size = 20f, flow = 0.8f, rotation = DabRotation.TRAJECTORY)
        val st = DabStroker(p).also { it.setTilt(2f, 0.5f, 1.25f) }
        val d = stroke(st)
        val n = d.size / DabStroker.STRIDE
        assertTrue(n > 3)
        for (i in 0 until n) {
            val o = i * DabStroker.STRIDE
            assertEquals(20f, d[o + 2], 1e-3f)     // радиус 10 × 2
            assertEquals(0.4f, d[o + 3], 1e-4f)    // flow 0.8 × 0.5
            assertEquals(1.25f, d[o + 4], 1e-4f)   // поворот по перу вместо траектории
        }
        // шире штамп - реже шаг: штампов меньше, чем у кисти без наклона
        val plain = stroke(DabStroker(p)).size / DabStroker.STRIDE
        assertTrue(n < plain)
    }

    @Test fun tiltChangesGraduallyAlongStroke() {
        val st = DabStroker(BrushParams(size = 10f))
        val out = ArrayList<Float>()
        out += st.begin(0f, 0f, 1f).toList()
        for (i in 1..30) { st.setTilt(1f + i / 10f, 1f, Float.NaN); out += st.add(i * 6f, 0f, 1f).toList() }
        out += st.end().toList()
        val radii = out.toFloatArray().let { d -> List(d.size / DabStroker.STRIDE) { d[it * DabStroker.STRIDE + 2] } }
        // радиус растёт плавно, без скачков больше одного шага множителя
        for (i in 1 until radii.size) assertTrue(radii[i] + 1e-3f >= radii[i - 1] && radii[i] - radii[i - 1] < 1.2f)
        assertTrue(radii.last() > radii.first() * 2f)
    }
}
