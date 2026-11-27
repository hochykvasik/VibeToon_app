package com.vibetoon.domain.paint

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sprint 33: направляющие рисования в координатах холста (пиксели проекта), не зависят от камеры.
 * Изометрическая сетка, перспектива на 1/2/3 точки схода, эллипс и зеркальная симметрия
 * (вертикальная, горизонтальная, обе оси, радиальная). Только редактор: в экспорт не попадают.
 */
object DrawingGuides {
    enum class Kind { OFF, ISOMETRIC, PERSPECTIVE_1, PERSPECTIVE_2, PERSPECTIVE_3, ELLIPSE }
    enum class Symmetry { OFF, VERTICAL, HORIZONTAL, BOTH, RADIAL }
    enum class Handle { VP0, VP1, VP2, ELLIPSE_CENTER, ELLIPSE_RX, ELLIPSE_RY, ISO_ORIGIN, ISO_CELL, SYM_CENTER }

    data class Point(val x: Float, val y: Float)

    const val MIN_RADIAL = 2
    const val MAX_RADIAL = 16

    /** [vps] - точки схода; [center] - центр эллипса или начало изометрической сетки; [angle] - поворот эллипса, радианы. */
    data class State(
        val kind: Kind = Kind.OFF,
        val vps: List<Point> = emptyList(),
        val center: Point = Point(0f, 0f),
        val rx: Float = 0f,
        val ry: Float = 0f,
        val angle: Float = 0f,
        val cell: Float = 64f,
        val snap: Boolean = true,
        val symmetry: Symmetry = Symmetry.OFF,
        val symCenter: Point = Point(0f, 0f),
        val radial: Int = 6
    ) {
        val active: Boolean get() = kind != Kind.OFF || symmetry != Symmetry.OFF
        /** Притягивает ли штрих: направляющая включена и не выключено «Притяжение». */
        val snapping: Boolean get() = snap && kind != Kind.OFF && usable(this)
    }

    private fun finite(vararg v: Float) = v.all { it.isFinite() }
    private fun finite(p: Point) = p.x.isFinite() && p.y.isFinite()

    private fun usable(s: State): Boolean = when (s.kind) {
        Kind.OFF -> false
        Kind.ISOMETRIC -> finite(s.center) && s.cell.isFinite() && s.cell >= 4f
        Kind.PERSPECTIVE_1 -> s.vps.size >= 1 && s.vps.take(1).all(::finite)
        Kind.PERSPECTIVE_2 -> s.vps.size >= 2 && s.vps.take(2).all(::finite)
        Kind.PERSPECTIVE_3 -> s.vps.size >= 3 && s.vps.take(3).all(::finite)
        Kind.ELLIPSE -> finite(s.center) && finite(s.rx, s.ry, s.angle) && s.rx >= 1f && s.ry >= 1f
    }

    /** Геометрия по умолчанию для вида [kind] на холсте [w]x[h]; симметрия и «Притяжение» сохраняются. */
    fun withKind(prev: State, kind: Kind, w: Int, h: Int): State {
        val cw = max(1, w).toFloat(); val ch = max(1, h).toFloat()
        val m = min(cw, ch)
        val base = prev.copy(kind = kind, center = Point(cw / 2f, ch / 2f), angle = 0f)
        return when (kind) {
            Kind.OFF -> prev.copy(kind = Kind.OFF)
            Kind.ISOMETRIC -> base.copy(cell = max(8f, m / 12f))
            Kind.PERSPECTIVE_1 -> base.copy(vps = listOf(Point(cw / 2f, ch * .45f)))
            Kind.PERSPECTIVE_2 -> base.copy(vps = listOf(Point(0f, ch * .45f), Point(cw, ch * .45f)))
            Kind.PERSPECTIVE_3 -> base.copy(vps = listOf(Point(0f, ch * .4f), Point(cw, ch * .4f), Point(cw / 2f, ch)))
            Kind.ELLIPSE -> base.copy(rx = m * .3f, ry = m * .18f)
        }
    }

    /** Включает симметрию; центр осей по умолчанию - центр холста (если ещё не задан). */
    fun withSymmetry(prev: State, symmetry: Symmetry, w: Int, h: Int): State {
        val center = if (prev.symmetry == Symmetry.OFF || !finite(prev.symCenter) || (prev.symCenter.x == 0f && prev.symCenter.y == 0f))
            Point(max(1, w) / 2f, max(1, h) / 2f) else prev.symCenter
        return prev.copy(symmetry = symmetry, symCenter = center)
    }

    fun withRadial(prev: State, count: Int): State = prev.copy(radial = count.coerceIn(MIN_RADIAL, MAX_RADIAL))

    // ---- притяжение линий ----

    private fun unit(x: Float, y: Float): Point? {
        val l = hypot(x, y)
        return if (!l.isFinite() || l < 1e-4f) null else Point(x / l, y / l)
    }

    /** Направления, к которым притягивается прямой штрих из точки [a] (единичные векторы, без знака). */
    fun directions(s: State, a: Point): List<Point> {
        val toVp = { i: Int -> s.vps.getOrNull(i)?.let { unit(it.x - a.x, it.y - a.y) } }
        val s30 = sqrt(3f) / 2f
        return when (s.kind) {
            Kind.ISOMETRIC -> listOf(Point(0f, 1f), Point(s30, .5f), Point(s30, -.5f))
            Kind.PERSPECTIVE_1 -> listOfNotNull(Point(1f, 0f), Point(0f, 1f), toVp(0))
            Kind.PERSPECTIVE_2 -> listOfNotNull(Point(0f, 1f), toVp(0), toVp(1))
            Kind.PERSPECTIVE_3 -> listOfNotNull(toVp(0), toVp(1), toVp(2))
            else -> emptyList()
        }
    }

    /** Направление, ближайшее к движению a -> p (максимальный |cos|); null - нет движения или направлений. */
    fun chooseDirection(dirs: List<Point>, a: Point, p: Point): Point? {
        val m = unit(p.x - a.x, p.y - a.y) ?: return null
        return dirs.maxByOrNull { abs(it.x * m.x + it.y * m.y) }
    }

    fun projectOnLine(a: Point, d: Point, p: Point): Point {
        val t = (p.x - a.x) * d.x + (p.y - a.y) * d.y
        return Point(a.x + d.x * t, a.y + d.y * t)
    }

    // ---- эллипс ----

    fun ellipseAngleOf(s: State, p: Point): Float {
        val c = cos(-s.angle); val sn = sin(-s.angle)
        val dx = p.x - s.center.x; val dy = p.y - s.center.y
        val lx = dx * c - dy * sn; val ly = dx * sn + dy * c
        return atan2(ly / s.ry, lx / s.rx)
    }

    fun ellipsePoint(s: State, t: Float): Point {
        val lx = s.rx * cos(t); val ly = s.ry * sin(t)
        val c = cos(s.angle); val sn = sin(s.angle)
        return Point(s.center.x + lx * c - ly * sn, s.center.y + lx * sn + ly * c)
    }

    /** Точка на эллипсе по лучу из центра (устойчиво и без итераций). */
    fun projectOnEllipse(s: State, p: Point): Point = ellipsePoint(s, ellipseAngleOf(s, p))

    private fun wrap(a: Float): Float {
        var r = a
        val twoPi = (2 * PI).toFloat()
        while (r > PI) r -= twoPi
        while (r <= -PI) r += twoPi
        return r
    }

    /** Точки дуги от параметра [from] к [to] по короткому пути, шаг не больше [maxStep] пикселей; без первой точки. */
    fun ellipseArc(s: State, from: Float, to: Float, maxStep: Float): List<Point> {
        val delta = wrap(to - from)
        val r = max(s.rx, s.ry)
        val step = max(maxStep, .5f)
        val n = ceil(abs(delta) * r / step).toInt().coerceIn(1, 512)
        return (1..n).map { i -> ellipsePoint(s, from + delta * i / n) }
    }

    // ---- симметрия ----

    fun mirrorCount(s: State): Int = when (s.symmetry) {
        Symmetry.OFF -> 1
        Symmetry.VERTICAL, Symmetry.HORIZONTAL -> 2
        Symmetry.BOTH -> 4
        Symmetry.RADIAL -> s.radial.coerceIn(MIN_RADIAL, MAX_RADIAL)
    }

    /** Отражения точки: первая - сама точка; затем зеркальные копии в фиксированном порядке. */
    fun mirrorImages(s: State, p: Point): List<Point> {
        val cx = s.symCenter.x; val cy = s.symCenter.y
        return when (s.symmetry) {
            Symmetry.OFF -> listOf(p)
            Symmetry.VERTICAL -> listOf(p, Point(2 * cx - p.x, p.y))
            Symmetry.HORIZONTAL -> listOf(p, Point(p.x, 2 * cy - p.y))
            Symmetry.BOTH -> listOf(p, Point(2 * cx - p.x, p.y), Point(p.x, 2 * cy - p.y), Point(2 * cx - p.x, 2 * cy - p.y))
            Symmetry.RADIAL -> {
                val n = mirrorCount(s)
                val dx = p.x - cx; val dy = p.y - cy
                (0 until n).map { k ->
                    val a = 2.0 * PI * k / n
                    val c = cos(a).toFloat(); val sn = sin(a).toFloat()
                    Point(cx + dx * c - dy * sn, cy + dx * sn + dy * c)
                }
            }
        }
    }

    /**
     * Штрих с направляющими: притяжение (прямая к точке схода/оси или дуга эллипса), затем отражения.
     * [lockDistance] - путь (пиксели холста), после которого выбирается направление прямой.
     * Результат - точки для каждого отражения (индекс 0 - исходный штрих).
     */
    class Stroke(private val state: State, private val lockDistance: Float, private val maxStep: Float = 2f) {
        val count: Int = mirrorCount(state)
        private val snapKind = if (state.snapping) state.kind else Kind.OFF
        private var origin: Point? = null
        private var dir: Point? = null
        private var lastT = 0f

        private fun spread(points: List<Point>): List<List<Point>> {
            val out = List(count) { ArrayList<Point>(points.size) }
            for (p in points) mirrorImages(state, p).forEachIndexed { i, q -> if (i < count) out[i].add(q) }
            return out
        }

        /** Ровно одна точка на каждое отражение. */
        fun begin(p: Point): List<List<Point>> {
            origin = p; dir = null
            val first = when (snapKind) {
                Kind.ELLIPSE -> { lastT = ellipseAngleOf(state, p); ellipsePoint(state, lastT) }
                else -> p
            }
            return spread(listOf(first))
        }

        fun move(p: Point): List<List<Point>> {
            val a = origin ?: return begin(p)
            if (!finite(p)) return List(count) { emptyList() }
            val pts: List<Point> = when (snapKind) {
                Kind.OFF -> listOf(p)
                Kind.ELLIPSE -> {
                    val t = ellipseAngleOf(state, p)
                    val arc = ellipseArc(state, lastT, t, maxStep)
                    lastT = t
                    arc
                }
                else -> {
                    var d = dir
                    if (d == null) {
                        if (hypot(p.x - a.x, p.y - a.y) < lockDistance) return List(count) { emptyList() }
                        d = chooseDirection(directions(state, a), a, p) ?: return List(count) { emptyList() }
                        dir = d
                    }
                    listOf(projectOnLine(a, d, p))
                }
            }
            return spread(pts)
        }

        /** Выбранное направление прямой (для подсветки); null - ещё не выбрано или не прямая. */
        fun lockedDirection(): Point? = dir
    }

    // ---- отрисовка (только редактор) ----

    /** Отрезок a-b, обрезанный прямоугольником [0..w]x[0..h] (Лианг-Барски); null - вне холста. */
    fun clip(a: Point, b: Point, w: Float, h: Float): FloatArray? {
        var t0 = 0f; var t1 = 1f
        val dx = b.x - a.x; val dy = b.y - a.y
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(a.x, w - a.x, a.y, h - a.y)
        for (i in 0..3) {
            if (abs(p[i]) < 1e-9f) { if (q[i] < 0f) return null; continue }
            val r = q[i] / p[i]
            if (p[i] < 0f) { if (r > t1) return null; if (r > t0) t0 = r } else { if (r < t0) return null; if (r < t1) t1 = r }
        }
        if (t1 - t0 < 1e-6f) return null
        return floatArrayOf(a.x + dx * t0, a.y + dy * t0, a.x + dx * t1, a.y + dy * t1)
    }

    private fun lineThrough(p: Point, d: Point, w: Float, h: Float): FloatArray? {
        val far = 4f * (w + h) + abs(p.x) + abs(p.y)
        return clip(Point(p.x - d.x * far, p.y - d.y * far), Point(p.x + d.x * far, p.y + d.y * far), w, h)
    }

    /** Линии направляющей: ломаные (x0, y0, x1, y1, ...) в пикселях холста. Число линий ограничено. */
    fun overlay(s: State, w: Int, h: Int, rays: Int = 24): List<FloatArray> {
        if (w <= 0 || h <= 0) return emptyList()
        val cw = w.toFloat(); val ch = h.toFloat()
        val out = ArrayList<FloatArray>()
        if (usable(s)) when (s.kind) {
            Kind.ISOMETRIC -> {
                val side = max(s.cell, (cw + ch) / 120f)
                val spacing = side * sqrt(3f) / 2f
                for (d in directions(s, s.center)) {
                    val nx = -d.y; val ny = d.x
                    val offs = listOf(0f to 0f, cw to 0f, 0f to ch, cw to ch).map { (x, y) -> (x - s.center.x) * nx + (y - s.center.y) * ny }
                    val k0 = floor(offs.min() / spacing).toInt(); val k1 = ceil(offs.max() / spacing).toInt()
                    for (k in k0..k1) {
                        val p = Point(s.center.x + nx * k * spacing, s.center.y + ny * k * spacing)
                        lineThrough(p, d, cw, ch)?.let(out::add)
                    }
                }
            }
            Kind.PERSPECTIVE_1, Kind.PERSPECTIVE_2, Kind.PERSPECTIVE_3 -> {
                val n = when (s.kind) { Kind.PERSPECTIVE_1 -> 1; Kind.PERSPECTIVE_2 -> 2; else -> 3 }
                val vps = s.vps.take(n)
                val far = 4f * (cw + ch)
                for (vp in vps) for (i in 0 until rays) {
                    val a = 2.0 * PI * i / rays
                    clip(vp, Point(vp.x + (cos(a) * far).toFloat(), vp.y + (sin(a) * far).toFloat()), cw, ch)?.let(out::add)
                }
                // горизонт: через первые две точки схода (или горизонталь через одну)
                val horizon = if (n >= 2) unit(vps[1].x - vps[0].x, vps[1].y - vps[0].y) else Point(1f, 0f)
                if (horizon != null) lineThrough(vps[0], horizon, cw, ch)?.let(out::add)
            }
            Kind.ELLIPSE -> {
                val seg = 96
                out.add(FloatArray((seg + 1) * 2) { i -> val p = ellipsePoint(s, (2 * PI * (i / 2) / seg).toFloat()); if (i % 2 == 0) p.x else p.y })
            }
            Kind.OFF -> Unit
        }
        val c = s.symCenter
        if (finite(c)) when (s.symmetry) {
            Symmetry.OFF -> Unit
            Symmetry.VERTICAL -> lineThrough(c, Point(0f, 1f), cw, ch)?.let(out::add)
            Symmetry.HORIZONTAL -> lineThrough(c, Point(1f, 0f), cw, ch)?.let(out::add)
            Symmetry.BOTH -> { lineThrough(c, Point(0f, 1f), cw, ch)?.let(out::add); lineThrough(c, Point(1f, 0f), cw, ch)?.let(out::add) }
            Symmetry.RADIAL -> {
                val n = mirrorCount(s)
                val far = 4f * (cw + ch)
                for (k in 0 until n) {
                    val a = 2.0 * PI * k / n - PI / 2
                    clip(c, Point(c.x + (cos(a) * far).toFloat(), c.y + (sin(a) * far).toFloat()), cw, ch)?.let(out::add)
                }
            }
        }
        return out
    }

    /** Ручки, которые можно тянуть в режиме «Править направляющие». */
    fun handles(s: State): List<Pair<Handle, Point>> {
        val out = ArrayList<Pair<Handle, Point>>()
        when (s.kind) {
            Kind.PERSPECTIVE_1, Kind.PERSPECTIVE_2, Kind.PERSPECTIVE_3 -> {
                val n = when (s.kind) { Kind.PERSPECTIVE_1 -> 1; Kind.PERSPECTIVE_2 -> 2; else -> 3 }
                listOf(Handle.VP0, Handle.VP1, Handle.VP2).take(n).forEachIndexed { i, hnd -> s.vps.getOrNull(i)?.let { out += hnd to it } }
            }
            Kind.ELLIPSE -> {
                out += Handle.ELLIPSE_CENTER to s.center
                out += Handle.ELLIPSE_RX to ellipsePoint(s, 0f)
                out += Handle.ELLIPSE_RY to ellipsePoint(s, (PI / 2).toFloat())
            }
            Kind.ISOMETRIC -> {
                out += Handle.ISO_ORIGIN to s.center
                val s30 = sqrt(3f) / 2f
                out += Handle.ISO_CELL to Point(s.center.x + s.cell * s30, s.center.y - s.cell * .5f)
            }
            Kind.OFF -> Unit
        }
        if (s.symmetry != Symmetry.OFF) out += Handle.SYM_CENTER to s.symCenter
        return out.filter { finite(it.second) }
    }

    /** Ближайшая ручка в радиусе [radius] (пиксели холста). */
    fun hit(s: State, p: Point, radius: Float): Handle? =
        handles(s).map { it.first to hypot(it.second.x - p.x, it.second.y - p.y) }
            .filter { it.second <= radius }.minByOrNull { it.second }?.first

    /** Перенос ручки в точку [p] холста; размеры ограничены снизу, чтобы направляющая не вырождалась. */
    fun moveHandle(s: State, handle: Handle, p: Point): State {
        if (!finite(p)) return s
        fun vp(i: Int) = s.copy(vps = s.vps.mapIndexed { k, v -> if (k == i) p else v })
        return when (handle) {
            Handle.VP0 -> vp(0)
            Handle.VP1 -> vp(1)
            Handle.VP2 -> vp(2)
            Handle.ELLIPSE_CENTER -> s.copy(center = p)
            Handle.ELLIPSE_RX -> {
                val dx = p.x - s.center.x; val dy = p.y - s.center.y
                val r = hypot(dx, dy)
                if (r < 4f) s else s.copy(rx = r, angle = atan2(dy, dx))
            }
            Handle.ELLIPSE_RY -> {
                // расстояние вдоль малой оси (перпендикуляр к большой)
                val nx = -sin(s.angle); val ny = cos(s.angle)
                val r = abs((p.x - s.center.x) * nx + (p.y - s.center.y) * ny)
                s.copy(ry = max(4f, r))
            }
            Handle.ISO_ORIGIN -> s.copy(center = p)
            Handle.ISO_CELL -> s.copy(cell = max(8f, hypot(p.x - s.center.x, p.y - s.center.y)))
            Handle.SYM_CENTER -> s.copy(symCenter = p)
        }
    }

    // ---- сохранение в настройках ----

    /** Строка для SharedPreferences: версия, размер холста, затем поля. */
    fun encode(s: State, w: Int, h: Int): String {
        val f = ArrayList<String>()
        f += "g1"; f += w.toString(); f += h.toString()
        f += s.kind.name; f += s.symmetry.name; f += s.radial.toString(); f += if (s.snap) "1" else "0"
        for (v in listOf(s.center.x, s.center.y, s.rx, s.ry, s.angle, s.cell, s.symCenter.x, s.symCenter.y)) f += v.toString()
        f += s.vps.size.toString()
        for (p in s.vps) { f += p.x.toString(); f += p.y.toString() }
        return f.joinToString(";")
    }

    class Decoded(val state: State, val width: Int, val height: Int)

    fun decode(text: String?): Decoded? {
        val f = text?.split(';') ?: return null
        if (f.size < 16 || f[0] != "g1") return null
        return runCatching {
            val w = f[1].toInt(); val h = f[2].toInt()
            val kind = Kind.valueOf(f[3]); val sym = Symmetry.valueOf(f[4])
            val radial = f[5].toInt().coerceIn(MIN_RADIAL, MAX_RADIAL)
            val nums = (7..14).map { f[it].toFloat() }
            if (nums.any { !it.isFinite() } || w <= 0 || h <= 0) return null
            val n = f[15].toInt()
            if (n !in 0..3 || f.size != 16 + n * 2) return null
            val vps = (0 until n).map { Point(f[16 + it * 2].toFloat(), f[17 + it * 2].toFloat()) }
            if (vps.any { !finite(it) }) return null
            Decoded(State(kind, vps, Point(nums[0], nums[1]), nums[2], nums[3], nums[4], nums[5], f[6] == "1", sym, Point(nums[6], nums[7]), radial), w, h)
        }.getOrNull()
    }

    /** Переносит направляющие на холст другого размера: позиции пропорционально, размеры - по меньшему коэффициенту. */
    fun rescale(s: State, fromW: Int, fromH: Int, toW: Int, toH: Int): State {
        if (fromW <= 0 || fromH <= 0 || toW <= 0 || toH <= 0 || (fromW == toW && fromH == toH)) return s
        val kx = toW.toFloat() / fromW; val ky = toH.toFloat() / fromH; val k = min(kx, ky)
        fun p(v: Point) = Point(v.x * kx, v.y * ky)
        return s.copy(vps = s.vps.map(::p), center = p(s.center), rx = s.rx * k, ry = s.ry * k, cell = max(8f, s.cell * k), symCenter = p(s.symCenter))
    }
}
