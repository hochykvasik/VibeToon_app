package com.vibetoon.domain.vector

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Спринт 40: векторные кисти - штрих «Путь» векторного слоя с переменной толщиной. Чистая математика - Package40VectorBrushTest.
 *
 * Толщина хранится в фигуре множителями по узлам ([VectorShape.widths], ключ проекта "vw"), стиль - [VectorShape.brush]
 * ("vb"). Штрих остаётся вектором: узлы можно двигать, фигура масштабируется и деформируется без потери качества,
 * Undo/Redo - как у любой правки векторного слоя. Пустые [VectorShape.widths] - ровная линия, как раньше.
 *
 *  - [Style.UNIFORM] «Ровная» - постоянная толщина (прежнее поведение);
 *  - [Style.PRESSURE] «По нажиму» - толщина по нажиму пера (и по наклону, если он включён в настройках «Стилус»);
 *  - [Style.INK] «Тушь» - нажим плюс плавное сужение в начале и в конце штриха;
 *  - [Style.CALLIGRAPHY] «Каллиграфия» - плоское перо под 45°: толщина зависит от направления линии (работает и пальцем).
 */
object VectorBrush {
    enum class Style(val id: String, val ru: String) {
        UNIFORM("uniform", "Ровная"), PRESSURE("pressure", "По нажиму"), INK("ink", "Тушь"), CALLIGRAPHY("calligraphy", "Каллиграфия");
        companion object { fun of(id: String?): Style = values().firstOrNull { it.id == id } ?: UNIFORM }
    }

    const val PREF = "vector_brush"
    /** Толщина при нулевом нажиме (доля от толщины кисти). */
    const val MIN_FACTOR = 0.15f
    const val MAX_FACTOR = 4f
    /** Сужение «Туши»: доля длины штриха с каждого конца и минимальная толщина на кончике. */
    const val TAPER_FRACTION = 0.18f
    const val TAPER_TIP = 0.12f
    /** Угол плоского пера каллиграфии (радианы, ось Y вниз) и толщина вдоль пера. */
    val NIB_ANGLE = (-Math.PI / 4).toFloat()
    const val NIB_THIN = 0.2f
    private const val CAP_STEPS = 8

    /** Множители толщины по узлам для стиля [style]: [pressures] - нажим 0..1+ (с наклоном) по узлам; пусто - ровная линия. */
    fun widths(style: Style, pts: List<Float>, pressures: List<Float>): List<Float> {
        val n = pts.size / 2
        if (style == Style.UNIFORM || n < 2) return emptyList()
        val p = List(n) { i -> pressures.getOrNull(i)?.takeIf { it.isFinite() } ?: 1f }
        val byPressure = p.map { (MIN_FACTOR + (1f - MIN_FACTOR) * it.coerceIn(0f, 1f)) * max(1f, it) }
        val out = when (style) {
            Style.PRESSURE -> byPressure
            Style.INK -> {
                val cum = cumulative(pts)
                val total = cum.last()
                val taper = max(total * TAPER_FRACTION, 1e-3f)
                List(n) { i ->
                    val d = min(cum[i], total - cum[i])
                    byPressure[i] * (TAPER_TIP + (1f - TAPER_TIP) * smoothstep((d / taper).coerceIn(0f, 1f)))
                }
            }
            Style.CALLIGRAPHY -> List(n) { i ->
                val a = i.coerceAtMost(n - 2); val b = a + 1
                val dir = atan2(pts[b * 2 + 1] - pts[a * 2 + 1], pts[b * 2] - pts[a * 2])
                (NIB_THIN + (1f - NIB_THIN) * abs(sin(dir - NIB_ANGLE))) * byPressure[i]
            }
            Style.UNIFORM -> emptyList()
        }
        return out.map { (Math.round(it.coerceIn(0.05f, MAX_FACTOR) * 1000f) / 1000f) }
    }

    private fun smoothstep(x: Float) = x * x * (3f - 2f * x)

    /** Накопленная длина до каждого узла. */
    fun cumulative(pts: List<Float>): FloatArray {
        val n = pts.size / 2
        val out = FloatArray(max(n, 1))
        for (i in 1 until n) out[i] = out[i - 1] + hypot(pts[i * 2] - pts[i * 2 - 2], pts[i * 2 + 1] - pts[i * 2 - 1])
        return out
    }

    /** Как [VectorMath.simplify], но множители толщины остаются при своих узлах. */
    fun simplify(pts: List<Float>, w: List<Float>, minDist: Float): Pair<List<Float>, List<Float>> {
        if (w.size != pts.size / 2) return VectorMath.simplify(pts, minDist) to emptyList()
        if (pts.size <= 4) return pts to w
        val op = ArrayList<Float>(); val ow = ArrayList<Float>()
        op += pts[0]; op += pts[1]; ow += w[0]
        var i = 2
        while (i < pts.size - 2) {
            if (hypot(pts[i] - op[op.size - 2], pts[i + 1] - op[op.size - 1]) >= minDist) { op += pts[i]; op += pts[i + 1]; ow += w[i / 2] }
            i += 2
        }
        op += pts[pts.size - 2]; op += pts[pts.size - 1]; ow += w[w.size - 1]
        return op to ow
    }

    /** Переменная толщина применима: путь, множитель на каждый узел. */
    fun hasWidths(s: VectorShape): Boolean = s.kind == VecKind.PATH && s.widths.size == s.pts.size / 2 && s.widths.size >= 2

    /** Полутолщина узла в px холста. */
    private fun half(base: Float, k: Float) = max(0.25f, base * k.coerceIn(0.05f, MAX_FACTOR) / 2f)

    /**
     * Контур штриха переменной толщины (замкнутый многоугольник x0,y0,...): левая сторона, круглый конец, правая сторона
     * обратно, круглое начало. Перед построением один проход сглаживания Чайкина (точки и толщины вместе). Пусто - не путь с толщинами.
     */
    fun outline(s: VectorShape): List<Float> {
        if (!hasWidths(s) || s.width <= 0f) return emptyList()
        val (p, w) = chaikin(s.pts, s.widths)
        val n = p.size / 2
        val nx = FloatArray(n); val ny = FloatArray(n)
        for (i in 0 until n) {
            val a = max(i - 1, 0); val b = min(i + 1, n - 1)
            var dx = p[b * 2] - p[a * 2]; var dy = p[b * 2 + 1] - p[a * 2 + 1]
            val l = hypot(dx, dy)
            if (l < 1e-6f) { dx = 1f; dy = 0f } else { dx /= l; dy /= l }
            nx[i] = -dy; ny[i] = dx
        }
        val out = ArrayList<Float>(n * 4 + CAP_STEPS * 4)
        for (i in 0 until n) { val h = half(s.width, w[i]); out += p[i * 2] + nx[i] * h; out += p[i * 2 + 1] + ny[i] * h }
        cap(out, p[(n - 1) * 2], p[(n - 1) * 2 + 1], nx[n - 1], ny[n - 1], half(s.width, w[n - 1]))
        for (i in n - 1 downTo 0) { val h = half(s.width, w[i]); out += p[i * 2] - nx[i] * h; out += p[i * 2 + 1] - ny[i] * h }
        cap(out, p[0], p[1], -nx[0], -ny[0], half(s.width, w[0]))
        return out
    }

    /** Полукруг от +нормали к -нормали вокруг точки (снаружи штриха). */
    private fun cap(out: ArrayList<Float>, cx: Float, cy: Float, nx: Float, ny: Float, h: Float) {
        val a0 = atan2(ny, nx)
        for (k in 1 until CAP_STEPS) {
            val a = a0 - (Math.PI * k / CAP_STEPS).toFloat()
            out += cx + cos(a) * h; out += cy + sin(a) * h
        }
    }

    /** Один проход Чайкина: концы остаются на месте, толщины интерполируются вместе с точками. */
    fun chaikin(pts: List<Float>, w: List<Float>): Pair<List<Float>, List<Float>> {
        val n = pts.size / 2
        if (n < 3 || w.size != n) return pts to w
        val op = ArrayList<Float>(n * 4); val ow = ArrayList<Float>(n * 2)
        op += pts[0]; op += pts[1]; ow += w[0]
        for (i in 0 until n - 1) {
            val x0 = pts[i * 2]; val y0 = pts[i * 2 + 1]; val x1 = pts[i * 2 + 2]; val y1 = pts[i * 2 + 3]
            op += x0 * 0.75f + x1 * 0.25f; op += y0 * 0.75f + y1 * 0.25f; ow += w[i] * 0.75f + w[i + 1] * 0.25f
            op += x0 * 0.25f + x1 * 0.75f; op += y0 * 0.25f + y1 * 0.75f; ow += w[i] * 0.25f + w[i + 1] * 0.75f
        }
        op += pts[pts.size - 2]; op += pts[pts.size - 1]; ow += w[n - 1]
        return op to ow
    }

    /** Множители после удаления узла [k] (у пути без толщин - пусто). */
    fun withoutNode(w: List<Float>, nodes: Int, k: Int): List<Float> =
        if (w.size != nodes || k !in w.indices) emptyList() else w.toMutableList().also { it.removeAt(k) }

    /** Штрих из жеста: [pressures] по точкам жеста; null - жест слишком мал. */
    fun stroke(style: Style, pts: List<Float>, pressures: List<Float>, stroke: Long, fill: Long, width: Float): VectorShape? {
        if (pts.size < 4) return null
        val raw = widths(style, pts, pressures)
        val (sp, sw) = if (raw.isEmpty()) VectorMath.simplify(pts, VectorMath.MIN_POINT_DIST) to emptyList() else simplify(pts, raw, VectorMath.MIN_POINT_DIST)
        if (sp.size < 4) return null
        val b = VectorMath.bounds(sp)
        if (abs(b[2] - b[0]) + abs(b[3] - b[1]) < VectorMath.MIN_EXTENT) return null
        return VectorShape(VecKind.PATH, sp, stroke, fill, width, widths = sw, brush = if (style == Style.UNIFORM) "" else style.id)
    }
}
