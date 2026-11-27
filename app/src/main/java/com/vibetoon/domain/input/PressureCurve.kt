package com.vibetoon.domain.input

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stylus sprint: кривая чувствительности пера («Чувствительность стилуса»).
 * Отображает нажим пера 0..1 в нажим кисти 0..1 по опорным точкам: монотонный кубический сплайн
 * (Фрича-Карлсона), без выбросов за пределы соседних точек; вход и выход всегда ограничены [0, 1].
 * Первая точка всегда x = 0, последняя x = 1; между ними до [MAX_POINTS] - 2 редактируемых узлов.
 * Чистый класс без Android - unit-тест PressureCurveTest.
 */
class PressureCurve private constructor(val points: List<Point>) {
    data class Point(val x: Float, val y: Float)

    /** Готовые кривые от мягкой (лёгкое касание даёт толстую линию) до жёсткой (нужно сильно давить). */
    enum class Preset(val id: String, val title: String, val pts: List<Point>) {
        VERY_SOFT("very_soft", "Очень мягкая", listOf(Point(0f, 0f), Point(0.2f, 0.55f), Point(1f, 1f))),
        SOFT("soft", "Мягкая кривая", listOf(Point(0f, 0f), Point(0.35f, 0.55f), Point(1f, 1f))),
        LINEAR("linear", "Линейная", listOf(Point(0f, 0f), Point(1f, 1f))),
        FIRM("firm", "Жёсткая кривая", listOf(Point(0f, 0f), Point(0.6f, 0.4f), Point(1f, 1f))),
        VERY_FIRM("very_firm", "Очень жёсткая", listOf(Point(0f, 0f), Point(0.75f, 0.28f), Point(1f, 1f)));

        val curve: PressureCurve get() = of(pts)
    }

    private val tangents: FloatArray = monotoneTangents(points)

    /** Нажим кисти для нажима пера [pressure]; NaN/бесконечность считаются полным нажимом (безопасный откат). */
    fun map(pressure: Float): Float {
        val p = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 1f
        val n = points.size
        var i = 0
        while (i < n - 2 && p > points[i + 1].x) i++
        val a = points[i]; val b = points[i + 1]
        val h = b.x - a.x
        if (h <= 1e-6f) return b.y.coerceIn(0f, 1f)
        val t = (p - a.x) / h
        val t2 = t * t; val t3 = t2 * t
        val y = (2 * t3 - 3 * t2 + 1) * a.y + (t3 - 2 * t2 + t) * h * tangents[i] +
            (-2 * t3 + 3 * t2) * b.y + (t3 - t2) * h * tangents[i + 1]
        return y.coerceIn(0f, 1f)
    }

    /** Точки для рисования графика: [count] + 1 значений y на равномерной сетке x. */
    fun sample(count: Int = 48): FloatArray = FloatArray(count.coerceAtLeast(1) + 1) { i -> map(i / count.coerceAtLeast(1).toFloat()) }

    /** Перемещение узла: концы двигаются только по вертикали, внутренние не перескакивают соседей. */
    fun move(index: Int, x: Float, y: Float): PressureCurve {
        if (index !in points.indices) return this
        val list = points.toMutableList()
        val ny = clamp01(y)
        val nx = when (index) {
            0 -> 0f
            points.lastIndex -> 1f
            else -> clamp01(x).coerceIn(points[index - 1].x + MIN_GAP, points[index + 1].x - MIN_GAP)
        }
        list[index] = Point(nx, ny)
        return PressureCurve(list)
    }

    /** Новый узел на кривой в точке x (не больше [MAX_POINTS]); возвращает кривую и индекс узла. */
    fun insert(x: Float, y: Float): Pair<PressureCurve, Int> {
        if (points.size >= MAX_POINTS) return this to -1
        val cx = clamp01(x)
        val at = points.indexOfFirst { it.x > cx }.takeIf { it > 0 } ?: return this to -1
        if (cx - points[at - 1].x < MIN_GAP || points[at].x - cx < MIN_GAP) return this to -1
        val list = points.toMutableList()
        list.add(at, Point(cx, clamp01(y)))
        return PressureCurve(list) to at
    }

    /** Удаление внутреннего узла (концы не удаляются). */
    fun remove(index: Int): PressureCurve =
        if (index <= 0 || index >= points.lastIndex) this else PressureCurve(points.filterIndexed { i, _ -> i != index })

    /** Ближайший к (x, y) узел в пределах [radius] (в единицах графика 0..1) или -1. */
    fun nearest(x: Float, y: Float, radius: Float): Int {
        var best = -1; var bd = radius
        for ((i, pt) in points.withIndex()) {
            val d = sqrt((pt.x - x) * (pt.x - x) + (pt.y - y) * (pt.y - y))
            if (d <= bd) { bd = d; best = i }
        }
        return best
    }

    /** Совпадающий пресет или null (пользовательская кривая). */
    fun preset(): Preset? = Preset.entries.firstOrNull { pr ->
        pr.pts.size == points.size && pr.pts.zip(points).all { (a, b) -> abs(a.x - b.x) < 1e-3f && abs(a.y - b.y) < 1e-3f }
    }

    /** Строка для настроек и резервной копии: «x,y;x,y;…» с тремя знаками. */
    fun encode(): String = points.joinToString(";") { "${fmt(it.x)},${fmt(it.y)}" }

    override fun equals(other: Any?): Boolean = other is PressureCurve && other.points == points
    override fun hashCode(): Int = points.hashCode()
    override fun toString(): String = "PressureCurve(${encode()})"

    companion object {
        const val MAX_POINTS = 6
        const val MIN_GAP = 0.04f
        val LINEAR: PressureCurve get() = Preset.LINEAR.curve
        val DEFAULT_ENCODED: String get() = LINEAR.encode()

        /** Нормализованная кривая: значения в [0, 1], сортировка по x, концы x = 0 и x = 1, близкие узлы слиты. */
        fun of(raw: List<Point>): PressureCurve {
            val inner = raw.filter { it.x.isFinite() && it.y.isFinite() }.map { Point(clamp01(it.x), clamp01(it.y)) }.sortedBy { it.x }
            val start = inner.firstOrNull { it.x <= 0f }?.y ?: 0f
            val end = inner.lastOrNull { it.x >= 1f }?.y ?: 1f
            val out = ArrayList<Point>()
            out += Point(0f, start)
            for (pt in inner) {
                if (pt.x <= MIN_GAP / 2 || pt.x >= 1f - MIN_GAP / 2) continue
                if (pt.x - out.last().x < MIN_GAP) continue
                if (out.size >= MAX_POINTS - 1) break
                out += pt
            }
            if (1f - out.last().x < MIN_GAP && out.size > 1) out.removeAt(out.lastIndex)
            out += Point(1f, end)
            return PressureCurve(out)
        }

        /** Разбор строки настроек; повреждённое значение - линейная кривая (как без кривой). */
        fun decode(text: String?): PressureCurve {
            if (text.isNullOrBlank()) return LINEAR
            Preset.entries.firstOrNull { it.id == text.trim() }?.let { return it.curve }
            val pts = text.split(';').mapNotNull { part ->
                val xy = part.split(',')
                if (xy.size != 2) return@mapNotNull null
                val x = xy[0].trim().toFloatOrNull(); val y = xy[1].trim().toFloatOrNull()
                if (x == null || y == null || !x.isFinite() || !y.isFinite()) null else Point(x, y)
            }
            return if (pts.size < 2) LINEAR else of(pts)
        }

        private fun clamp01(v: Float) = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
        private fun fmt(v: Float): String {
            val r = Math.round(v * 1000f) / 1000f
            return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }

        /** Касательные Фрича-Карлсона: на экстремумах и плоских участках 0, иначе ограничены, чтобы не было выбросов. */
        private fun monotoneTangents(p: List<Point>): FloatArray {
            val n = p.size
            val d = FloatArray(n - 1) { i -> (p[i + 1].y - p[i].y) / max(1e-6f, p[i + 1].x - p[i].x) }
            val m = FloatArray(n)
            m[0] = d[0]; m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            for (i in 0 until n - 1) {
                if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
                val a = m[i] / d[i]; val b = m[i + 1] / d[i]
                if (a < 0f) m[i] = 0f
                if (b < 0f) m[i + 1] = 0f
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
                }
            }
            return m
        }
    }
}
