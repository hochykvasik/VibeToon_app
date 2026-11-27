package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/**
 * Сетка слоя: вершины в исходном положении (u, v - доли размера слоя) и треугольники (индексы вершин).
 * Топология статична; смещение вершины хранится как LayerTransform (dx, dy в долях слоя) под id
 * [MeshMath.vertexId], поэтому ключи, Undo и сохранение работают как у обычных узлов анимации.
 */
class Mesh(val verts: FloatArray, val tris: IntArray) {
    val count: Int get() = verts.size / 2
    fun u(k: Int) = verts[k * 2]
    fun v(k: Int) = verts[k * 2 + 1]
}

object MeshMath {
    const val MAX_VERTS = 1024
    const val ID_BASE = 100_000_000

    fun vertexId(layerId: Int, k: Int) = ID_BASE + layerId * MAX_VERTS + k
    fun isVertexId(id: Int) = id >= ID_BASE

    /** Смещения вершин (2 числа на вершину) на кадре; [fx] = false - только форма привязки. */
    fun offsets(layerId: Int, mesh: Mesh, frame: Int, base: Map<Int, LayerTransform>, anim: Animation, fx: Boolean): FloatArray {
        val out = FloatArray(mesh.count * 2)
        for (k in 0 until mesh.count) {
            val id = vertexId(layerId, k)
            var dx = base[id]?.dx ?: 0f
            var dy = base[id]?.dy ?: 0f
            if (fx) anim.tracks[id]?.evaluate(frame)?.let { dx += it.dx; dy += it.dy }
            out[k * 2] = dx
            out[k * 2 + 1] = dy
        }
        return out
    }

    /**
     * Спринт 46: то же, что [offsets], но анимированная часть (дорожки вершин на кадре) берётся из [cache]: при ~100
     * слоях с сеткой жест на одном слое перестраивает сцену на каждое движение пальца, и раньше каждый раз заново
     * считались дорожки всех вершин всех сеток (до 1024 на слой). Ручные смещения ([base]) читаются всегда.
     */
    fun offsets(layerId: Int, mesh: Mesh, frame: Int, base: Map<Int, LayerTransform>, anim: Animation, fx: Boolean, cache: MeshTrackCache): FloatArray {
        val out = FloatArray(mesh.count * 2)
        val track = if (fx) cache.trackPart(layerId, mesh.count, frame, anim) else null
        for (k in 0 until mesh.count) {
            val b = base[vertexId(layerId, k)]
            out[k * 2] = (b?.dx ?: 0f) + (track?.get(k * 2) ?: 0f)
            out[k * 2 + 1] = (b?.dy ?: 0f) + (track?.get(k * 2 + 1) ?: 0f)
        }
        return out
    }

    /** Все id вершин сетки слоя (для очистки данных). */
    fun ids(layerId: Int, mesh: Mesh): Set<Int> = (0 until mesh.count).mapTo(HashSet()) { vertexId(layerId, it) }

    /**
     * Равномерная сетка: около [density] ячеек по длинной стороне слоя w x h (пиксели), вершин не больше MAX_VERTS.
     */
    fun generate(w: Int, h: Int, density: Int): Mesh {
        val (cols, rows) = gridSize(w, h, density)
        val v = FloatArray((cols + 1) * (rows + 1) * 2)
        var p = 0
        for (j in 0..rows) for (i in 0..cols) {
            v[p++] = i.toFloat() / cols
            v[p++] = j.toFloat() / rows
        }
        return Mesh(v, triangulate(v, w.coerceAtLeast(1).toFloat(), h.coerceAtLeast(1).toFloat()))
    }

    /** Число ячеек (столбцов, строк) сетки: около [density] по длинной стороне, вершин не больше MAX_VERTS. */
    fun gridSize(w: Int, h: Int, density: Int): Pair<Int, Int> {
        val ww = w.coerceAtLeast(1).toFloat()
        val hh = h.coerceAtLeast(1).toFloat()
        var n = density.coerceAtLeast(1)
        while (true) {
            val step = maxOf(ww, hh) / n
            val cols = Math.round(ww / step).coerceAtLeast(1)
            val rows = Math.round(hh / step).coerceAtLeast(1)
            if ((cols + 1) * (rows + 1) <= MAX_VERTS || n == 1) return cols to rows
            n--
        }
    }

    /**
     * Добавляет вершину (u, v) в исходных координатах: треугольник, внутри которого она лежит, делится на три.
     * Топология вокруг (разрезы, контур) не меняется, индексы старых вершин сохраняются.
     * null: лимит вершин, точка вне сетки (в разрезе) или почти совпадает с существующей.
     */
    fun addVertex(mesh: Mesh, u: Float, v: Float, w: Int, h: Int): Mesh? {
        if (mesh.count >= MAX_VERTS) return null
        for (k in 0 until mesh.count) {   // дубликат (почти в той же точке) не добавляем
            val dx = (mesh.u(k) - u) * w; val dy = (mesh.v(k) - v) * h
            if (dx * dx + dy * dy < 4f) return null
        }
        for (t in 0 until mesh.tris.size / 3) {
            val a = mesh.tris[t * 3]; val b = mesh.tris[t * 3 + 1]; val c = mesh.tris[t * 3 + 2]
            if (!inside(u, v, mesh.u(a), mesh.v(a), mesh.u(b), mesh.v(b), mesh.u(c), mesh.v(c))) continue
            val n = mesh.count
            val nt = mesh.tris.copyOf(mesh.tris.size + 6)
            nt[t * 3 + 2] = n                                   // (a, b, new)
            nt[mesh.tris.size] = b; nt[mesh.tris.size + 1] = c; nt[mesh.tris.size + 2] = n
            nt[mesh.tris.size + 3] = c; nt[mesh.tris.size + 4] = a; nt[mesh.tris.size + 5] = n
            return Mesh(mesh.verts + floatArrayOf(u, v), nt)
        }
        return null
    }

    private fun inside(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val neg = d1 < -1e-9f || d2 < -1e-9f || d3 < -1e-9f
        val pos = d1 > 1e-9f || d2 > 1e-9f || d3 > 1e-9f
        return !(neg && pos)
    }

    /** Индексы треугольников (по деформированным позициям u+dx, v+dy), которые задел отрезок A-B. */
    fun trianglesHit(mesh: Mesh, off: FloatArray, ax: Float, ay: Float, bx: Float, by: Float): List<Int> {
        val out = ArrayList<Int>()
        fun px(k: Int) = mesh.u(k) + off[k * 2]
        fun py(k: Int) = mesh.v(k) + off[k * 2 + 1]
        fun cross(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1)
        fun segs(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float): Boolean {
            val d1 = cross(x3, y3, x4, y4, x1, y1); val d2 = cross(x3, y3, x4, y4, x2, y2)
            val d3 = cross(x1, y1, x2, y2, x3, y3); val d4 = cross(x1, y1, x2, y2, x4, y4)
            return d1 * d2 <= 0f && d3 * d4 <= 0f && !(d1 == 0f && d2 == 0f)
        }
        for (t in 0 until mesh.tris.size / 3) {
            val a = mesh.tris[t * 3]; val b = mesh.tris[t * 3 + 1]; val c = mesh.tris[t * 3 + 2]
            val hit = inside(ax, ay, px(a), py(a), px(b), py(b), px(c), py(c)) ||
                inside(bx, by, px(a), py(a), px(b), py(b), px(c), py(c)) ||
                segs(ax, ay, bx, by, px(a), py(a), px(b), py(b)) ||
                segs(ax, ay, bx, by, px(b), py(b), px(c), py(c)) ||
                segs(ax, ay, bx, by, px(c), py(c), px(a), py(a))
            if (hit) out.add(t)
        }
        return out
    }

    /** Сетка без указанных треугольников (вершины пока остаются, индексы не меняются). */
    fun removeTriangles(mesh: Mesh, remove: Set<Int>): Mesh {
        val keep = ArrayList<Int>()
        for (t in 0 until mesh.tris.size / 3) if (t !in remove) { keep.add(mesh.tris[t * 3]); keep.add(mesh.tris[t * 3 + 1]); keep.add(mesh.tris[t * 3 + 2]) }
        return Mesh(mesh.verts, keep.toIntArray())
    }

    /**
     * Удаляет вершину [k] и перестраивает треугольники вокруг неё. Внутренняя вершина: дыра (кольцо соседей)
     * заполняется методом «отрезания ушей» в пикселях слоя. Граничная вершина или кольцо, которое не
     * удалось замкнуть: её треугольники просто убираются. Индексы остальных вершин не меняются (вершина k
     * остаётся без треугольников - её уберёт [compact]). null, если вершины нет в сетке.
     */
    fun removeVertex(mesh: Mesh, k: Int, w: Float, h: Float): Mesh? {
        if (k !in 0 until mesh.count) return null
        val keep = ArrayList<Int>()
        val ring = ArrayList<IntArray>()   // рёбра кольца (a, b) в порядке обхода треугольников
        var any = false
        for (t in 0 until mesh.tris.size / 3) {
            val a = mesh.tris[t * 3]; val b = mesh.tris[t * 3 + 1]; val c = mesh.tris[t * 3 + 2]
            val at = when (k) { a -> 0; b -> 1; c -> 2; else -> -1 }
            if (at < 0) { keep.add(a); keep.add(b); keep.add(c); continue }
            any = true
            val p = intArrayOf(a, b, c)
            ring.add(intArrayOf(p[(at + 1) % 3], p[(at + 2) % 3]))   // ребро напротив k, направление сохраняется
        }
        if (!any) return null
        // цепочка рёбер кольца: next[a] = b
        val next = HashMap<Int, Int>()
        var ok = true
        for (e in ring) { if (next.containsKey(e[0])) ok = false; next[e[0]] = e[1] }
        val loop = ArrayList<Int>()
        if (ok && ring.size >= 3) {
            var cur = ring[0][0]
            for (i in 0..ring.size) {
                loop.add(cur)
                cur = next[cur] ?: run { ok = false; -1 }
                if (!ok) break
                if (cur == ring[0][0]) break
            }
            if (ok && (cur != ring[0][0] || loop.size != ring.size)) ok = false
        } else ok = false
        if (ok) {
            fun px(i: Int) = mesh.u(i) * w
            fun py(i: Int) = mesh.v(i) * h
            fun cross(a: Int, b: Int, c: Int) = (px(b) - px(a)) * (py(c) - py(a)) - (py(b) - py(a)) * (px(c) - px(a))
            // ориентация кольца как у исходных треугольников: знак площади по сумме
            var area = 0f
            for (i in loop.indices) { val a = loop[i]; val b = loop[(i + 1) % loop.size]; area += px(a) * py(b) - px(b) * py(a) }
            val sign = if (area >= 0f) 1f else -1f
            val poly = ArrayList(loop)
            val out = ArrayList<Int>()
            var guard = 0
            while (poly.size > 3 && guard++ < 10000) {
                var cut = false
                for (i in poly.indices) {
                    val a = poly[(i + poly.size - 1) % poly.size]; val b = poly[i]; val c = poly[(i + 1) % poly.size]
                    if (cross(a, b, c) * sign <= 1e-6f) continue   // вогнутая вершина
                    var inside = false
                    for (o in poly) {
                        if (o == a || o == b || o == c) continue
                        val d1 = cross(a, b, o) * sign; val d2 = cross(b, c, o) * sign; val d3 = cross(c, a, o) * sign
                        if (d1 >= 0f && d2 >= 0f && d3 >= 0f) { inside = true; break }
                    }
                    if (inside) continue
                    out.add(a); out.add(b); out.add(c); poly.removeAt(i); cut = true; break
                }
                if (!cut) { ok = false; break }
            }
            if (ok && poly.size == 3) { out.add(poly[0]); out.add(poly[1]); out.add(poly[2]) }
            if (ok) keep.addAll(out)
        }
        return Mesh(mesh.verts, keep.toIntArray())
    }

    /** Ближайшая к точке (u, v) вершина (с учётом смещений [off]) в радиусе [maxDist] (доли слоя) или -1. */
    fun nearestVertex(mesh: Mesh, off: FloatArray, u: Float, v: Float, maxDist: Float): Int {
        var best = -1; var bd = maxDist * maxDist
        for (k in 0 until mesh.count) {
            val dx = mesh.u(k) + off[k * 2] - u; val dy = mesh.v(k) + off[k * 2 + 1] - v
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = k }
        }
        return best
    }

    /**
     * Сглаживание деформации: каждое ребро делится пополам, смещение середины = 3/8 (a + b) + 1/8 (c + d)
     * (правило ребра Loop; c, d - вершины напротив ребра; на границе - среднее a и b). Исходные вершины
     * остаются на месте, поэтому ручные точки не «уплывают», а поверхность между ними изгибается плавно
     * без заломов. [levels] раз подряд. Возвращает (сетка, смещения): сетка с новыми вершинами (u, v - среднее).
     */
    fun smooth(mesh: Mesh, off: FloatArray, levels: Int): Pair<Mesh, FloatArray> {
        var m = mesh
        var o = off
        for (lv in 0 until levels) {
            if (m.count * 4 > 60000 || m.tris.isEmpty()) break
            val opposite = HashMap<Long, IntArray>()   // ребро -> до двух противолежащих вершин
            fun key(a: Int, b: Int) = minOf(a, b).toLong() * 1000003L + maxOf(a, b)
            for (t in 0 until m.tris.size / 3) {
                val a = m.tris[t * 3]; val b = m.tris[t * 3 + 1]; val c = m.tris[t * 3 + 2]
                for ((p, q, r) in listOf(Triple(a, b, c), Triple(b, c, a), Triple(c, a, b))) {
                    val e = opposite.getOrPut(key(p, q)) { intArrayOf(-1, -1) }
                    if (e[0] < 0) e[0] = r else if (e[1] < 0) e[1] = r
                }
            }
            val verts = ArrayList<Float>(m.verts.size * 2)
            for (v in m.verts) verts.add(v)
            val offs = ArrayList<Float>(o.size * 2)
            for (v in o) offs.add(v)
            val mid = HashMap<Long, Int>()
            fun midOf(a: Int, b: Int): Int = mid.getOrPut(key(a, b)) {
                val i = verts.size / 2
                verts.add((m.u(a) + m.u(b)) / 2f); verts.add((m.v(a) + m.v(b)) / 2f)
                val e = opposite[key(a, b)]!!
                for (axis in 0..1) {
                    val ab = o[a * 2 + axis] + o[b * 2 + axis]
                    offs.add(if (e[1] >= 0 && e[0] >= 0) 3f / 8f * ab + 1f / 8f * (o[e[0] * 2 + axis] + o[e[1] * 2 + axis]) else ab / 2f)
                }
                i
            }
            val tris = ArrayList<Int>(m.tris.size * 4)
            for (t in 0 until m.tris.size / 3) {
                val a = m.tris[t * 3]; val b = m.tris[t * 3 + 1]; val c = m.tris[t * 3 + 2]
                val ab = midOf(a, b); val bc = midOf(b, c); val ca = midOf(c, a)
                tris.addAll(listOf(a, ab, ca, ab, b, bc, ca, bc, c, ab, bc, ca))
            }
            m = Mesh(verts.toFloatArray(), tris.toIntArray())
            o = offs.toFloatArray()
        }
        return m to o
    }

    /** Выбрасывает вершины без треугольников; возвращает сетку и таблицу «старый индекс -> новый» (-1 = удалена). */
    fun compact(mesh: Mesh): Pair<Mesh, IntArray> {
        val map = IntArray(mesh.count) { -1 }
        var n = 0
        for (t in mesh.tris) if (map[t] < 0) map[t] = 0
        for (k in 0 until mesh.count) if (map[k] == 0) map[k] = n++
        val v = FloatArray(n * 2)
        for (k in 0 until mesh.count) if (map[k] >= 0) { v[map[k] * 2] = mesh.u(k); v[map[k] * 2 + 1] = mesh.v(k) }
        return Mesh(v, IntArray(mesh.tris.size) { map[mesh.tris[it]] }) to map
    }

    /**
     * Сетка по контуру: ячейки обычной сетки (как в [generate]) остаются, если в них есть непрозрачные пиксели
     * (альфа > 20 в карте alpha размером aw x ah). null, если слой полностью прозрачен.
     */
    fun fromAlpha(w: Int, h: Int, alpha: ByteArray, aw: Int, ah: Int, density: Int): Mesh? {
        if (aw <= 0 || ah <= 0 || alpha.size < aw * ah) return null
        val (cols, rows) = gridSize(w, h, density)
        val ids = IntArray((cols + 1) * (rows + 1)) { -1 }
        val verts = ArrayList<Float>()
        val tris = ArrayList<Int>()
        fun vid(i: Int, j: Int): Int {
            val g = j * (cols + 1) + i
            if (ids[g] < 0) { ids[g] = verts.size / 2; verts.add(i.toFloat() / cols); verts.add(j.toFloat() / rows) }
            return ids[g]
        }
        for (j in 0 until rows) for (i in 0 until cols) {
            val x0 = (i * aw) / cols; val x1 = (((i + 1) * aw + cols - 1) / cols).coerceAtMost(aw)
            val y0 = (j * ah) / rows; val y1 = (((j + 1) * ah + rows - 1) / rows).coerceAtMost(ah)
            var solid = false
            loop@ for (y in y0 until y1) for (x in x0 until x1) if ((alpha[y * aw + x].toInt() and 0xFF) > 20) { solid = true; break@loop }
            if (!solid) continue
            val a = vid(i, j); val b = vid(i + 1, j); val c = vid(i, j + 1); val d = vid(i + 1, j + 1)
            tris.add(a); tris.add(b); tris.add(c); tris.add(b); tris.add(d); tris.add(c)
        }
        if (tris.isEmpty()) return null
        return Mesh(verts.toFloatArray(), tris.toIntArray())
    }

    /** Маска фигуры: порог alpha > 15, закрытие (заливает блики и дырки внутри), расширение на 4-6 px. null - слой прозрачен. */
    fun contourMask(alpha: ByteArray, aw: Int, ah: Int): BooleanArray? {
        var mask = BooleanArray(aw * ah) { (alpha[it].toInt() and 0xFF) > 15 }
        if (mask.none { it }) return null
        fun grow(m: BooleanArray, steps: Int, on: Boolean): BooleanArray {
            // on = true: расширение (dilation), false: сжатие (erosion), 4-связность
            var cur = m
            repeat(steps) {
                val next = cur.copyOf()
                for (y in 0 until ah) for (x in 0 until aw) {
                    val i = y * aw + x
                    val l = x > 0 && cur[i - 1]; val r = x < aw - 1 && cur[i + 1]
                    val u = y > 0 && cur[i - aw]; val d = y < ah - 1 && cur[i + aw]
                    next[i] = if (on) cur[i] || l || r || u || d
                              else cur[i] && (x == 0 || cur[i - 1]) && (x == aw - 1 || cur[i + 1]) && (y == 0 || cur[i - aw]) && (y == ah - 1 || cur[i + aw])
                }
                cur = next
            }
            return cur
        }
        val closeR = maxOf(2, maxOf(aw, ah) / 150)
        mask = grow(grow(mask, closeR, true), closeR, false)
        val margin = (maxOf(aw, ah) / 170).coerceIn(4, 6)
        return grow(mask, margin, true)
    }

    /**
     * Автоматический шаг сетки (пиксели слоя): `clamp(sqrt(W*H) / (18 * sqrt(Complexity)), 16, 128)`, где
     * Complexity = P^2 / (4 pi A) (1 у круга, растёт с извилистостью контура). Периметр P на пиксельной сетке считается как
     * среднее числа рёбер и числа граничных пикселей: лесенка по диагонали не завышает его в 1.4 раза.
     */
    fun autoGridStep(w: Int, h: Int, mask: BooleanArray, aw: Int, ah: Int): Float {
        fun inM(x: Int, y: Int) = x in 0 until aw && y in 0 until ah && mask[y * aw + x]
        var area = 0; var edges = 0; var bpix = 0
        for (y in 0 until ah) for (x in 0 until aw) if (mask[y * aw + x]) {
            area++
            var e = 0
            if (!inM(x - 1, y)) e++
            if (!inM(x + 1, y)) e++
            if (!inM(x, y - 1)) e++
            if (!inM(x, y + 1)) e++
            edges += e
            if (e > 0) bpix++
        }
        if (area == 0) return 128f
        val sx = w.toFloat() / aw; val sy = h.toFloat() / ah
        val p = 0.5f * (edges + bpix) * (sx + sy) / 2f
        val a = area * sx * sy
        val complexity = (p * p / (4f * Math.PI.toFloat() * a)).coerceAtLeast(1f)
        return (Math.sqrt(w.toDouble() * h).toFloat() / (18f * Math.sqrt(complexity.toDouble()).toFloat())).coerceIn(16f, 128f)
    }

    /**
     * Сетка по контуру с авто-шагом: см. [autoGridStep]. Пресеты: Standard ([high] = false) - шаг x1.5, мало вершин, для
     * лёгких деформаций; High - шаг x0.55, много вершин (до [MAX_VERTS]) для сложных изгибов (волосы, одежда).
     * null, если слой прозрачен.
     */
    fun fromContourAuto(w: Int, h: Int, alpha: ByteArray, aw: Int, ah: Int, high: Boolean = false): Mesh? {
        if (aw <= 0 || ah <= 0 || alpha.size < aw * ah || w <= 0 || h <= 0) return null
        val mask = contourMask(alpha, aw, ah) ?: return null
        val stepPx = (autoGridStep(w, h, mask, aw, ah) * (if (high) 0.55f else 1.5f)).coerceIn(10f, 192f)
        // шаг в пикселях слоя -> в ячейках карты; внутренняя шахматная сетка идёт с шагом 1.7 s
        return contourMesh(w, h, mask, aw, ah, stepPx / (w.toFloat() / aw) / 1.7f)
    }

    /**
     * Сетка как в Clip Studio: плотные вершины по контуру фигуры, редкие внутри (шахматная раскладка), триангуляция Делоне,
     * треугольники вне формы удалены. [density] - число вершин контура на половине длинной стороны (ручной режим, для
     * тестов); общее число вершин не больше [MAX_VERTS]. null, если слой прозрачен.
     */
    fun fromContour(w: Int, h: Int, alpha: ByteArray, aw: Int, ah: Int, density: Int): Mesh? {
        if (aw <= 0 || ah <= 0 || alpha.size < aw * ah || w <= 0 || h <= 0) return null
        val mask = contourMask(alpha, aw, ah) ?: return null
        return contourMesh(w, h, mask, aw, ah, maxOf(aw, ah).toFloat() / (density.coerceAtLeast(2) * 2f))
    }

    private fun contourMesh(w: Int, h: Int, mask: BooleanArray, aw: Int, ah: Int, s0: Float): Mesh? {
        fun inM(x: Int, y: Int) = x in 0 until aw && y in 0 until ah && mask[y * aw + x]
        val boundary = ArrayList<Int>()
        for (y in 0 until ah) for (x in 0 until aw) if (mask[y * aw + x] && (!inM(x - 1, y) || !inM(x + 1, y) || !inM(x, y - 1) || !inM(x, y + 1))) boundary.add(y * aw + x)
        var s = s0
        var pts = FloatArray(0)
        for (attempt in 0 until 10) {
            val px = ArrayList<Float>()
            // хэш-сетка с единым размером ячейки G (= минимальная дистанция); поиск соседей по всем ячейкам в радиусе d
            val g = s * 0.6f
            val grid = HashMap<Long, ArrayList<Int>>()
            fun cell(v: Float) = Math.floor((v / g).toDouble()).toLong()
            fun near(x: Float, y: Float, d: Float): Boolean {
                val cx = cell(x); val cy = cell(y)
                val rng = Math.ceil((d / g).toDouble()).toLong()
                for (gx in cx - rng..cx + rng) for (gy in cy - rng..cy + rng) {
                    val l = grid[gx * 100003L + gy] ?: continue
                    for (i in l) { val dx = px[i * 2] - x; val dy = px[i * 2 + 1] - y; if (dx * dx + dy * dy < d * d) return true }
                }
                return false
            }
            fun add(x: Float, y: Float) { grid.getOrPut(cell(x) * 100003L + cell(y)) { ArrayList() }.add(px.size / 2); px.add(x); px.add(y) }
            for (b in boundary) {
                val x = (b % aw) + 0.5f; val y = (b / aw) + 0.5f
                if (!near(x, y, s * 0.6f)) add(x, y)
            }
            val si = s * 1.7f
            var row = 0
            var yy = si / 2
            while (yy < ah) {
                var xx = if (row % 2 == 0) si / 2 else si
                while (xx < aw) {
                    if (inM(xx.toInt(), yy.toInt()) && !near(xx, yy, si * 0.8f) && !near(xx, yy, s * 0.9f)) add(xx, yy)
                    xx += si
                }
                yy += si * 0.87f; row++
            }
            if (px.size / 2 <= MAX_VERTS - 8) { pts = px.toFloatArray(); break }
            s *= 1.25f
        }
        if (pts.size < 6) return null
        for (i in 0 until pts.size / 2) { pts[i * 2] /= aw; pts[i * 2 + 1] /= ah }
        val wf = w.toFloat(); val hf = h.toFloat()
        fun mAt(u: Float, v: Float) = inM((u * aw).toInt().coerceIn(0, aw - 1), (v * ah).toInt().coerceIn(0, ah - 1))
        fun filtered(p: FloatArray): IntArray {
            val all = triangulate(p, wf, hf)
            val keep = ArrayList<Int>()
            for (t in 0 until all.size / 3) {
                val a = all[t * 3]; val b = all[t * 3 + 1]; val c = all[t * 3 + 2]
                val au = p[a * 2]; val av = p[a * 2 + 1]; val bu = p[b * 2]; val bv = p[b * 2 + 1]; val cu = p[c * 2]; val cv = p[c * 2 + 1]
                // без жёсткой проверки центра масс: треугольник остаётся, если фигуре принадлежит центр ИЛИ хотя бы две середины рёбер
                // (тонкий кончик не теряется, дыр внутри нет; пустые «перемычки» вне фигуры отсекаются)
                val center = mAt((au + bu + cu) / 3f, (av + bv + cv) / 3f)
                var mids = 0
                if (mAt((au + bu) / 2f, (av + bv) / 2f)) mids++
                if (mAt((bu + cu) / 2f, (bv + cv) / 2f)) mids++
                if (mAt((au + cu) / 2f, (av + cv) / 2f)) mids++
                if (center || mids >= 2) { keep.add(a); keep.add(b); keep.add(c) }
            }
            return keep.toIntArray()
        }
        var tris = filtered(pts)
        if (tris.isEmpty()) return null
        // уточнение по Руппертy (упрощённо): центр описанной окружности худшего треугольника (угол < 22 град) становится вершиной
        val stepPx = s * 1.7f * (wf / aw)
        repeat(3) {
            val add = ArrayList<Float>()
            for (t in 0 until tris.size / 3) {
                if (pts.size / 2 + add.size / 2 >= MAX_VERTS - 8) break
                val c = circumcenterIfSkinny(pts, tris, t, wf, hf, 22f) ?: continue
                if (c[2] > stepPx * 3f) continue
                val cu = c[0] / wf; val cv = c[1] / hf
                if (cu <= 0f || cu >= 1f || cv <= 0f || cv >= 1f || !mAt(cu, cv)) continue
                var close = false
                for (i in 0 until pts.size / 2) if (Math.hypot(((pts[i * 2] - cu) * wf).toDouble(), ((pts[i * 2 + 1] - cv) * hf).toDouble()) < stepPx * 0.45) { close = true; break }
                if (!close) for (i in 0 until add.size / 2) if (Math.hypot(((add[i * 2] - cu) * wf).toDouble(), ((add[i * 2 + 1] - cv) * hf).toDouble()) < stepPx * 0.45) { close = true; break }
                if (!close) { add.add(cu); add.add(cv) }
            }
            if (add.isEmpty()) return@repeat
            val np = pts + add.toFloatArray()
            val nt = filtered(np)
            if (nt.isNotEmpty()) { pts = np; tris = nt }
        }
        relaxInterior(pts, tris, wf, hf, 4)
        return compact(Mesh(pts, tris)).first
    }

    /** Центр описанной окружности треугольника [t] в пикселях и его радиус [x, y, R], если минимальный угол меньше [minDeg]. */
    private fun circumcenterIfSkinny(pts: FloatArray, tris: IntArray, t: Int, w: Float, h: Float, minDeg: Float): FloatArray? {
        val a = tris[t * 3]; val b = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
        val ax = pts[a * 2] * w; val ay = pts[a * 2 + 1] * h
        val bx = pts[b * 2] * w; val by = pts[b * 2 + 1] * h
        val cx = pts[c * 2] * w; val cy = pts[c * 2 + 1] * h
        val la = Math.hypot((bx - cx).toDouble(), (by - cy).toDouble())
        val lb = Math.hypot((ax - cx).toDouble(), (ay - cy).toDouble())
        val lc = Math.hypot((ax - bx).toDouble(), (ay - by).toDouble())
        if (la < 1e-6 || lb < 1e-6 || lc < 1e-6) return null
        fun ang(opp: Double, p: Double, q: Double) = Math.toDegrees(Math.acos(((p * p + q * q - opp * opp) / (2 * p * q)).coerceIn(-1.0, 1.0)))
        val mn = minOf(ang(la, lb, lc), ang(lb, la, lc), ang(lc, la, lb))
        if (mn >= minDeg) return null
        val d = 2.0 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (Math.abs(d) < 1e-9) return null
        val a2 = ax * ax + ay * ay; val b2 = bx * bx + by * by; val c2 = cx * cx + cy * cy
        val ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d
        val uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
        return floatArrayOf(ux.toFloat(), uy.toFloat(), Math.hypot(ax - ux, ay - uy).toFloat())
    }

    /**
     * Лапласово сглаживание внутренних вершин ([iters] проходов, вершина идёт к среднему соседей): треугольники
     * выравниваются. Граничные вершины (рёбра с одним треугольником) стоят на месте; ход, переворачивающий
     * треугольник, отклоняется. [pts] меняется на месте.
     */
    fun relaxInterior(pts: FloatArray, tris: IntArray, w: Float, h: Float, iters: Int) {
        val n = pts.size / 2
        val edgeCount = HashMap<Long, Int>()
        fun key(a: Int, b: Int) = minOf(a, b).toLong() * 100003L + maxOf(a, b)
        val nb = Array(n) { LinkedHashSet<Int>() }
        val inc = Array(n) { ArrayList<Int>() }
        for (t in 0 until tris.size / 3) for (e in 0 until 3) {
            val a = tris[t * 3 + e]; val b = tris[t * 3 + (e + 1) % 3]
            edgeCount.merge(key(a, b), 1, Int::plus)
            nb[a].add(b); nb[b].add(a); inc[a].add(t)
        }
        val onBorder = BooleanArray(n)
        for ((k, c) in edgeCount) if (c == 1) { onBorder[(k / 100003L).toInt()] = true; onBorder[(k % 100003L).toInt()] = true }
        fun orient(t: Int): Float {
            val a = tris[t * 3]; val b = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
            return ((pts[b * 2] - pts[a * 2]) * w) * ((pts[c * 2 + 1] - pts[a * 2 + 1]) * h) - ((pts[b * 2 + 1] - pts[a * 2 + 1]) * h) * ((pts[c * 2] - pts[a * 2]) * w)
        }
        repeat(iters) {
            for (i in 0 until n) {
                if (onBorder[i] || nb[i].isEmpty() || inc[i].isEmpty()) continue
                var mx = 0f; var my = 0f
                for (j in nb[i]) { mx += pts[j * 2]; my += pts[j * 2 + 1] }
                mx /= nb[i].size; my /= nb[i].size
                val ox = pts[i * 2]; val oy = pts[i * 2 + 1]
                val before = inc[i].map { orient(it) }
                pts[i * 2] = ox + (mx - ox) * 0.6f; pts[i * 2 + 1] = oy + (my - oy) * 0.6f
                var ok = true
                for ((q, t) in inc[i].withIndex()) { val a = orient(t); if (a * before[q] <= 0f || Math.abs(a) < Math.abs(before[q]) * 0.3f) { ok = false; break } }
                if (!ok) { pts[i * 2] = ox; pts[i * 2 + 1] = oy }
            }
        }
    }

    /**
     * Триангуляция Делоне (Bowyer-Watson) по точкам pts (u, v), масштабируемым на размер слоя, чтобы углы
     * считались в пикселях. Возвращает индексы по три на треугольник; сетка накрывает выпуклую оболочку точек.
     */
    fun triangulate(pts: FloatArray, w: Float, h: Float): IntArray {
        val n = pts.size / 2
        if (n < 3) return IntArray(0)
        val xs = DoubleArray(n + 3)
        val ys = DoubleArray(n + 3)
        for (i in 0 until n) { xs[i] = pts[i * 2] * w.toDouble(); ys[i] = pts[i * 2 + 1] * h.toDouble() }
        val big = maxOf(w, h).toDouble() * 20.0 + 1.0
        xs[n] = -big; ys[n] = -big
        xs[n + 1] = big * 2; ys[n + 1] = -big
        xs[n + 2] = w / 2.0; ys[n + 2] = big * 2

        class Tri(val a: Int, val b: Int, val c: Int) {
            var cx = 0.0; var cy = 0.0; var r2 = 0.0
            init {
                val ax = xs[a]; val ay = ys[a]
                val bx = xs[b] - ax; val by = ys[b] - ay
                val cxx = xs[c] - ax; val cyy = ys[c] - ay
                val d = 2.0 * (bx * cyy - by * cxx)
                if (kotlin.math.abs(d) < 1e-12) { cx = 0.0; cy = 0.0; r2 = -1.0 } else {
                    val b2 = bx * bx + by * by; val c2 = cxx * cxx + cyy * cyy
                    val ux = (cyy * b2 - by * c2) / d
                    val uy = (bx * c2 - cxx * b2) / d
                    cx = ax + ux; cy = ay + uy; r2 = ux * ux + uy * uy
                }
            }
            fun contains(px: Double, py: Double): Boolean {
                if (r2 < 0) return false
                val dx = px - cx; val dy = py - cy
                return dx * dx + dy * dy <= r2 * (1.0 + 1e-9)
            }
        }

        var tris = ArrayList<Tri>()
        tris.add(Tri(n, n + 1, n + 2))
        for (i in 0 until n) {
            val bad = tris.filter { it.contains(xs[i], ys[i]) }
            if (bad.isEmpty()) continue
            // граница полости: рёбра, встречающиеся у плохих треугольников ровно один раз
            val edges = HashMap<Long, IntArray>()
            fun key(p: Int, q: Int) = minOf(p, q).toLong() * 100000L + maxOf(p, q)
            for (t in bad) for ((p, q) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val k = key(p, q)
                val e = edges[k]
                if (e == null) edges[k] = intArrayOf(p, q, 1) else e[2]++
            }
            val badSet = bad.toHashSet()
            val next = ArrayList<Tri>(tris.size)
            for (t in tris) if (t !in badSet) next.add(t)
            for (e in edges.values) if (e[2] == 1) next.add(Tri(e[0], e[1], i))
            tris = next
        }
        val out = ArrayList<Int>()
        for (t in tris) {
            if (t.a >= n || t.b >= n || t.c >= n) continue
            // вырожденные (нулевой площади) треугольники отбрасываем
            val area = (xs[t.b] - xs[t.a]) * (ys[t.c] - ys[t.a]) - (ys[t.b] - ys[t.a]) * (xs[t.c] - xs[t.a])
            if (kotlin.math.abs(area) < 1e-9) continue
            out.add(t.a); out.add(t.b); out.add(t.c)
        }
        return out.toIntArray()
    }
}

/**
 * Спринт 46: смещения вершин сеток из дорожек на кадре, по слою. Запись верна, пока тот же кадр, то же число вершин и
 * тот же объект анимации (Animation неизменяема: любая правка ключей создаёт новый объект). Только главный поток.
 */
class MeshTrackCache {
    private class Entry(val count: Int, val frame: Int, val anim: Animation, val v: FloatArray)
    private val map = HashMap<Int, Entry>()

    /** Смещения из дорожек (2 числа на вершину) - общий массив, не изменять. */
    fun trackPart(layerId: Int, count: Int, frame: Int, anim: Animation): FloatArray {
        map[layerId]?.let { e -> if (e.count == count && e.frame == frame && e.anim === anim) return e.v }
        val v = FloatArray(count * 2)
        for (k in 0 until count) {
            anim.tracks[MeshMath.vertexId(layerId, k)]?.evaluate(frame)?.let { v[k * 2] = it.dx; v[k * 2 + 1] = it.dy }
        }
        map[layerId] = Entry(count, frame, anim, v)
        return v
    }

    /** Оставить только слои [ids] (удалённые сетки не держат память). */
    fun retain(ids: Set<Int>) { map.keys.retainAll(ids) }

    fun size(): Int = map.size

    fun clear() = map.clear()
}
