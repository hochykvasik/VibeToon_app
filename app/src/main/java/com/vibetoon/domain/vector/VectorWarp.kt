package com.vibetoon.domain.vector

import com.vibetoon.domain.paint.ShapeKind
import com.vibetoon.domain.paint.ShapeMath
import com.vibetoon.domain.raster.TransformBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Спринт 36: наклон (Skew), искажение (Distort) и любое плоское преобразование векторных фигур - без растеризации.
 * Узлы переводятся картой точно, ручки Безье - как разность образов конца ручки и узла (для аффинных карт это точно,
 * для перспективы - с точностью до кривизны карты на длине ручки). Прямоугольник, эллипс, линия и фигуры-пресеты
 * перед наклоном/искажением становятся контурами пера (наклонённый прямоугольник - уже не прямоугольник), поэтому
 * после операции их узлы и ручки можно править как обычно. Части булевых операций преобразуются вместе с фигурой.
 */
object VectorWarp {
    const val KAPPA = 0.5522847498
    /** Наклон ограничен ±[MAX_SKEW_DEG]: дальше tan растёт без меры и фигура вытягивается в линию. */
    const val MAX_SKEW_DEG = 80.0

    /** Карта плоскости в пикселях холста. */
    fun interface PointMap { fun map(x: Double, y: Double): DoubleArray }

    fun affine(a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double) =
        PointMap { x, y -> doubleArrayOf(a * x + c * y + tx, b * x + d * y + ty) }

    /** Наклон вокруг точки (cx, cy): x += tan(kx)*(y - cy), затем y += tan(ky)*(x - cx). null - угол вне ±80° или NaN. */
    fun skewMap(kxDeg: Double, kyDeg: Double, cx: Double, cy: Double): PointMap? {
        if (!kxDeg.isFinite() || !kyDeg.isFinite() || abs(kxDeg) > MAX_SKEW_DEG || abs(kyDeg) > MAX_SKEW_DEG) return null
        val sx = tan(Math.toRadians(kxDeg)); val sy = tan(Math.toRadians(kyDeg))
        return PointMap { x, y ->
            val nx = x + sx * (y - cy)
            doubleArrayOf(nx, y + sy * (nx - cx))
        }
    }

    /** Карта рамки трансформации: исходный прямоугольник рамки -> её текущие 4 угла (гомография). null - рамка вырождена. */
    fun boxMap(box: TransformBox.Box): PointMap? {
        val m = TransformBox.homography(box) ?: return null
        val sx = box.src.x.toDouble(); val sy = box.src.y.toDouble()
        val sw = max(box.src.w, 1).toDouble(); val sh = max(box.src.h, 1).toDouble()
        return PointMap { x, y -> TransformBox.mapUnit(m, (x - sx) / sw, (y - sy) / sh).let { doubleArrayOf(it.x, it.y) } }
    }

    /** Фигура в виде контура пера (или булевой операции из контуров): дальше её узлы точно переводятся любой картой. */
    fun editable(s: VectorShape): VectorShape {
        val p = s.pts
        return when (s.kind) {
            VecKind.RECT -> if (p.size < 4) s else {
                val x0 = min(p[0], p[2]); val y0 = min(p[1], p[3]); val x1 = max(p[0], p[2]); val y1 = max(p[1], p[3])
                VectorShape(VecKind.POLYGON, listOf(x0, y0, x1, y0, x1, y1, x0, y1), s.stroke, s.fill, s.width)
            }
            VecKind.ELLIPSE -> if (p.size < 4) s else {
                val cx = (p[0] + p[2]) / 2.0; val cy = (p[1] + p[3]) / 2.0
                ellipse(cx, cy, abs(p[2] - p[0]) / 2.0, abs(p[3] - p[1]) / 2.0, s.stroke, s.fill, s.width)
            }
            VecKind.LINE -> if (p.size < 4) s else VectorShape(VecKind.POLY, listOf(p[0], p[1], p[p.size - 2], p[p.size - 1]), s.stroke, 0L, s.width)
            VecKind.PRESET -> {
                val kind = runCatching { ShapeKind.valueOf(s.preset) }.getOrNull()
                if (kind == null || p.size < 4) s else {
                    val x0 = min(p[0], p[2]); val y0 = min(p[1], p[3]); val w = abs(p[2] - p[0]); val h = abs(p[3] - p[1])
                    val polys = ShapeMath.polygons(kind).filter { it.size >= 6 }.map { poly ->
                        VectorShape(VecKind.POLYGON, List(poly.size) { i -> if (i % 2 == 0) x0 + poly[i] * w else y0 + poly[i] * h }, s.stroke, s.fill, s.width)
                    }
                    when (polys.size) { 0 -> s; 1 -> polys[0]; else -> balanced(polys, VectorBool.UNION, s.stroke, s.fill, s.width) }
                }
            }
            VecKind.BOOL -> {
                val parts = VectorBool.parts(s).map { editable(it) }
                if (parts.size != 2) s else s.copy(pts = parts.flatMap { it.pts }, parts = parts)
            }
            else -> s
        }
    }

    /** Эллипс из 4 узлов Безье (по часовой с верхней точки). */
    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, stroke: Long, fill: Long, width: Float): VectorShape {
        val kx = (KAPPA * rx).toFloat(); val ky = (KAPPA * ry).toFloat()
        val pts = listOf(cx, cy - ry, cx + rx, cy, cx, cy + ry, cx - rx, cy).map { it.toFloat() }
        val h = listOf(-kx, 0f, kx, 0f, 0f, -ky, 0f, ky, kx, 0f, -kx, 0f, 0f, ky, 0f, -ky)
        return VectorShape(VecKind.POLYGON, pts, stroke, fill, width, handles = h)
    }

    /**
     * Сбалансированное дерево булевых операций (глубина log2 n): у отрисовки ограничена глубина вложенности,
     * а длинная цепочка из десятков контуров её бы превысила. [op] должна быть ассоциативной (объединение, исключение).
     */
    fun balanced(list: List<VectorShape>, op: String, stroke: Long, fill: Long, width: Float): VectorShape {
        require(list.isNotEmpty())
        if (list.size == 1) return list[0]
        val mid = list.size / 2
        val a = balanced(list.subList(0, mid), op, stroke, fill, width)
        val b = balanced(list.subList(mid, list.size), op, stroke, fill, width)
        return VectorShape(VecKind.BOOL, a.pts + b.pts, stroke, fill, width, "", op, listOf(a, b))
    }

    /** Глубина вложенности булевых операций (0 - простая фигура). */
    fun depth(s: VectorShape): Int = if (s.kind != VecKind.BOOL) 0 else 1 + (s.parts.maxOfOrNull { depth(it) } ?: 0)

    /**
     * Фигура через карту [f]. [widthScale] - множитель толщины обводки (для масштаба; у наклона и искажения 1).
     * null - карта дала NaN/бесконечность (например, точка ушла за горизонт перспективы).
     */
    fun map(s: VectorShape, f: PointMap, widthScale: Double = 1.0): VectorShape? {
        val e = editable(s)
        if (e.kind == VecKind.BOOL) {
            val parts = VectorBool.parts(e).map { map(it, f, widthScale) ?: return null }
            if (parts.size != 2) return null
            return e.copy(pts = parts.flatMap { it.pts }, parts = parts, width = scaledWidth(e, widthScale))
        }
        if (e.kind == VecKind.RECT || e.kind == VecKind.ELLIPSE || e.kind == VecKind.PRESET) return null   // не удалось перевести в контур
        val n = e.pts.size / 2
        val pts = ArrayList<Float>(n * 2)
        val hasH = e.handles.size == e.pts.size * 2
        val h = if (hasH) ArrayList<Float>(e.handles.size) else null
        for (k in 0 until n) {
            val x = e.pts[k * 2].toDouble(); val y = e.pts[k * 2 + 1].toDouble()
            val q = f.map(x, y)
            if (!q[0].isFinite() || !q[1].isFinite()) return null
            pts += q[0].toFloat(); pts += q[1].toFloat()
            if (h != null) for (w in 0..1) {
                val hx = e.handles[k * 4 + w * 2].toDouble(); val hy = e.handles[k * 4 + w * 2 + 1].toDouble()
                if (hx == 0.0 && hy == 0.0) { h += 0f; h += 0f; continue }
                val r = f.map(x + hx, y + hy)
                if (!r[0].isFinite() || !r[1].isFinite()) return null
                h += (r[0] - q[0]).toFloat(); h += (r[1] - q[1]).toFloat()
            }
        }
        return e.copy(pts = pts, handles = h ?: e.handles, width = scaledWidth(e, widthScale))
    }

    private fun scaledWidth(s: VectorShape, k: Double): Float =
        if (k == 1.0 || !k.isFinite() || k <= 0.0) s.width else (s.width * k).toFloat().coerceIn(0.1f, 1500f)

    /**
     * Применяет карту к фигурам с индексами [indices] (пусто - ко всем). Новый список (исходный не меняется);
     * null - хотя бы одна фигура не переводится (операция отклоняется целиком, слой остаётся как был).
     */
    fun apply(shapes: List<VectorShape>, indices: Set<Int>, f: PointMap, widthScale: Double = 1.0): List<VectorShape>? {
        val out = ArrayList<VectorShape>(shapes.size)
        for ((i, s) in shapes.withIndex()) out += if (indices.isEmpty() || i in indices) map(s, f, widthScale) ?: return null else s
        return out
    }

    /** Рамка фигур (узлы и концы ручек, с учётом частей булевых операций): minX, minY, maxX, maxY; null - пусто. */
    fun bounds(shapes: List<VectorShape>, indices: Set<Int> = emptySet()): DoubleArray? {
        val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        var any = false
        fun add(x: Double, y: Double) {
            if (!x.isFinite() || !y.isFinite()) return
            b[0] = min(b[0], x); b[1] = min(b[1], y); b[2] = max(b[2], x); b[3] = max(b[3], y); any = true
        }
        for ((i, s0) in shapes.withIndex()) {
            if (indices.isNotEmpty() && i !in indices) continue
            val s = editable(s0)
            for (k in 0 until s.pts.size / 2) {
                val x = s.pts[k * 2].toDouble(); val y = s.pts[k * 2 + 1].toDouble()
                add(x, y)
                if (s.handles.size == s.pts.size * 2) for (w in 0..1) add(x + s.handles[k * 4 + w * 2], y + s.handles[k * 4 + w * 2 + 1])
            }
        }
        return if (any) b else null
    }

    /** Множитель толщины обводки для рамки: корень из изменения площади (наклон и чистое искажение углов дают ~1). */
    fun widthScaleFor(box: TransformBox.Box): Double {
        val a0 = box.src.w.toDouble() * box.src.h.toDouble()
        val a1 = abs(TransformBox.area(box))
        if (a0 <= 0.0 || !a1.isFinite() || a1 <= 0.0) return 1.0
        return sqrt(a1 / a0).coerceIn(1.0 / 8.0, 8.0)
    }
}
