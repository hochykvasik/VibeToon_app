package com.vibetoon.domain.anim

import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorMath
import com.vibetoon.domain.vector.VectorShape
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Спринт 38: слой-путь (MediaKind.PATH). Кривая лежит в векторах слоя (координаты холста); слои, прикреплённые к пути,
 * ставят свою опорную точку в точку пути на «Положении» 0..100 % длины и (по желанию) поворачиваются по касательной.
 * Положение - анимируемая дорожка под id [progressId] (значение в LayerTransform.dx, проценты): ключи, плавность,
 * Undo/Redo и сохранение - как у остальных дорожек слоя.
 */
object PathLayerMath {
    const val ID_BASE = 600_000_000
    private const val SPAN = 100_000_000

    fun progressId(layerId: Int): Int = ID_BASE + layerId
    fun isProgressId(id: Int): Boolean = id >= ID_BASE && id < ID_BASE + SPAN
    fun layerOf(id: Int): Int = id - ID_BASE

    /** Ломаная пути (x0,y0,x1,y1,...) с накопленной длиной; [closed] - замкнутый контур (по кругу). */
    class Path(val pts: FloatArray, val closed: Boolean) {
        val count: Int get() = pts.size / 2
        /** Накопленная длина до каждой вершины (у замкнутого - ещё отрезок от последней к первой). */
        val cum: FloatArray
        val length: Float

        init {
            val n = count
            val segs = if (closed && n >= 2) n else (n - 1).coerceAtLeast(0)
            cum = FloatArray(segs + 1)
            for (i in 0 until segs) {
                val a = i; val b = (i + 1) % n
                cum[i + 1] = cum[i] + hypot(pts[b * 2] - pts[a * 2], pts[b * 2 + 1] - pts[a * 2 + 1])
            }
            length = cum.last()
        }
    }

    /** Точка пути и угол касательной (радианы, как LayerTransform.rotation). */
    data class Place(val x: Float, val y: Float, val angle: Float)

    /** Кривая из векторной фигуры: линия, ломаная/контур пера (с ручками Безье), прямоугольник, эллипс. null - не путь. */
    fun path(shape: VectorShape): Path? {
        val p = shape.pts
        if (p.size < 4 || p.any { !it.isFinite() }) return null
        val out = when (shape.kind) {
            VecKind.PATH, VecKind.LINE -> p.toFloatArray() to false
            VecKind.POLY, VecKind.POLYGON -> bezier(p, shape.handles, shape.kind == VecKind.POLYGON) to (shape.kind == VecKind.POLYGON)
            VecKind.RECT -> floatArrayOf(p[0], p[1], p[2], p[1], p[2], p[3], p[0], p[3]) to true
            VecKind.ELLIPSE -> ellipse((p[0] + p[2]) / 2f, (p[1] + p[3]) / 2f, kotlin.math.abs(p[2] - p[0]) / 2f, kotlin.math.abs(p[3] - p[1]) / 2f) to true
            VecKind.PRESET, VecKind.BOOL -> return null
        }
        val path = Path(out.first, out.second)
        return if (path.length > 1e-3f) path else null
    }

    /** Первая фигура слоя, из которой получается путь. */
    fun pathOf(shapes: List<VectorShape>): Path? = shapes.firstNotNullOfOrNull { path(it) }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int = 72): FloatArray =
        FloatArray(n * 2) { k ->
            val a = (k / 2) * 2.0 * Math.PI / n - Math.PI / 2   // старт сверху, по часовой (ось y вниз)
            if (k % 2 == 0) (cx + rx * cos(a)).toFloat() else (cy + ry * sin(a)).toFloat()
        }

    /** Узлы пера с ручками (по 4 числа на узел: вход dx,dy и выход dx,dy) - в ломаную по 16 точек на сегмент. */
    private fun bezier(p: List<Float>, h: List<Float>, closed: Boolean): FloatArray {
        val n = p.size / 2
        if (h.size != n * 4) return p.toFloatArray()
        val out = ArrayList<Float>()
        val segs = if (closed) n else n - 1
        out += p[0]; out += p[1]
        for (i in 0 until segs) {
            val a = i; val b = (i + 1) % n
            val x0 = p[a * 2]; val y0 = p[a * 2 + 1]; val x3 = p[b * 2]; val y3 = p[b * 2 + 1]
            val x1 = x0 + h[a * 4 + 2]; val y1 = y0 + h[a * 4 + 3]
            val x2 = x3 + h[b * 4]; val y2 = y3 + h[b * 4 + 1]
            for (s in 1..16) {
                val t = s / 16f; val u = 1f - t
                out += u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
                out += u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
            }
        }
        if (closed && out.size >= 4) { out.removeAt(out.size - 1); out.removeAt(out.size - 1) }   // последняя = первая
        return out.toFloatArray()
    }

    /**
     * Точка на [percent] % длины. Открытый путь: меньше 0 - начало, больше 100 - конец; замкнутый - по кругу
     * (110 % = 10 %, удобно для циклов). Угол - направление пути в этой точке.
     */
    fun place(path: Path, percent: Float): Place {
        val n = path.count
        if (n == 0) return Place(0f, 0f, 0f)
        if (n == 1 || path.length <= 0f) return Place(path.pts[0], path.pts[1], 0f)
        val f0 = if (percent.isFinite()) percent / 100f else 0f
        val f = if (path.closed) (f0 - kotlin.math.floor(f0)) else f0.coerceIn(0f, 1f)
        val d = f * path.length
        val cum = path.cum
        var i = java.util.Arrays.binarySearch(cum, d).let { if (it >= 0) it else -it - 2 }
        i = i.coerceIn(0, cum.size - 2)
        val a = i; val b = (i + 1) % n
        val seg = cum[i + 1] - cum[i]
        val t = if (seg <= 1e-6f) 0f else ((d - cum[i]) / seg).coerceIn(0f, 1f)
        val ax = path.pts[a * 2]; val ay = path.pts[a * 2 + 1]; val bx = path.pts[b * 2]; val by = path.pts[b * 2 + 1]
        return Place(ax + (bx - ax) * t, ay + (by - ay) * t, atan2(by - ay, bx - ax))
    }

    /** Рамка пути для слоя (x, y, w, h) с полями [pad]; не меньше 2×2. */
    fun bounds(path: Path, pad: Float): FloatArray {
        val b = VectorMath.bounds(path.pts.asList())
        val x = b[0] - pad; val y = b[1] - pad
        return floatArrayOf(x, y, (b[2] - b[0] + 2 * pad).coerceAtLeast(2f), (b[3] - b[1] + 2 * pad).coerceAtLeast(2f))
    }

    /** Нарисованный пальцем путь: шум убирается, углы сглаживаются (2 прохода Чайкина). null - слишком короткий жест. */
    fun fromStroke(pts: List<Float>, minLength: Float = 12f): VectorShape? {
        if (pts.size < 4 || pts.any { !it.isFinite() }) return null
        var s = VectorMath.simplify(pts, 6f)
        if (Path(s.toFloatArray(), false).length < minLength) return null
        repeat(2) { s = chaikin(s) }
        return VectorShape(VecKind.PATH, s, STROKE, 0L, 4f)
    }

    private fun chaikin(p: List<Float>): List<Float> {
        val n = p.size / 2
        if (n < 3) return p
        val out = ArrayList<Float>(p.size * 2)
        out += p[0]; out += p[1]
        for (i in 0 until n - 1) {
            val x0 = p[i * 2]; val y0 = p[i * 2 + 1]; val x1 = p[i * 2 + 2]; val y1 = p[i * 2 + 3]
            out += 0.75f * x0 + 0.25f * x1; out += 0.75f * y0 + 0.25f * y1
            out += 0.25f * x0 + 0.75f * x1; out += 0.25f * y0 + 0.75f * y1
        }
        out += p[p.size - 2]; out += p[p.size - 1]
        return out
    }

    val PRESETS = listOf("line", "arc", "wave", "circle")

    /** Готовые пути по холсту [cw]×[ch]: прямая, дуга, волна, круг. */
    fun preset(kind: String, cw: Float, ch: Float): VectorShape {
        val x0 = cw * 0.15f; val x1 = cw * 0.85f; val cy = ch / 2f
        val pts: List<Float> = when (kind) {
            "arc" -> (0..32).flatMap { k ->
                val a = Math.PI + Math.PI * k / 32.0   // верхняя половина: слева направо через верх
                listOf((cw / 2f + (x1 - x0) / 2f * cos(a)).toFloat(), (cy + ch * 0.3f + ch * 0.45f * sin(a)).toFloat())
            }
            "wave" -> (0..48).flatMap { k ->
                val t = k / 48f
                listOf(x0 + (x1 - x0) * t, cy - ch * 0.15f * sin(t * 4 * Math.PI).toFloat())
            }
            "circle" -> return VectorShape(VecKind.ELLIPSE, listOf(cw / 2f - minOf(cw, ch) * 0.3f, cy - minOf(cw, ch) * 0.3f,
                cw / 2f + minOf(cw, ch) * 0.3f, cy + minOf(cw, ch) * 0.3f), STROKE, 0L, 4f)
            else -> listOf(x0, cy, x1, cy)
        }
        return VectorShape(if (kind == "line" || kind !in PRESETS) VecKind.LINE else VecKind.PATH, pts, STROKE, 0L, 4f)
    }

    private const val STROKE = 0xFF42A5F5L

    /** Мир слоя по его рамке на холсте: точка в координатах слоя (x, y, w, h - его прямоугольник) -> холст. null - вырожденная рамка. */
    fun quadMap(q: FloatArray, x: Float, y: Float, w: Float, h: Float): com.vibetoon.domain.model.Affine2D? {
        if (q.size < 8 || w <= 0f || h <= 0f || q.any { !it.isFinite() }) return null
        val a = (q[2] - q[0]) / w; val b = (q[3] - q[1]) / w
        val c = (q[6] - q[0]) / h; val d = (q[7] - q[1]) / h
        if (kotlin.math.abs(a * d - b * c) < 1e-9f) return null
        return com.vibetoon.domain.model.Affine2D(a, b, c, d, q[0] - a * x - c * y, q[1] - b * x - d * y)
    }
}
