package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Пин «марионетки»: точка в исходном положении слоя (u, v - доли размера слоя), мягкий (Move) или жёсткий (Stiff)
 * и радиус влияния в пикселях слоя. Смещение пина лежит в base и tracks под [PuppetMath.pinId] (как у вершин).
 */
data class Pin(val u: Float, val v: Float, val stiff: Boolean = false, val radius: Float = 180f)

/**
 * Puppet Mesh: сетка - только основа, рисунком управляют пины. Смещение вершины = взвешенное среднее смещений
 * пинов: `d(v) = sum(w_i * delta_i) / max(1, sum(w_i))`, где вес пина падает с расстоянием до него. Мягкий пин
 * (Move) даёт гауссов спад (рисунок тянется как резина), жёсткий (Stiff) - почти плоскую «полку» и резкий край.
 */
object PuppetMath {
    const val ID_BASE = 500_000_000
    const val MAX_PINS = 64

    fun pinId(layerId: Int, k: Int) = ID_BASE + layerId * MAX_PINS + k
    fun isPinId(id: Int) = id >= ID_BASE && id < PathLayerMath.ID_BASE   // выше - положения слоёв на путях (спринт 38)
    fun layerOf(id: Int) = (id - ID_BASE) / MAX_PINS
    fun indexOf(id: Int) = (id - ID_BASE) % MAX_PINS

    /** Вес пина в точке на расстоянии [d] пикселей: 1 в центре, 0 на радиусе. */
    fun weight(p: Pin, d: Float): Float {
        val r = p.radius.coerceAtLeast(1f)
        if (d >= r) return 0f
        return if (p.stiff) {
            val t = ((d - 0.6f * r) / (0.4f * r)).coerceIn(0f, 1f)
            1f - t * t * (3f - 2f * t)
        } else {
            val s = r / 2.4f
            val g = exp(-(d * d) / (2f * s * s))
            val gr = exp(-(r * r) / (2f * s * s))
            ((g - gr) / (1f - gr)).coerceAtLeast(0f)
        }
    }

    /** Смещения пинов на кадре (2 числа на пин, доли слоя); [fx] = false - только форма привязки. */
    fun offsets(layerId: Int, count: Int, frame: Int, base: Map<Int, LayerTransform>, anim: Animation, fx: Boolean): FloatArray {
        val out = FloatArray(count * 2)
        for (k in 0 until count) {
            val id = pinId(layerId, k)
            var dx = base[id]?.dx ?: 0f
            var dy = base[id]?.dy ?: 0f
            if (fx) anim.tracks[id]?.evaluate(frame)?.let { dx += it.dx; dy += it.dy }
            out[k * 2] = dx; out[k * 2 + 1] = dy
        }
        return out
    }

    /**
     * Доминирование пина: вес w в (0..1] превращается в w / (1.02 - w). У самого пина (w -> 1) его вес в десятки раз
     * больше чужих, поэтому пин ТОЧНО идёт за пальцем, а между пинами вклады делятся поровну.
     */
    fun sharp(w: Float) = w / (1.02f - w)

    /** Суммарный вес, при котором влияние пинов считается полным: ниже - плавный переход к исходному положению у края радиуса. */
    const val FULL_INFLUENCE = 0.35f

    /**
     * Смещение точки (u, v) от всех пинов (доли слоя); [w], [h] - размер слоя в пикселях. Веса НОРМИРУЮТСЯ:
     * `d = sum(w_i * delta_i) / sum(w_i)` (сумма весов = 1), поэтому вершина между корнем и кончиком пряди с двумя пинами
     * получает 0.5 от каждого и не «приклеена» к фону. Только у самого края радиуса, где все веса малы, влияние
     * плавно затухает до нуля (smoothstep по сумме весов), чтобы не было скачка на границе.
     */
    fun fieldAt(u: Float, v: Float, pins: List<Pin>, off: FloatArray, w: Float, h: Float, out: FloatArray = FloatArray(2)): FloatArray {
        var sw = 0f; var sx = 0f; var sy = 0f; var sh = 0f
        for ((i, p) in pins.withIndex()) {
            val wt = weight(p, hypot((u - p.u) * w, (v - p.v) * h))
            if (wt <= 0f) continue
            val k = sharp(wt)
            sw += wt; sh += k; sx += k * off[i * 2]; sy += k * off[i * 2 + 1]
        }
        if (sw <= 1e-6f) { out[0] = 0f; out[1] = 0f; return out }
        val t = (sw / FULL_INFLUENCE).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        out[0] = sx / sh * s; out[1] = sy / sh * s
        return out
    }

    /** С такого числа пинов на слое включается линия (Deform Path): прядь изгибается вдоль сплайна через пины (2 пина - прямая). */
    const val PATH_MIN_PINS = 2
    /** Максимальная жёсткость пружины мягкого пина (в центре пина); к краю радиуса спадает до нуля. */
    const val LAMBDA_MAX = 100f

    private class Cached(val w: Float, val h: Float, val hash: Int, val sys: ArapSystem, val adj: Array<IntArray>)
    private class GeoCache(val key: Int, val dist: Array<FloatArray>)
    private class PathCache(val key: Int, val rest: PathMath.Curve, val bind: PathDeformer.Binding)
    private val cache = java.util.WeakHashMap<Mesh, Cached>()
    private val geoCache = java.util.WeakHashMap<Mesh, GeoCache>()
    private val pathCache = java.util.WeakHashMap<Mesh, PathCache>()

    private fun systemFor(mesh: Mesh, vo: FloatArray, w: Float, h: Float, q: FloatArray): Cached {
        val hash = vo.contentHashCode()
        synchronized(cache) {
            val c = cache[mesh]
            if (c != null && c.w == w && c.h == h && c.hash == hash) return c
            val nc = Cached(w, h, hash, ArapSystem(q, mesh.tris), MeshGraph.adjacency(mesh.count, mesh.tris))
            cache[mesh] = nc
            return nc
        }
    }

    /** Геодезические расстояния (пиксели) от каждого пина до каждой вершины: по рёбрам сетки, а не по прямой. */
    private fun geoFor(mesh: Mesh, q: FloatArray, adj: Array<IntArray>, pins: List<Pin>, w: Float, h: Float): Array<FloatArray> {
        val key = (q.contentHashCode() * 31 + pins.hashCode()) * 31 + w.hashCode() * 7 + h.hashCode()
        synchronized(geoCache) {
            geoCache[mesh]?.let { if (it.key == key) return it.dist }
            val edge = MeshGraph.meanEdge(q, adj)
            val res = Array(pins.size) { pi ->
                val px = pins[pi].u * w; val py = pins[pi].v * h
                val e = FloatArray(mesh.count) { hypot(q[it * 2] - px, q[it * 2 + 1] - py) }
                val nd = e.minOrNull() ?: 0f
                val src = ArrayList<Int>(); val init = ArrayList<Float>()
                for (k in e.indices) if (e[k] <= nd + 1.5f * edge) { src.add(k); init.add(e[k]) }
                MeshGraph.geodesic(q, adj, src.toIntArray(), init.toFloatArray())
            }
            geoCache[mesh] = GeoCache(key, res)
            return res
        }
    }

    /**
     * Добавляет деформацию пинов к смещениям вершин [vertexOffsets] (2 числа на вершину, доли слоя). Нулевые смещения
     * пинов возвращают исходные смещения без расчёта.
     *  - С [PATH_MIN_PINS] (2) и больше пинов: Deform Path (линия через пины, привязка t/d как в Live2D, см. [PathDeformer]).
     *  - Иначе: ARAP с МЯГКИМИ пинами `(L + Lambda) P = b + Lambda P_target`, где Lambda - от [LAMBDA_MAX] в центре пина
     *    до 0 на краю радиуса. Расстояния геодезические (по рёбрам сетки): влияние не перепрыгивает на соседние пряди.
     *    Жёсткий (Stiff) пин дополнительно закрепляет кластер вершин (60% радиуса) как твёрдое тело. Вершины вне
     *    радиусов всех пинов - якоря. Итог умножается на нормированное влияние пинов, у края радиуса оно затухает.
     */
    fun addTo(mesh: Mesh, vertexOffsets: FloatArray, pins: List<Pin>, pinOffsets: FloatArray, w: Float, h: Float,
              scaleX: Float = 1f, scaleY: Float = 1f): FloatArray {
        if (pins.isEmpty() || mesh.count == 0) return vertexOffsets
        if ((0 until pins.size * 2).all { kotlin.math.abs(pinOffsets[it]) < 1e-6f }) return vertexOffsets
        val n = mesh.count
        if (pins.size >= PATH_MIN_PINS) {
            // спринт 43: путь считается в пикселях С УЧЁТОМ масштаба слоя - нормали и ширина верны и у растянутого слоя
            val sx = kotlin.math.abs(scaleX).takeIf { it.isFinite() && it > 1e-3f } ?: 1f
            val sy = kotlin.math.abs(scaleY).takeIf { it.isFinite() && it > 1e-3f } ?: 1f
            val ww = w * sx; val hh = h * sy
            val qs = FloatArray(n * 2) { if (it % 2 == 0) (mesh.u(it / 2) + vertexOffsets[it]) * ww else (mesh.v(it / 2) + vertexOffsets[it]) * hh }
            return pathAddTo(mesh, vertexOffsets, pins, pinOffsets, ww, hh, qs)
        }
        val q = FloatArray(n * 2) { if (it % 2 == 0) (mesh.u(it / 2) + vertexOffsets[it]) * w else (mesh.v(it / 2) + vertexOffsets[it]) * h }
        val c = systemFor(mesh, vertexOffsets, w, h, q)
        val geo = geoFor(mesh, q, c.adj, pins, w, h)
        val avg = FloatArray(n * 2)
        val sumW = FloatArray(n)
        val infl = FloatArray(n)
        for (k in 0 until n) {
            var sw = 0f; var sx = 0f; var sy = 0f; var sh = 0f
            for (p in pins.indices) {
                val wt = weight(pins[p], geo[p][k])
                if (wt <= 0f) continue
                val sk = sharp(wt)
                sw += wt; sh += sk; sx += sk * pinOffsets[p * 2]; sy += sk * pinOffsets[p * 2 + 1]
            }
            sumW[k] = sw
            if (sw > 1e-6f) { avg[k * 2] = sx / sh; avg[k * 2 + 1] = sy / sh }
            val t = (sw / FULL_INFLUENCE).coerceIn(0f, 1f)
            infl[k] = t * t * (3f - 2f * t)
        }
        val target = HashMap<Int, FloatArray>()
        for ((i, p) in pins.withIndex()) {
            // ВСЕ пины (не только активный и жёсткие) - жёсткие граничные условия: ближайшая вершина идёт ровно за пином
            val dx = pinOffsets[i * 2] * w; val dy = pinOffsets[i * 2 + 1] * h
            val rc = if (p.stiff) p.radius * 0.6f else 0f
            var nearest = 0; var nd = Float.MAX_VALUE
            for (k in 0 until n) {
                val d = geo[i][k]
                if (d < nd) { nd = d; nearest = k }
                if (d <= rc) target[k] = floatArrayOf(q[k * 2] + dx, q[k * 2 + 1] + dy)
            }
            target.getOrPut(nearest) { floatArrayOf(q[nearest * 2] + dx, q[nearest * 2 + 1] + dy) }
        }
        for (k in 0 until n) if (sumW[k] < 0.01f && k !in target) target[k] = floatArrayOf(q[k * 2], q[k * 2 + 1])
        val fixed = target.keys.toIntArray()
        val tg = FloatArray(fixed.size * 2)
        for ((j, k) in fixed.withIndex()) { tg[j * 2] = target.getValue(k)[0]; tg[j * 2 + 1] = target.getValue(k)[1] }
        // пружины мягких пинов: цель - нормированное среднее смещение пинов, жёсткость - от суммарного веса
        val spring = FloatArray(n * 2) { if (it % 2 == 0) q[it] + avg[it] * w else q[it] + avg[it] * h }
        val lambda = FloatArray(n) { LAMBDA_MAX * sumW[it].coerceIn(0f, 1f) }
        val p = c.sys.solve(fixed, tg, spring, lambda = lambda, springTarget = spring)
        val out = vertexOffsets.copyOf()
        for (k in 0 until n) {
            out[k * 2] += infl[k] * (p[k * 2] - q[k * 2]) / w
            out[k * 2 + 1] += infl[k] * (p[k * 2 + 1] - q[k * 2 + 1]) / h
        }
        return clampMoves(vertexOffsets, out, maxPinMovePx(pinOffsets, w, h), w, h)
    }

    /**
     * Спринт 43: совет по плотности - true, если вдоль пути через пины слишком мало рядов вершин для плавного изгиба
     * (см. [PathDeformer.needsDenserMesh]). Пинов меньше двух - путь не строится, совет не нужен.
     */
    fun pathNeedsDenserMesh(mesh: Mesh, vertexOffsets: FloatArray, pins: List<Pin>, w: Float, h: Float): Boolean {
        if (pins.size < PATH_MIN_PINS || mesh.count == 0) return false
        val q = FloatArray(mesh.count * 2) { if (it % 2 == 0) (mesh.u(it / 2) + vertexOffsets.getOrElse(it) { 0f }) * w else (mesh.v(it / 2) + vertexOffsets.getOrElse(it) { 0f }) * h }
        val rest = PathMath.build(FloatArray(pins.size * 2) { if (it % 2 == 0) pins[it / 2].u * w else pins[it / 2].v * h }) ?: return false
        return PathDeformer.needsDenserMesh(PathDeformer.bind(q, mesh.tris, rest, pins.maxOf { it.radius }))
    }

    /** Наибольшее смещение пина в пикселях. */
    private fun maxPinMovePx(po: FloatArray, w: Float, h: Float): Float {
        var m = 0f
        for (i in 0 until po.size / 2) m = maxOf(m, hypot(po[i * 2] * w, po[i * 2 + 1] * h))
        return m
    }

    /**
     * Защита от «вылета» вершин: добавка к смещению каждой вершины не больше [CLAMP_K] * смещение пина + [CLAMP_PAD] px
     * (иначе пин или кусок сетки может улететь за пределы слоя); нечисловые значения откатываются к исходным.
     */
    fun clampMoves(base: FloatArray, out: FloatArray, maxPinPx: Float, w: Float, h: Float): FloatArray {
        val lim = maxPinPx * CLAMP_K + CLAMP_PAD
        for (k in 0 until out.size / 2) {
            var dx = (out[k * 2] - base[k * 2]) * w; var dy = (out[k * 2 + 1] - base[k * 2 + 1]) * h
            if (!dx.isFinite() || !dy.isFinite()) { out[k * 2] = base[k * 2]; out[k * 2 + 1] = base[k * 2 + 1]; continue }
            val len = hypot(dx, dy)
            if (len > lim) { val s = lim / len; dx *= s; dy *= s; out[k * 2] = base[k * 2] + dx / w; out[k * 2 + 1] = base[k * 2 + 1] + dy / h }
        }
        return out
    }
    const val CLAMP_K = 1.5f
    const val CLAMP_PAD = 4f

    /**
     * Deform Path (спринт 43 - как Path Deformer в Live2D, см. [PathDeformer]): сплайн через пины в исходном положении и
     * в текущем; вершины восстанавливаются из (t - доля длины, d - смещение по нормали) с мягким корнем.
     */
    private fun pathAddTo(mesh: Mesh, vo: FloatArray, pins: List<Pin>, po: FloatArray, w: Float, h: Float, q: FloatArray): FloatArray {
        val m = pins.size
        val restPts = FloatArray(m * 2) { if (it % 2 == 0) pins[it / 2].u * w else pins[it / 2].v * h }
        val curPts = FloatArray(m * 2) { if (it % 2 == 0) (pins[it / 2].u + po[it]) * w else (pins[it / 2].v + po[it]) * h }
        val radius = pins.maxOf { it.radius }
        val key = ((q.contentHashCode() * 31 + restPts.contentHashCode()) * 31 + radius.hashCode()) * 31 + w.hashCode() * 7 + h.hashCode()
        val pc = synchronized(pathCache) {
            val c = pathCache[mesh]
            if (c != null && c.key == key) c else {
                val rest = PathMath.build(restPts) ?: return vo
                PathCache(key, rest, PathDeformer.bind(q, mesh.tris, rest, radius)).also { pathCache[mesh] = it }
            }
        }
        val cur = PathMath.build(curPts) ?: return vo
        val p = PathDeformer.deform(q, pc.bind, cur)
        val out = vo.copyOf()
        for (k in 0 until mesh.count) {
            out[k * 2] += (p[k * 2] - q[k * 2]) / w
            out[k * 2 + 1] += (p[k * 2 + 1] - q[k * 2 + 1]) / h
        }
        return clampMoves(vo, out, maxPinMovePx(po, w, h), w, h)
    }
}
