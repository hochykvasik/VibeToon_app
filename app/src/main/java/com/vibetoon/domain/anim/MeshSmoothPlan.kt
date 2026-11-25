package com.vibetoon.domain.anim

import java.util.WeakHashMap

/**
 * Спринт 43: «план» сглаживания сетки по правилу Loop ([MeshMath.smooth]). Топология (новые вершины, их соседи и
 * треугольники) зависит только от сетки, а не от смещений кадра, поэтому считается один раз на сетку и уровень, а на
 * каждом кадре Play/скраббинга остаётся один линейный проход по смещениям - без HashMap, боксинга и списков.
 * Результат побитово совпадает с [MeshMath.smooth] (те же формулы в том же порядке).
 */
class MeshSmoothPlan private constructor(
    /** Итоговая сетка (u, v и треугольники) после всех уровней. */
    val mesh: Mesh,
    /** По уровням: число вершин до уровня и по 4 индекса на новую вершину (a, b, e0, e1; e = -1 - нет соседа). */
    private val levels: List<Pair<Int, IntArray>>
) {
    /** Смещения вершин итоговой сетки для смещений [off] исходной сетки (2 числа на вершину). */
    fun apply(off: FloatArray): FloatArray {
        var o = off
        for ((count, mids) in levels) {
            val n = count + mids.size / 4
            val out = FloatArray(n * 2)
            System.arraycopy(o, 0, out, 0, minOf(o.size, count * 2))
            var w = count * 2
            var i = 0
            while (i < mids.size) {
                val a = mids[i]; val b = mids[i + 1]; val e0 = mids[i + 2]; val e1 = mids[i + 3]
                for (axis in 0..1) {
                    val ab = o[a * 2 + axis] + o[b * 2 + axis]
                    out[w++] = if (e1 >= 0 && e0 >= 0) 3f / 8f * ab + 1f / 8f * (o[e0 * 2 + axis] + o[e1 * 2 + axis]) else ab / 2f
                }
                i += 4
            }
            o = out
        }
        return if (levels.isEmpty()) off.copyOf() else o
    }

    companion object {
        private val cache = WeakHashMap<Mesh, Array<MeshSmoothPlan?>>()

        /** План для [mesh] и [levels] уровней (кэшируется по сетке: сетка неизменяема). */
        fun of(mesh: Mesh, levels: Int): MeshSmoothPlan {
            val lv = levels.coerceIn(0, 3)
            synchronized(cache) { cache[mesh]?.get(lv)?.let { return it } }
            val plan = build(mesh, lv)
            synchronized(cache) { cache.getOrPut(mesh) { arrayOfNulls(4) }[lv] = plan }
            return plan
        }

        private fun build(mesh: Mesh, levels: Int): MeshSmoothPlan {
            var m = mesh
            val out = ArrayList<Pair<Int, IntArray>>()
            for (lv in 0 until levels) {
                if (m.count * 4 > 60000 || m.tris.isEmpty()) break
                val opposite = HashMap<Long, IntArray>()
                fun key(a: Int, b: Int) = minOf(a, b).toLong() * 1000003L + maxOf(a, b)
                val tri = m.tris
                for (t in 0 until tri.size / 3) {
                    val a = tri[t * 3]; val b = tri[t * 3 + 1]; val c = tri[t * 3 + 2]
                    for (e in 0..2) {
                        val p = if (e == 0) a else if (e == 1) b else c
                        val q = if (e == 0) b else if (e == 1) c else a
                        val r = if (e == 0) c else if (e == 1) a else b
                        val slot = opposite.getOrPut(key(p, q)) { intArrayOf(-1, -1) }
                        if (slot[0] < 0) slot[0] = r else if (slot[1] < 0) slot[1] = r
                    }
                }
                val count = m.count
                val verts = FloatArray(count * 2 + opposite.size * 2)
                System.arraycopy(m.verts, 0, verts, 0, count * 2)
                val mids = IntArray(opposite.size * 4)
                val mid = HashMap<Long, Int>(opposite.size * 2)
                var next = count
                fun midOf(a: Int, b: Int): Int {
                    val k = key(a, b)
                    mid[k]?.let { return it }
                    val i = next++
                    verts[i * 2] = (m.u(a) + m.u(b)) / 2f; verts[i * 2 + 1] = (m.v(a) + m.v(b)) / 2f
                    val e = opposite.getValue(k)
                    val j = (i - count) * 4
                    mids[j] = a; mids[j + 1] = b; mids[j + 2] = e[0]; mids[j + 3] = e[1]
                    mid[k] = i
                    return i
                }
                val tris = IntArray(tri.size * 4)
                var p = 0
                for (t in 0 until tri.size / 3) {
                    val a = tri[t * 3]; val b = tri[t * 3 + 1]; val c = tri[t * 3 + 2]
                    val ab = midOf(a, b); val bc = midOf(b, c); val ca = midOf(c, a)
                    tris[p++] = a; tris[p++] = ab; tris[p++] = ca
                    tris[p++] = ab; tris[p++] = b; tris[p++] = bc
                    tris[p++] = ca; tris[p++] = bc; tris[p++] = c
                    tris[p++] = ab; tris[p++] = bc; tris[p++] = ca
                }
                out += count to mids.copyOf((next - count) * 4)
                m = Mesh(verts.copyOf(next * 2), tris)
            }
            return MeshSmoothPlan(m, out)
        }
    }
}
