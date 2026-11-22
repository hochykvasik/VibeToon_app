package com.vibetoon

import com.vibetoon.domain.paint.BrushParams
import com.vibetoon.domain.paint.BrushPresets
import com.vibetoon.domain.paint.GrainMath
import com.vibetoon.domain.paint.GrainMode
import com.vibetoon.domain.paint.StrokeMode
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Карандаш (Sprint 6.9.1): CPU-копия шейдера штампа (kDabFrag в Paint.cpp) рисует прямую линию и меряет, видна ли
 * фактура. Старый пресет (MOVING + ACCUMULATE) давал почти сплошную линию - как Перо; новый (CANVAS + CAP_OPACITY) - зерно.
 */
class PencilGrainTest {
    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    /** Альфа пикселей средней строки линии длиной 200 px. */
    private fun strokeRow(p: BrushParams): FloatArray {
        val tex = GrainMath.texture(p.grain)!!
        val n = GrainMath.SIZE
        fun grain(u: Float, v: Float): Float {
            val x = Math.floorMod(floor(u * n).toInt(), n); val y = Math.floorMod(floor(v * n).toInt(), n)
            val g = (tex[y * n + x].toInt() and 0xFF) / 255f
            return ((g - 0.5f) * p.grainContrast + 0.5f).coerceIn(0f, 1f)
        }
        val w = 260; val h = 40; val r = p.size / 2f
        val dst = FloatArray(w * h)
        val rnd = java.util.Random(7)
        val step = maxOf(1f, p.size * p.spacing)
        var cx = 30f
        while (cx <= 230f) {
            val rot = rnd.nextFloat() * 6.2831855f
            val cs = cos(rot); val sn = sin(rot)
            for (py in 0 until h) for (px in 0 until w) {
                val dx = px + 0.5f - cx; val dy = py + 0.5f - 20f
                if (hypot(dx, dy) > r + 2f) continue
                val qx = cs * dx + sn * dy; val qy = -sn * dx + cs * dy   // в осях штампа
                val soft = maxOf(r * (1f - p.hardness), 1f)
                var a = smooth(((r + 0.5f - sqrt(qx * qx + qy * qy)) / soft).coerceIn(0f, 1f))
                val gs = GrainMath.SIZE * maxOf(0.05f, p.grainScale)
                a *= if (p.grainMode == GrainMode.MOVING) grain(qx / gs + 0.5f, qy / gs + 0.5f) else grain((px + 0.5f) / gs, (py + 0.5f) / gs)
                a *= p.flow
                val i = py * w + px
                dst[i] = if (p.mode == StrokeMode.ACCUMULATE) a + dst[i] * (1f - a) else maxOf(dst[i], a)
            }
            cx += step
        }
        return FloatArray(160) { dst[20 * w + 50 + it] }
    }

    private fun stats(a: FloatArray): Pair<Float, Float> {
        val m = a.average().toFloat()
        return m to sqrt(a.map { (it - m) * (it - m) }.average()).toFloat()
    }

    @Test fun oldPresetWasAlmostSolid() {
        val old = BrushParams(size = 12f, spacing = 0.12f, minSizePercent = 0.35f, flow = 0.55f, hardness = 0.7f, mode = StrokeMode.ACCUMULATE,
            grain = com.vibetoon.domain.paint.GrainKind.PENCIL, grainMode = GrainMode.MOVING, grainScale = 1f, grainContrast = 1.4f)
        val (mean, sd) = stats(strokeRow(old))
        assertTrue("старый карандаш: среднее $mean, разброс $sd", mean > 0.9f && sd < 0.05f)
    }

    @Test fun newPencilShowsPaperGrain() {
        val row = strokeRow(BrushPresets.byId("pencil")!!.params)
        val (mean, sd) = stats(row)
        assertTrue("карандаш: среднее $mean, разброс $sd", sd > 0.12f)
        assertTrue("есть просветы бумаги: min ${row.min()}", row.min() < 0.35f)
        assertTrue("и плотные места: max ${row.max()}", row.max() > 0.8f)
        assertTrue("линия не пропадает: $mean", mean in 0.35f..0.85f)
    }

    @Test fun penStaysSolidForComparison() {
        val p = BrushPresets.byId("pen")!!.params
        assertTrue(p.grainMode == GrainMode.NONE)
    }
}
