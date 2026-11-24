package com.vibetoon.domain.project.foreign

import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.anim.TimeFormat
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.MiniJson
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectMath
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorBool
import com.vibetoon.domain.vector.VectorShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Пакет 28: Lottie (Bodymovin JSON, .json и .lottie) -> проект VibeToon.
 *
 * Композиция: размер w×h (длинная сторона до 4096), fps = fr, длительность op - ip.
 * Слои: фигуры (ty 4) и заливки (ty 1) - векторные слои (контуры с ручками Безье, прямоугольники со скруглением,
 * эллипсы, многоугольники/звёзды, заливка и обводка, дырки в составных контурах - булево вычитание), картинки (ty 2),
 * тексты (ty 5), пустые (ty 3) - Transform Layer, вложенные композиции (ty 0) - группы. Родитель (parent) - привязка
 * слоя, ip/op - клип, режим наложения и скрытие - свойства слоя.
 *
 * Трансформация слоя Lottie: холст = p + R·S·(x - a). В VibeToon слой хранится в позе покоя (x - a0 + pRef + off,
 * где off - сдвиг покоя родителя/группы), опорная точка - pRef + off, а ключи: d = p - pRef - R·S·(a - a0), масштаб
 * s/100, поворот r (по часовой, радианы), прозрачность o/100. Отрезки между ключами с одинаковой кривой у всех свойств
 * переносятся одним ключом с кривой cubic-bezier; остальные (разные кривые, пространственные дуги позиции) - ключами
 * на каждом кадре, поэтому движение совпадает.
 */
object LottieImport {
    const val MAX_LAYERS = 300
    private const val KAPPA = 0.5522848f

    fun looksLike(text: String): Boolean {
        val head = text.trimStart().take(1)
        if (head != "{") return false
        return "\"layers\"" in text && "\"fr\"" in text && "\"op\"" in text && "\"ip\"" in text && "\"media\"" !in text
    }

    // ------------------------------------------------------------------ анимируемые свойства

    class Key(val t: Double, var s: DoubleArray, val e: DoubleArray?, val ox: Double, val oy: Double, val ix: Double, val iy: Double,
              val hold: Boolean, val to: DoubleArray?, val ti: DoubleArray?, val multiEase: Boolean)

    class Anim(val keys: List<Key>, val static: DoubleArray) {
        val animated get() = keys.size >= 2

        fun at(t: Double): DoubleArray {
            if (keys.isEmpty()) return static
            if (keys.size == 1 || t <= keys[0].t) return keys[0].s
            if (t >= keys.last().t) return keys.last().s
            var i = 0
            while (i < keys.size - 2 && keys[i + 1].t <= t) i++
            val a = keys[i]; val b = keys[i + 1]
            val end = a.e ?: b.s
            if (a.hold) return a.s
            val u = ((t - a.t) / (b.t - a.t).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
            val y = ease(a.ox, a.oy, a.ix, a.iy, u)
            val n = min(a.s.size, end.size)
            if (spatial(a) && n >= 2) {
                val to = a.to!!; val ti = a.ti!!
                return DoubleArray(n) { k ->
                    if (k >= 2) lerp(a.s[k], end[k], y)
                    else cubic(a.s[k], a.s[k] + to[k], end[k] + ti[k], end[k], y)
                }
            }
            return DoubleArray(n) { lerp(a.s[it], end[it], y) }
        }

        /** Значение на отрезке [t0, t1] меняется. */
        fun changes(t0: Double, t1: Double): Boolean {
            if (!animated) return false
            val a = at(t0); val b = at(t1); val m = at((t0 + t1) / 2)
            if (keys.any { it.t > t0 && it.t < t1 }) return true
            return !same(a, b) || !same(a, m)
        }
    }

    private fun same(a: DoubleArray, b: DoubleArray) = a.size == b.size && a.indices.all { abs(a[it] - b[it]) < 1e-6 }
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    private fun cubic(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val u = 1 - t
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
    }
    fun spatial(k: Key): Boolean = k.to != null && k.ti != null && (k.to.any { abs(it) > 1e-6 } || k.ti.any { abs(it) > 1e-6 })

    /** cubic-bezier(x1, y1, x2, y2) в доле времени [u]. */
    fun ease(x1: Double, y1: Double, x2: Double, y2: Double, u: Double): Double {
        if (abs(x1 - y1) < 1e-9 && abs(x2 - y2) < 1e-9) return u
        val cx1 = x1.coerceIn(0.0, 1.0); val cx2 = x2.coerceIn(0.0, 1.0)
        var lo = 0.0; var hi = 1.0; var s = u
        repeat(40) {
            s = (lo + hi) / 2
            if (cubic(0.0, cx1, cx2, 1.0, s) < u) lo = s else hi = s
        }
        return cubic(0.0, y1, y2, 1.0, s)
    }

    private fun vec(v: Any?): DoubleArray? = when (v) {
        is Number -> doubleArrayOf(v.toDouble())
        is List<*> -> v.mapNotNull { (it as? Number)?.toDouble() }.toDoubleArray().takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun first(v: Any?): Pair<Double, Boolean> = when (v) {
        is Number -> v.toDouble() to false
        is List<*> -> { val l = v.mapNotNull { (it as? Number)?.toDouble() }; (l.firstOrNull() ?: 0.0) to (l.distinct().size > 1) }
        else -> 0.0 to false
    }

    fun anim(v: Any?, def: DoubleArray): Anim {
        val m = v as? Map<*, *> ?: return Anim(emptyList(), vec(v) ?: def)
        val k = m["k"]
        if (k is List<*> && k.isNotEmpty() && (k[0] as? Map<*, *>)?.containsKey("t") == true) {
            val keys = ArrayList<Key>()
            for (raw in k) {
                val o = raw as? Map<*, *> ?: continue
                val t = (o["t"] as? Number)?.toDouble() ?: continue
                val s = vec(o["s"]) ?: keys.lastOrNull()?.let { it.e ?: it.s } ?: def
                val om = o["o"] as? Map<*, *>; val im = o["i"] as? Map<*, *>
                val (ox, m1) = first(om?.get("x")); val (oy, m2) = first(om?.get("y"))
                val (ix, m3) = first(im?.get("x")); val (iy, m4) = first(im?.get("y"))
                keys += Key(t, s, vec(o["e"]), if (om == null) 0.0 else ox, if (om == null) 0.0 else oy, if (im == null) 1.0 else ix, if (im == null) 1.0 else iy,
                    (o["h"] as? Number)?.toInt() == 1, vec(o["to"]), vec(o["ti"]), m1 || m2 || m3 || m4)
            }
            if (keys.isEmpty()) return Anim(emptyList(), def)
            return Anim(keys.sortedBy { it.t }, keys[0].s)
        }
        return Anim(emptyList(), vec(k) ?: def)
    }

    /** Трансформация слоя: anchor, position (или раздельные x/y), scale %, rotation °, opacity %. */
    class Tr(val a: Anim, val p: Anim, val px: Anim?, val py: Anim?, val s: Anim, val r: Anim, val o: Anim, val skew: Boolean) {
        fun pos(t: Double): DoubleArray = if (px != null && py != null) doubleArrayOf(px.at(t)[0], py.at(t)[0]) else p.at(t).let { doubleArrayOf(it[0], it.getOrElse(1) { 0.0 }) }
        fun props(): List<Anim> = listOfNotNull(a, if (px != null) null else p, px, py, s, r, o)
        val animated get() = props().any { it.animated }
    }

    fun tr(ks: Any?): Tr {
        val m = ks as? Map<*, *> ?: emptyMap<String, Any?>()
        val pm = m["p"] as? Map<*, *>
        val split = pm != null && (pm["s"] == true || (pm["x"] != null && pm["k"] == null))
        val sk = anim(m["sk"], doubleArrayOf(0.0))
        return Tr(
            anim(m["a"], doubleArrayOf(0.0, 0.0)),
            if (split) Anim(emptyList(), doubleArrayOf(0.0, 0.0)) else anim(m["p"], doubleArrayOf(0.0, 0.0)),
            if (split) anim(pm!!["x"], doubleArrayOf(0.0)) else null,
            if (split) anim(pm!!["y"], doubleArrayOf(0.0)) else null,
            anim(m["s"], doubleArrayOf(100.0, 100.0)),
            anim(m["r"] ?: m["rz"], doubleArrayOf(0.0)),
            anim(m["o"], doubleArrayOf(100.0)),
            sk.animated || abs(sk.static[0]) > 1e-6
        )
    }

    // ------------------------------------------------------------------ геометрия фигур

    /** Матрица 2x3 (как Affine2D, но в Double, чтобы не тянуть рендер). */
    data class M(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0) {
        operator fun times(o: M) = M(a * o.a + c * o.b, b * o.a + d * o.b, a * o.c + c * o.d, b * o.c + d * o.d, a * o.tx + c * o.ty + tx, b * o.tx + d * o.ty + ty)
        fun x(x: Double, y: Double) = a * x + c * y + tx
        fun y(x: Double, y: Double) = b * x + d * y + ty
        fun scale() = sqrt(abs(a * d - b * c))
        companion object {
            fun t(x: Double, y: Double) = M(tx = x, ty = y)
            fun r(deg: Double) = (deg * PI / 180).let { M(cos(it), sin(it), -sin(it), cos(it)) }
            fun s(x: Double, y: Double) = M(a = x, d = y)
        }
    }

    /** Контур: узлы и ручки (вход dx,dy, выход dx,dy на узел), замкнут ли. */
    class Path(val pts: List<Double>, val handles: List<Double>, val closed: Boolean) {
        fun map(m: M): Path {
            val p = ArrayList<Double>(pts.size); val h = ArrayList<Double>(handles.size)
            for (i in 0 until pts.size / 2) {
                p += m.x(pts[i * 2], pts[i * 2 + 1]); p += m.y(pts[i * 2], pts[i * 2 + 1])
                for (j in 0 until 2) {
                    val hx = handles[i * 4 + j * 2]; val hy = handles[i * 4 + j * 2 + 1]
                    h += m.a * hx + m.c * hy; h += m.b * hx + m.d * hy
                }
            }
            return Path(p, h, closed)
        }
        /** Ориентированная площадь по узлам (знак - направление обхода). */
        fun area(): Double { var s = 0.0; val n = pts.size / 2; for (i in 0 until n) { val j = (i + 1) % n; s += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1] }; return s / 2 }
        fun bounds(): DoubleArray {
            val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
            for (i in 0 until pts.size / 2) {
                for (k in listOf(0.0, 1.0)) {
                    // узел и концы его ручек - грубая, но надёжная рамка
                    val x = pts[i * 2]; val y = pts[i * 2 + 1]
                    b[0] = min(b[0], x); b[1] = min(b[1], y); b[2] = max(b[2], x); b[3] = max(b[3], y)
                    val hx = x + handles[i * 4 + (k * 2).toInt()]; val hy = y + handles[i * 4 + (k * 2).toInt() + 1]
                    b[0] = min(b[0], hx); b[1] = min(b[1], hy); b[2] = max(b[2], hx); b[3] = max(b[3], hy)
                }
            }
            return b
        }
    }

    fun rectPath(cx: Double, cy: Double, w: Double, h: Double, round: Double): Path {
        val x0 = cx - w / 2; val y0 = cy - h / 2; val x1 = cx + w / 2; val y1 = cy + h / 2
        val r = round.coerceIn(0.0, min(w, h) / 2)
        if (r <= 0.01) return Path(listOf(x0, y0, x1, y0, x1, y1, x0, y1), List(16) { 0.0 }, true)
        val k = KAPPA * r
        val pts = listOf(x0 + r, y0, x1 - r, y0, x1, y0 + r, x1, y1 - r, x1 - r, y1, x0 + r, y1, x0, y1 - r, x0, y0 + r)
        val h = listOf(-k, 0.0, 0.0, 0.0, 0.0, 0.0, k, 0.0, 0.0, -k, 0.0, 0.0, 0.0, 0.0, 0.0, k,
            k, 0.0, 0.0, 0.0, 0.0, 0.0, -k, 0.0, 0.0, k, 0.0, 0.0, 0.0, 0.0, 0.0, -k)
        return Path(pts, h, true)
    }

    fun ellipsePath(cx: Double, cy: Double, w: Double, h: Double): Path {
        val rx = w / 2; val ry = h / 2; val kx = KAPPA * rx; val ky = KAPPA * ry
        return Path(listOf(cx, cy - ry, cx + rx, cy, cx, cy + ry, cx - rx, cy),
            listOf(-kx, 0.0, kx, 0.0, 0.0, -ky, 0.0, ky, kx, 0.0, -kx, 0.0, 0.0, ky, 0.0, -ky), true)
    }

    fun starPath(cx: Double, cy: Double, points: Int, outer: Double, inner: Double, rotDeg: Double, star: Boolean): Path {
        val n = (if (star) points * 2 else points).coerceIn(3, 200)
        val pts = ArrayList<Double>()
        for (i in 0 until n) {
            val ang = (-90.0 + rotDeg + 360.0 * i / n) * PI / 180
            val r = if (star && i % 2 == 1) inner else outer
            pts += cx + r * cos(ang); pts += cy + r * sin(ang)
        }
        return Path(pts, List(n * 4) { 0.0 }, true)
    }

    private fun color(v: Any?, opacity: Double): Long {
        val c = vec((v as? Map<*, *>)?.get("k")?.let { k -> if (k is List<*> && k.firstOrNull() is Map<*, *>) (k[0] as Map<*, *>)["s"] else k }) ?: return 0xFF000000L
        val div = if (c.take(3).any { it > 1.0 }) 255.0 else 1.0
        fun ch(i: Int) = ((c.getOrElse(i) { if (i == 3) div else 0.0 } / div).coerceIn(0.0, 1.0) * 255 + 0.5).toLong()
        val a = ((c.getOrElse(3) { div } / div).coerceIn(0.0, 1.0) * opacity.coerceIn(0.0, 1.0) * 255 + 0.5).toLong()
        return (a shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }

    private fun num(v: Any?, def: Double): Double = anim(v, doubleArrayOf(def)).let { if (it.keys.isEmpty()) it.static[0] else it.keys[0].s[0] }
    private fun pair(v: Any?, def: Double): DoubleArray = anim(v, doubleArrayOf(def, def)).let { (if (it.keys.isEmpty()) it.static else it.keys[0].s).let { a -> doubleArrayOf(a[0], a.getOrElse(1) { a[0] }) } }
    private fun isAnimated(v: Any?) = anim(v, doubleArrayOf(0.0)).animated

    /** Итог разбора слоя-фигуры: фигуры снизу вверх. */
    class ShapeOut(val shapes: List<VectorShape>, val bounds: DoubleArray?)

    /** Стиль (заливка или обводка) и контуры, к которым он относится. */
    private class StyleOp(val fill: Long, val stroke: Long, val width: Double, val evenOdd: Boolean, val paths: List<Path>)

    fun shapeLayer(items: List<*>, notes: MutableSet<String>): ShapeOut {
        // ops сверху вниз (в порядке списка Lottie: первый элемент - верхний)
        fun group(list: List<*>, m: M, opacity: Double, depth: Int): Pair<List<StyleOp>, List<Path>> {
            if (depth > 32) return emptyList<StyleOp>() to emptyList()
            val ops = ArrayList<StyleOp>()
            val paths = ArrayList<Path>()
            for (raw in list) {
                val it = raw as? Map<*, *> ?: continue
                if (it["hd"] == true) continue
                when (it["ty"]) {
                    "sh" -> {
                        val ks = it["ks"]
                        if (isAnimated(ks)) notes += "Анимация контуров фигур не переносится (взята первая форма)"
                        val v0 = (ks as? Map<*, *>)?.get("k").let { k -> if (k is List<*> && k.firstOrNull() is Map<*, *> && (k[0] as Map<*, *>).containsKey("t")) ((k[0] as Map<*, *>)["s"] as? List<*>)?.firstOrNull() else k } as? Map<*, *>
                        val v = (v0?.get("v") as? List<*>).orEmpty().mapNotNull { vec(it) }
                        val ii = (v0?.get("i") as? List<*>).orEmpty().mapNotNull { vec(it) }
                        val oo = (v0?.get("o") as? List<*>).orEmpty().mapNotNull { vec(it) }
                        if (v.size < 2) continue
                        val pts = ArrayList<Double>(); val h = ArrayList<Double>()
                        for ((k, p) in v.withIndex()) {
                            pts += p[0]; pts += p.getOrElse(1) { 0.0 }
                            val inn = ii.getOrNull(k); val out = oo.getOrNull(k)
                            h += inn?.getOrNull(0) ?: 0.0; h += inn?.getOrNull(1) ?: 0.0; h += out?.getOrNull(0) ?: 0.0; h += out?.getOrNull(1) ?: 0.0
                        }
                        paths += Path(pts, h, v0?.get("c") == true).map(m)
                    }
                    "rc" -> { val p = pair(it["p"], 0.0); val s = pair(it["s"], 0.0); if (s[0] > 0 && s[1] > 0) paths += rectPath(p[0], p[1], s[0], s[1], num(it["r"], 0.0)).map(m) }
                    "el" -> { val p = pair(it["p"], 0.0); val s = pair(it["s"], 0.0); if (s[0] > 0 && s[1] > 0) paths += ellipsePath(p[0], p[1], s[0], s[1]).map(m) }
                    "sr" -> {
                        val p = pair(it["p"], 0.0)
                        val star = (it["sy"] as? Number)?.toInt() != 2
                        if (num(it["os"], 0.0) != 0.0 || num(it["is"], 0.0) != 0.0) notes += "Скругление звёзд и многоугольников не переносится"
                        paths += starPath(p[0], p[1], num(it["pt"], 5.0).roundToInt(), num(it["or"], 50.0), num(it["ir"], 25.0), num(it["r"], 0.0), star).map(m)
                    }
                    "gr" -> {
                        val sub = (it["it"] as? List<*>).orEmpty()
                        val trm = sub.lastOrNull { (it as? Map<*, *>)?.get("ty") == "tr" } as? Map<*, *>
                        var gm = m; var go = opacity
                        if (trm != null) {
                            if (listOf("p", "a", "s", "r", "o").any { k -> isAnimated(trm[k]) }) notes += "Анимация групп внутри слоя-фигуры не переносится (взято первое положение)"
                            if (num(trm["sk"], 0.0) != 0.0) notes += "Перекос (skew) не переносится"
                            val p = pair(trm["p"], 0.0); val a = pair(trm["a"], 0.0); val s = pair(trm["s"], 100.0)
                            gm = m * M.t(p[0], p[1]) * M.r(num(trm["r"], 0.0)) * M.s(s[0] / 100, s[1] / 100) * M.t(-a[0], -a[1])
                            go = opacity * num(trm["o"], 100.0) / 100
                        }
                        val (subOps, subPaths) = group(sub, gm, go, depth + 1)
                        ops += subOps
                        paths += subPaths
                    }
                    "fl", "gf" -> {
                        if (it["ty"] == "gf") notes += "Градиенты заменены сплошным цветом"
                        val c = if (it["ty"] == "gf") gradientColor(it, opacity) else color(it["c"], opacity * num(it["o"], 100.0) / 100)
                        if (isAnimated(it["c"]) || isAnimated(it["o"])) notes += "Анимация цвета и прозрачности заливок не переносится"
                        if (paths.isNotEmpty()) ops += StyleOp(c, 0L, 0.0, (it["r"] as? Number)?.toInt() == 2, paths.toList())
                    }
                    "st", "gs" -> {
                        if (it["ty"] == "gs") notes += "Градиенты заменены сплошным цветом"
                        val c = if (it["ty"] == "gs") gradientColor(it, opacity) else color(it["c"], opacity * num(it["o"], 100.0) / 100)
                        if (isAnimated(it["w"])) notes += "Анимация толщины обводки не переносится"
                        if (it["d"] != null) notes += "Пунктир обводки не переносится"
                        if (paths.isNotEmpty()) ops += StyleOp(0L, c, num(it["w"], 1.0) * m.scale(), false, paths.toList())
                    }
                    "tr" -> {}
                    "tm" -> notes += "Trim Paths не переносится"
                    "rp" -> notes += "Repeater не переносится"
                    "mm" -> notes += "Merge Paths не переносится"
                    "rd" -> notes += "Round Corners не переносится"
                    else -> notes += "Эффекты фигур (${it["ty"]}) не переносятся"
                }
            }
            return ops to paths
        }
        val (ops, _) = group(items, M(), 1.0, 0)
        // Lottie: верхний стиль - первый; VibeToon рисует по порядку - разворачиваем
        val out = ArrayList<VectorShape>()
        var bounds: DoubleArray? = null
        for (op in ops.reversed()) {
            for (p in op.paths) {
                val b = p.bounds()
                bounds = bounds?.let { doubleArrayOf(min(it[0], b[0]), min(it[1], b[1]), max(it[2], b[2]), max(it[3], b[3])) } ?: b
            }
            out += styled(op)
        }
        return ShapeOut(out, bounds)
    }

    private fun gradientColor(it: Map<*, *>, opacity: Double): Long {
        val g = it["g"] as? Map<*, *>
        val stops = vec((g?.get("k") as? Map<*, *>)?.get("k")?.let { k -> if (k is List<*> && k.firstOrNull() is Map<*, *>) (k[0] as Map<*, *>)["s"] else k })
        if (stops == null || stops.size < 4) return 0xFF000000L
        return color(mapOf("k" to listOf(stops[1], stops[2], stops[3], 1.0)), opacity * num(it["o"], 100.0) / 100)
    }

    private fun shapeOf(p: Path, fill: Long, stroke: Long, width: Float): VectorShape {
        val kind = if (p.closed || fill != 0L) VecKind.POLYGON else VecKind.POLY
        val h = if (p.handles.all { abs(it) < 1e-6 }) emptyList() else p.handles.map { it.toFloat() }
        return VectorShape(kind, p.pts.map { it.toFloat() }, stroke, fill, width, handles = h)
    }

    /** Стиль -> фигуры VibeToon: обводка - каждый контур; заливка - контуры, вложенные «дырки» вычитаются (BOOL). */
    private fun styled(op: StyleOp): List<VectorShape> {
        if (op.stroke != 0L) return op.paths.filter { it.pts.size >= 4 }.map { shapeOf(it, 0L, op.stroke, op.width.toFloat().coerceIn(0.1f, 1500f)) }
        val paths = op.paths.filter { it.pts.size >= 6 || (it.pts.size >= 4 && it.handles.any { h -> abs(h) > 1e-6 }) }
        if (paths.size <= 1) return paths.map { shapeOf(it, op.fill, 0L, 1f) }
        val bs = paths.map { it.bounds() }
        fun inside(i: Int, j: Int) = i != j && bs[i][0] >= bs[j][0] && bs[i][1] >= bs[j][1] && bs[i][2] <= bs[j][2] && bs[i][3] <= bs[j][3]
        val used = BooleanArray(paths.size)
        val out = ArrayList<VectorShape>()
        for (i in paths.indices) {
            if (paths.indices.any { j -> inside(i, j) && !inside(j, i) }) continue   // вложен в другой - решится при внешнем
            var shape = shapeOf(paths[i], op.fill, 0L, 1f)
            used[i] = true
            for (j in paths.indices) {
                if (used[j] || !inside(j, i)) continue
                val hole = op.evenOdd || (paths[j].area() * paths[i].area() < 0)
                if (!hole) continue
                val part = shapeOf(paths[j], op.fill, 0L, 1f).copy(kind = VecKind.POLYGON)
                shape = VectorShape(VecKind.BOOL, shape.pts + part.pts, 0L, op.fill, 1f, "", VectorBool.SUBTRACT, listOf(shape, part))
                used[j] = true
            }
            out += shape
        }
        for (i in paths.indices) if (!used[i]) out += shapeOf(paths[i], op.fill, 0L, 1f)
        return out
    }

    // ------------------------------------------------------------------ слои

    /** Время: кадр проекта F = t·scale + offset (t - время слоя). */
    data class TimeMap(val scale: Double, val offset: Double) {
        fun frame(t: Double) = t * scale + offset
        fun local(f: Double) = (f - offset) / scale
    }

    private class Lay(
        val j: Map<*, *>, val id: Int, val ty: Int, val name: String, val scope: Int, val ind: Int?, val parentInd: Int?,
        val group: Int?, val time: TimeMap, val clipStart: Int, val clipEnd: Int, val tr: Tr
    ) {
        var bounds: DoubleArray? = null
        var pRef = doubleArrayOf(0.0, 0.0)
        var off = doubleArrayOf(0.0, 0.0)
        var a0 = doubleArrayOf(0.0, 0.0)
    }

    fun convertDotLottie(src: ForeignImport.Source, sink: ForeignImport.Sink, title: String): ForeignImport.Result {
        val manifest = src.read("manifest.json")?.toString(Charsets.UTF_8)?.let { runCatching { MiniJson.parse(it) as? Map<*, *> }.getOrNull() }
        val ids = ((manifest?.get("animations") as? List<*>).orEmpty()).mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
        val names = src.names().filter { it.lowercase().startsWith("animations/") && it.lowercase().endsWith(".json") }
        val path = ids.firstNotNullOfOrNull { id -> names.firstOrNull { it.substringAfterLast('/').substringBeforeLast('.') == id } } ?: names.firstOrNull()
            ?: throw IllegalArgumentException("no animation")
        val r = convert(src.read(path)!!.toString(Charsets.UTF_8), src, sink, title)
        return if (names.size > 1) r.copy(notes = r.notes + "В файле .lottie несколько анимаций - импортирована первая") else r
    }

    fun convert(json: String, src: ForeignImport.Source?, sink: ForeignImport.Sink, title: String): ForeignImport.Result {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("not lottie")
        val w0 = (root["w"] as? Number)?.toDouble() ?: 0.0
        val h0 = (root["h"] as? Number)?.toDouble() ?: 0.0
        require(w0 >= 1 && h0 >= 1) { "size" }
        val fr = (root["fr"] as? Number)?.toDouble() ?: 30.0
        val ip = (root["ip"] as? Number)?.toDouble() ?: 0.0
        val op = (root["op"] as? Number)?.toDouble() ?: (ip + 60)
        val notes = LinkedHashSet<String>()
        val k = min(1.0, ProjectMath.MAX_SIZE / max(w0, h0))
        val cw = ProjectMath.clampSize((w0 * k).roundToInt()); val ch = ProjectMath.clampSize((h0 * k).roundToInt())
        if (k < 1) notes += "Композиция ${w0.roundToInt()}×${h0.roundToInt()} уменьшена до ${cw}×${ch}"
        val fps = fr.roundToInt().coerceIn(1, 120)
        if (abs(fr - fps) > 0.01) notes += "Частота кадров ${"%.2f".format(java.util.Locale.US, fr)} округлена до $fps"
        // кадры проекта: время композиции * (fps / fr) - ip; при дробной fr сохраняется длительность в секундах
        val tScale = fps / fr
        val duration = ((op - ip) * tScale).roundToInt().coerceIn(1, TimeFormat.MAX_DURATION_FRAMES)
        if ((op - ip) * tScale > TimeFormat.MAX_DURATION_FRAMES) notes += "Длительность обрезана до ${TimeFormat.MAX_DURATION_FRAMES} кадров"
        val assets = (root["assets"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.associateBy { it["id"]?.toString() ?: "" }
        val idsGen = ForeignImport.Ids()
        val lays = ArrayList<Lay>()
        val order = ArrayList<Pair<Int, Boolean>>()   // (индекс в lays, это закрытие группы) - порядок медиа снизу вверх
        var scopes = 0
        var cut = false

        fun flatten(layers: List<*>, comp: TimeMap, cs: Int, ce: Int, group: Int?, scope: Int, depth: Int) {
            if (depth > 16) { notes += "Слишком глубокая вложенность композиций"; return }
            for (raw in layers.reversed()) {
                val l = raw as? Map<*, *> ?: continue
                if (lays.size >= MAX_LAYERS) { cut = true; return }
                val ty = (l["ty"] as? Number)?.toInt() ?: continue
                val sr = ((l["sr"] as? Number)?.toDouble() ?: 1.0).takeIf { it > 0 } ?: 1.0
                val st = (l["st"] as? Number)?.toDouble() ?: 0.0
                val time = TimeMap(sr * comp.scale, st * comp.scale + comp.offset)
                val lip = (l["ip"] as? Number)?.toDouble() ?: ip
                val lop = (l["op"] as? Number)?.toDouble() ?: op
                val s = max(cs, comp.frame(lip).roundToInt()); val e = min(ce, comp.frame(lop).roundToInt())
                if (e <= s || e <= 0 || s >= duration) continue
                if (ty !in setOf(0, 1, 2, 3, 4, 5)) { notes += "Слои Lottie типа $ty не переносятся"; continue }
                if (l["ddd"] == 1.0 || l["ddd"] == 1) notes += "3D-слои импортированы как 2D"
                if (l["tm"] != null) notes += "Time Remap не переносится"
                if (l["ao"] == 1.0 || l["ao"] == 1) notes += "Auto-Orient не переносится"
                if ((l["masksProperties"] as? List<*>)?.isNotEmpty() == true) notes += "Маски слоёв Lottie не переносятся"
                if ((l["ef"] as? List<*>)?.isNotEmpty() == true) notes += "Эффекты слоёв Lottie не переносятся"
                if (l["tt"] != null) notes += "Track Matte не переносится: слой-подложка скрыт"
                val t = tr(l["ks"])
                if (t.skew) notes += "Перекос (skew) не переносится"
                val lay = Lay(l, idsGen.next(), ty, (l["nm"] as? String)?.ifBlank { null } ?: "Layer", scope, (l["ind"] as? Number)?.toInt(),
                    (l["parent"] as? Number)?.toInt(), group, time, s, e, t)
                lays += lay
                val idx = lays.size - 1
                if (ty == 0) {
                    val asset = assets[l["refId"]?.toString()]
                    val inner = (asset?.get("layers") as? List<*>)
                    if (inner == null) { notes += "Не найдена вложенная композиция «${lay.name}»"; lays.removeAt(idx); continue }
                    flatten(inner, time, s, e, lay.id, ++scopes, depth + 1)
                    order += idx to true
                } else order += idx to false
            }
        }
        flatten((root["layers"] as? List<*>).orEmpty(), TimeMap(tScale, -ip * tScale), 0, duration, null, 0, 0)
        if (Regex("\"x\"\\s*:\\s*\"").containsMatchIn(json)) notes += "Выражения (expressions) не переносятся"
        if (cut) notes += "Импортировано слоёв: $MAX_LAYERS (больше за раз не импортируется)"
        if (lays.isEmpty()) throw ForeignImport.Unsupported(ForeignImport.MSG_EMPTY)

        // геометрия в локальных координатах слоя (до позы покоя)
        val shapeOut = HashMap<Int, ShapeOut>()
        val imageInfo = HashMap<Int, Triple<String, Int, Int>>()
        for (l in lays) {
            when (l.ty) {
                4 -> { val so = shapeLayer((l.j["shapes"] as? List<*>).orEmpty(), notes); shapeOut[l.id] = so; l.bounds = so.bounds }
                1 -> {
                    val sw = (l.j["sw"] as? Number)?.toDouble() ?: 0.0; val sh = (l.j["sh"] as? Number)?.toDouble() ?: 0.0
                    val c = (l.j["sc"] as? String)?.removePrefix("#")?.toLongOrNull(16)?.let { if ((l.j["sc"] as String).length > 7) it else (0xFF000000L or it) } ?: 0xFF000000L
                    if (sw > 0 && sh > 0) {
                        shapeOut[l.id] = ShapeOut(listOf(shapeOf(rectPath(sw / 2, sh / 2, sw, sh, 0.0), c, 0L, 1f)), doubleArrayOf(0.0, 0.0, sw, sh))
                        l.bounds = doubleArrayOf(0.0, 0.0, sw, sh)
                    }
                }
                2 -> {
                    val a = assets[l.j["refId"]?.toString()]
                    val bytes = a?.let { imageBytes(it, src) }
                    val size = bytes?.let { PngIO.imageSize(it) }
                    if (a == null || bytes == null || size == null) { notes += "Не найдена картинка слоя «${l.name}»"; continue }
                    val ext = when { bytes.size > 3 && bytes[0] == 0xFF.toByte() -> "jpg"; bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"; else -> "png" }
                    val file = sink.put("lot_${l.id}_${ForeignImport.safeName(l.name)}.$ext", bytes)
                    val aw = (a["w"] as? Number)?.toInt() ?: size.first; val ah = (a["h"] as? Number)?.toInt() ?: size.second
                    imageInfo[l.id] = Triple(file, size.first, size.second)
                    l.bounds = doubleArrayOf(0.0, 0.0, aw.toDouble(), ah.toDouble())
                }
                5 -> {
                    val d = textDoc(l.j)
                    val size = (d?.get("s") as? Number)?.toDouble() ?: 48.0
                    val text = (d?.get("t") as? String).orEmpty()
                    l.bounds = doubleArrayOf(0.0, -size, max(size, text.length * size * 0.55), size * 0.25)
                }
            }
        }

        val byScope = lays.groupBy { it.scope }
        val byId = lays.associateBy { it.id }
        fun parentOf(l: Lay): Lay? = l.parentInd?.let { p -> byScope[l.scope]?.firstOrNull { it.ind == p && it !== l } }
        val placed = HashSet<Int>()
        val visiting = HashSet<Int>()
        fun place(l: Lay) {
            if (l.id in placed || !visiting.add(l.id)) return
            val p = parentOf(l)
            val g = l.group?.let { byId[it] }
            val base = p ?: g
            if (base != null) { place(base); l.off = doubleArrayOf(base.pRef[0] - base.a0[0] + base.off[0], base.pRef[1] - base.a0[1] + base.off[1]) }
            l.a0 = l.tr.a.at(l.time.local(l.clipStart.toDouble())).let { doubleArrayOf(it[0], it.getOrElse(1) { 0.0 }) }
            // поза покоя: положение, при котором рамка слоя больше всего видна на холсте (векторный слой рисуется в пределах холста)
            val cands = LinkedHashSet<Double>()
            cands += l.time.local(l.clipStart.toDouble())
            for (a in listOfNotNull(l.tr.p, l.tr.px, l.tr.py)) for (kf in a.keys) cands += kf.t
            val b = l.bounds
            var best = l.tr.pos(cands.first()); var bestArea = -1.0
            if (b != null) for (t in cands) {
                val pos = l.tr.pos(t)
                val x0 = (b[0] - l.a0[0] + pos[0] + l.off[0]) * k; val y0 = (b[1] - l.a0[1] + pos[1] + l.off[1]) * k
                val x1 = (b[2] - l.a0[0] + pos[0] + l.off[0]) * k; val y1 = (b[3] - l.a0[1] + pos[1] + l.off[1]) * k
                val area = max(0.0, min(x1, cw.toDouble()) - max(x0, 0.0)) * max(0.0, min(y1, ch.toDouble()) - max(y0, 0.0))
                if (area > bestArea + 1e-6) { bestArea = area; best = pos }
            }
            l.pRef = best
            visiting.remove(l.id); placed += l.id
        }
        lays.forEach(::place)

        val media = ArrayList<MediaItem>()
        val regroup = ArrayList<Pair<Int, Int>>()
        val props = HashMap<Int, LayerProps>()
        val pivots = HashMap<Int, Pair<Float, Float>>()
        val links = HashMap<Int, Int>()
        val clips = HashMap<Int, Clip>()
        val base = HashMap<Int, LayerTransform>()
        val tracks = HashMap<Int, AnimationTrack>()
        val unsupportedBlend = LinkedHashSet<String>()
        val vw = (cw * min(1f, 1536f / max(cw, ch))).toInt().coerceAtLeast(1)
        val vh = (ch * min(1f, 1536f / max(cw, ch))).toInt().coerceAtLeast(1)
        val created = HashSet<Int>()
        for ((idx, isGroup) in order) {
            val l = lays[idx]
            val tx = (l.pRef[0] + l.off[0] - l.a0[0]); val ty = (l.pRef[1] + l.off[1] - l.a0[1])
            val rest = M.s(k, k) * M.t(tx, ty)
            val px = ((l.pRef[0] + l.off[0]) * k).toFloat(); val py = ((l.pRef[1] + l.off[1]) * k).toFloat()
            val item: MediaItem = when {
                isGroup -> if (media.none { regroup.any { r -> r.first == it.id && r.second == l.id } }) null else MediaItem(l.id, MediaKind.GROUP, l.name, "")
                l.ty == 4 || l.ty == 1 -> shapeOut[l.id]?.takeIf { it.shapes.isNotEmpty() }?.let { so ->
                    MediaItem(l.id, MediaKind.IMAGE, l.name, "assets/lot_${l.id}.png", vw, vh, 0f, 0f, cw.toFloat(), ch.toFloat(),
                        vector = true, vectors = so.shapes.map { mapShape(it, rest, k.toFloat()) })
                }
                l.ty == 2 -> imageInfo[l.id]?.let { (file, iw, ih) ->
                    val b = l.bounds!!
                    MediaItem(l.id, MediaKind.IMAGE, l.name, file, iw, ih, (tx * k).toFloat(), (ty * k).toFloat(), (b[2] * k).toFloat(), (b[3] * k).toFloat())
                }
                l.ty == 3 -> MediaItem(l.id, MediaKind.NULLOBJ, l.name, "", 0, 0, px - 20f, py - 20f, 40f, 40f)
                l.ty == 5 -> textItem(l, (tx * k).toFloat(), (ty * k).toFloat(), k.toFloat(), notes)
                else -> null
            } ?: continue
            media += item
            created += l.id
            l.group?.let { regroup += l.id to it }
            pivots[l.id] = px to py
            if (l.clipStart > 0 || l.clipEnd < duration) clips[l.id] = Clip(l.clipStart, l.clipEnd)
            val bm = (l.j["bm"] as? Number)?.toInt() ?: 0
            val blend = when (bm) { 0 -> "norm"; 1 -> "mul "; 2 -> "scrn"; 3 -> "over"; 4 -> "dark"; 5 -> "lite"; 6 -> "div "; 7 -> "idiv"; 9 -> "sLit"; 16 -> "lddg"; else -> null }
            if (blend == null) unsupportedBlend += "bm $bm"
            val matteSource = l.j["td"] == 1.0 || l.j["td"] == 1
            val p = LayerProps(blend = if (isGroup) "norm" else blend ?: "norm", visible = l.j["hd"] != true && !matteSource)
            if (p != LayerProps()) props[l.id] = p
            // ключи трансформации
            val keys = transformKeys(l, duration, k)
            if (keys.size >= 2) tracks[l.id] = AnimationTrack(l.id, keys)
            else keys.firstOrNull()?.toTransform()?.takeIf { it != LayerTransform() }?.let { base[l.id] = it }
        }
        // привязки к родителям (только созданным слоям)
        for (l in lays) if (l.id in created) parentOf(l)?.takeIf { it.id in created }?.let { links[l.id] = it.id }
        if (unsupportedBlend.isNotEmpty()) notes += "Некоторые режимы наложения заменены на Normal"
        if (media.isEmpty()) throw ForeignImport.Unsupported(ForeignImport.MSG_EMPTY)
        val name = (root["nm"] as? String)?.trim()?.ifEmpty { null } ?: title
        val meta = ProjectMeta("import", name, cw, ch, fps, duration, 0L)
        val data = ProjectData(meta, base = base, tracks = tracks, media = media, clips = clips, links = links, regroup = regroup,
            layerProps = props, pivots = pivots)
        return ForeignImport.Result(data, "Lottie", notes.toList())
    }

    private fun mapShape(s: VectorShape, m: M, k: Float): VectorShape {
        val pts = ArrayList<Float>(s.pts.size)
        for (i in 0 until s.pts.size / 2) { val x = s.pts[i * 2].toDouble(); val y = s.pts[i * 2 + 1].toDouble(); pts += m.x(x, y).toFloat(); pts += m.y(x, y).toFloat() }
        val h = s.handles.map { it * k }
        return s.copy(pts = pts, handles = h, width = if (s.stroke != 0L) (s.width * k).coerceIn(0.1f, 1500f) else s.width,
            parts = s.parts.map { mapShape(it, m, k) })
    }

    private fun textDoc(j: Map<*, *>): Map<*, *>? {
        val d = ((j["t"] as? Map<*, *>)?.get("d") as? Map<*, *>)?.get("k") as? List<*> ?: return null
        return (d.firstOrNull() as? Map<*, *>)?.get("s") as? Map<*, *>
    }

    private fun textItem(l: Lay, x: Float, y: Float, k: Float, notes: MutableSet<String>): MediaItem? {
        val d = textDoc(l.j) ?: return null
        val text = (d["t"] as? String)?.replace("\r", "\n")?.takeIf { it.isNotBlank() } ?: return null
        val size = ((d["s"] as? Number)?.toFloat() ?: 48f) * k
        val fc = vec(d["fc"])
        val color = if (fc != null && fc.size >= 3) {
            val div = if (fc.take(3).any { it > 1.0 }) 255.0 else 1.0
            fun c(v: Double) = ((v / div).coerceIn(0.0, 1.0) * 255 + 0.5).toLong()
            0xFF000000L or (c(fc[0]) shl 16) or (c(fc[1]) shl 8) or c(fc[2])
        } else 0xFF000000L
        val align = when ((d["j"] as? Number)?.toInt()) { 1 -> 2; 2 -> 1; else -> 0 }
        if (d["f"] != null) notes += "Шрифты текста Lottie заменены шрифтом по умолчанию"
        // рамка текста измеряется при открытии проекта (w = 0): x - точка начала строки, y - верх по высоте кегля
        return MediaItem(l.id, MediaKind.TEXT, l.name, "", 0, 0, x, y - size, 0f, 0f, text = text, textColor = color, textSize = size.coerceIn(4f, 2000f), textAlign = align)
    }

    private fun imageBytes(a: Map<*, *>, src: ForeignImport.Source?): ByteArray? {
        val p = a["p"] as? String ?: return null
        if (p.startsWith("data:")) return runCatching { java.util.Base64.getMimeDecoder().decode(p.substringAfter(',')) }.getOrNull()
        val u = (a["u"] as? String).orEmpty()
        val s = src ?: return null
        for (path in listOf(u + p, p, "images/$p", u.trimStart('/') + p)) {
            val norm = path.replace('\\', '/').trimStart('/')
            if (norm.contains("..")) continue
            s.read(norm)?.let { return it }
        }
        val base = p.substringAfterLast('/')
        return s.names().firstOrNull { it.substringAfterLast('/') == base }?.let { s.read(it) }
    }

    /**
     * Ключи слоя на кадрах проекта. Отрезок между соседними ключами переносится одним ключом, если все меняющиеся на нём
     * свойства имеют ключи ровно на его концах, одинаковую кривую (или Hold) и нет дуги позиции; иначе - ключ на каждом кадре.
     */
    private fun transformKeys(l: Lay, duration: Int, k: Double): List<Keyframe> {
        val t = l.tr
        fun value(f: Int): Keyframe {
            val tau = l.time.local(f.toDouble())
            val p = t.pos(tau)
            val a = t.a.at(tau)
            val s = t.s.at(tau)
            val sx = s[0] / 100; val sy = s.getOrElse(1) { s[0] } / 100
            val r = t.r.at(tau)[0] * PI / 180
            val dax = a[0] - l.a0[0]; val day = a.getOrElse(1) { 0.0 } - l.a0[1]
            // d = p - pRef - R·S·(a - a0)
            val rsx = cos(r) * sx * dax - sin(r) * sy * day
            val rsy = sin(r) * sx * dax + cos(r) * sy * day
            val dx = (p[0] - l.pRef[0] - rsx) * k; val dy = (p[1] - l.pRef[1] - rsy) * k
            return Keyframe(f, dx.toFloat(), dy.toFloat(), sx.toFloat(), sy.toFloat(), r.toFloat(), (t.o.at(tau)[0] / 100).toFloat().coerceIn(0f, 1f))
        }
        if (!t.animated) return listOf(value(l.clipStart.coerceIn(0, duration - 1)))
        val props = t.props().filter { it.animated }
        val frames = sortedSetOf<Int>()
        val last = duration - 1
        for (a in props) for (kf in a.keys) frames += l.time.frame(kf.t).roundToInt().coerceIn(0, last)
        val list = frames.toList()
        val out = ArrayList<Keyframe>()
        for (i in list.indices) {
            val f0 = list[i]
            if (i == list.size - 1) { out += value(f0); break }
            val f1 = list[i + 1]
            val t0 = l.time.local(f0.toDouble()); val t1 = l.time.local(f1.toDouble())
            val changing = props.filter { it.changes(t0, t1) }
            var ease: Key? = null
            var single = true
            for (a in changing) {
                val j = a.keys.indexOfFirst { l.time.frame(it.t).roundToInt() == f0 }
                if (j < 0 || j + 1 >= a.keys.size || l.time.frame(a.keys[j + 1].t).roundToInt() != f1) { single = false; break }
                val kf = a.keys[j]
                if (kf.multiEase || (a === t.p && spatial(kf))) { single = false; break }
                val e = ease
                if (e == null) ease = kf
                else if (e.hold != kf.hold || (!kf.hold && (abs(e.ox - kf.ox) > 1e-4 || abs(e.oy - kf.oy) > 1e-4 || abs(e.ix - kf.ix) > 1e-4 || abs(e.iy - kf.iy) > 1e-4))) { single = false; break }
            }
            if (single) {
                val e = ease
                val v = value(f0)
                out += when {
                    e == null -> v
                    e.hold -> v.copy(easing = EasingType.STEP)
                    abs(e.ox - e.oy) < 1e-4 && abs(e.ix - e.iy) < 1e-4 -> v
                    else -> v.copy(curve = listOf(e.ox.toFloat(), e.oy.toFloat(), e.ix.toFloat(), e.iy.toFloat()))
                }
            } else for (f in f0 until f1) out += value(f)
        }
        return out
    }
}
