package com.vibetoon.domain.render

import com.vibetoon.domain.anim.DenseWarpGrid
import com.vibetoon.domain.anim.Mesh
import com.vibetoon.domain.anim.MeshSmoothPlan
import com.vibetoon.domain.model.Affine2D
import kotlin.math.abs

/**
 * Спринт 43: стек деформаций одного слоя. Раньше любое «неподдерживаемое» сочетание (сетка внутри группы с общим Warp,
 * плотный Warp вместе с сеткой, вложенные групповые деформеры, слой, вышедший за сохранённую область группы) бросало
 * исключение, и редактор выключал ВСЕ групповые и плотные деформации сцены с сообщением «Деформация пропущена». Теперь
 * деформации слоя складываются по порядку, а не отключаются:
 *
 *  1. собственная сетка слоя (Mesh, пины, кости-скининг) - после сглаживания Loop;
 *  2. Warp слоя (кубический 4x4 и плотная детализация) - в точках уже деформированной сетки;
 *  3. Perspective слоя (если дальше есть групповые деформеры, иначе её делает шейдер);
 *  4. групповые деформеры от внутреннего к внешнему (голова -> тело): каждый в своих фиксированных локальных осях.
 *
 * Все функции тотальны: точки за пределами области группы продолжают краевое смещение (как шейдер за краем слоя),
 * полюс перспективы не переворачивает геометрию, вырожденные матрицы оставляют вершину на месте.
 */
object DeformStack {
    /** Наименьший |z| перспективы: ближе к полюсу геометрия растягивается, но не переворачивается и не пропадает. */
    const val MIN_Z = 1e-3f
    /** Ячеек сетки для слоя без своей сетки под групповым деформером или с одной перспективой/Warp. */
    const val GROUP_CELLS = 24

    /** Смещение кубического Warp 4x4 (32 числа, как в шейдере) в точке (u, v); за краем - смещение края. */
    fun warpAt(off: FloatArray, u: Float, v: Float, out: FloatArray) {
        val cu = if (u.isFinite()) u.coerceIn(0f, 1f) else 0f
        val cv = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
        val su = 1f - cu; val sv = 1f - cv
        val bu0 = su * su * su; val bu1 = 3f * su * su * cu; val bu2 = 3f * su * cu * cu; val bu3 = cu * cu * cu
        val bv0 = sv * sv * sv; val bv1 = 3f * sv * sv * cv; val bv2 = 3f * sv * cv * cv; val bv3 = cv * cv * cv
        var dx = 0f; var dy = 0f
        for (j in 0..3) {
            val bv = when (j) { 0 -> bv0; 1 -> bv1; 2 -> bv2; else -> bv3 }
            for (i in 0..3) {
                val w = bv * when (i) { 0 -> bu0; 1 -> bu1; 2 -> bu2; else -> bu3 }
                val k = (j * 4 + i) * 2
                dx += w * off[k]; dy += w * off[k + 1]
            }
        }
        out[0] = if (dx.isFinite()) dx else 0f
        out[1] = if (dy.isFinite()) dy else 0f
    }

    /** Знак знаменателя перспективы в центре области: по нему точки у полюса не переворачиваются. */
    fun perspectiveSign(h: FloatArray): Float {
        val z = h[2] * .5f + h[5] * .5f + h[8]
        return if (z < 0f) -1f else 1f
    }

    /** Гомография 3x3 по столбцам (как [com.vibetoon.domain.anim.PerspectiveMath.matrix]) с защитой от полюса. */
    fun perspective(h: FloatArray, x: Float, y: Float, sign: Float, out: FloatArray) {
        var z = h[2] * x + h[5] * y + h[8]
        if (!z.isFinite()) z = sign
        if (z * sign < MIN_Z) z = sign * MIN_Z
        val px = (h[0] * x + h[3] * y + h[6]) / z
        val py = (h[1] * x + h[4] * y + h[7]) / z
        out[0] = if (px.isFinite()) px else x
        out[1] = if (py.isFinite()) py else y
    }

    /** Групповая деформация в локальных осях группы: точка -> деформированная точка (тотально). */
    fun mapGroup(d: SceneBuilder.GroupDeformation, x: Float, y: Float, out: FloatArray, tmp: FloatArray = FloatArray(2)) {
        val b = d.bounds
        val bw = if (b.w.isFinite() && abs(b.w) > 1e-6f) b.w else 1f
        val bh = if (b.h.isFinite() && abs(b.h) > 1e-6f) b.h else 1f
        val bx = if (b.x.isFinite()) b.x else 0f
        val by = if (b.y.isFinite()) b.y else 0f
        var a = (x - bx) / bw; var c = (y - by) / bh
        d.warp?.takeIf { it.size >= 32 }?.let { warpAt(it, a, c, tmp); a += tmp[0]; c += tmp[1] }
        d.perspective?.takeIf { it.size >= 9 }?.let { perspective(it, a, c, perspectiveSign(it), tmp); a = tmp[0]; c = tmp[1] }
        val ox = bx + a * bw; val oy = by + c * bh
        out[0] = if (ox.isFinite()) ox else x
        out[1] = if (oy.isFinite()) oy else y
    }

    /** Активный групповой деформер на пути к слою: деформация и мировая матрица группы. */
    class GroupLevel(val deformation: SceneBuilder.GroupDeformation, val world: Affine2D, val inverse: Affine2D)

    /** Равномерная сетка cells x cells в единичном квадрате (нулевые смещения). */
    fun grid(cells: Int): Mesh {
        val n = cells.coerceIn(1, 64)
        val side = n + 1
        val verts = FloatArray(side * side * 2)
        for (y in 0..n) for (x in 0..n) { val i = (y * side + x) * 2; verts[i] = x.toFloat() / n; verts[i + 1] = y.toFloat() / n }
        val tris = IntArray(n * n * 6); var k = 0
        for (y in 0 until n) for (x in 0 until n) {
            val a = y * side + x; val b = a + 1; val c = a + side; val d = c + 1
            tris[k++] = a; tris[k++] = b; tris[k++] = c; tris[k++] = b; tris[k++] = d; tris[k++] = c
        }
        return Mesh(verts, tris)
    }

    private val gridCache = HashMap<Int, Mesh>()
    fun sharedGrid(cells: Int): Mesh = synchronized(gridCache) { gridCache.getOrPut(cells) { grid(cells) } }

    /**
     * Входы стека одного слоя. [mesh]/[meshOffsets] - своя сетка (уже со скинингом) или null; [smoothLevels] - уровни
     * Loop для своей сетки; [cubic] - Warp слоя (32 числа) или null; [dense] - плотная детализация Warp; [perspective] -
     * гомография слоя, которую нужно запечь (null - её делает шейдер или её нет); [groups] - групповые деформеры от
     * внутреннего к внешнему; [leafWorld] - единичный квадрат слоя -> холст (нужен только при [groups]).
     */
    class Input(
        val mesh: Mesh?,
        val meshOffsets: FloatArray?,
        val smoothLevels: Int,
        val cubic: FloatArray?,
        val dense: DenseWarpGrid?,
        val perspective: FloatArray?,
        val groups: List<GroupLevel>,
        val leafWorld: Affine2D?,
        val cells: Int = GROUP_CELLS
    ) {
        /** Ссылки, сравниваемые по идентичности (неизменяемые объекты), для кэша. */
        fun refs(): Array<Any?> = arrayOf(mesh, dense)

        /** Числа, от которых зависит результат: при совпадении кэш отдаёт готовую сетку без пересчёта. */
        fun signature(): FloatArray {
            var n = 4 + (meshOffsets?.size ?: 0) + (cubic?.size ?: 0) + (perspective?.size ?: 0) + 6
            for (g in groups) n += 4 + 12 + (g.deformation.warp?.size ?: 0) + (g.deformation.perspective?.size ?: 0) + 2
            val s = FloatArray(n); var p = 0
            s[p++] = smoothLevels.toFloat(); s[p++] = cells.toFloat(); s[p++] = groups.size.toFloat(); s[p++] = (meshOffsets?.size ?: -1).toFloat()
            meshOffsets?.let { it.copyInto(s, p); p += it.size }
            cubic?.let { it.copyInto(s, p); p += it.size }
            perspective?.let { it.copyInto(s, p); p += it.size }
            val m = leafWorld ?: Affine2D.IDENTITY
            s[p++] = m.a; s[p++] = m.b; s[p++] = m.c; s[p++] = m.d; s[p++] = m.tx; s[p++] = m.ty
            for (g in groups) {
                val b = g.deformation.bounds
                s[p++] = b.x; s[p++] = b.y; s[p++] = b.w; s[p++] = b.h
                for (mm in arrayOf(g.world, g.inverse)) { s[p++] = mm.a; s[p++] = mm.b; s[p++] = mm.c; s[p++] = mm.d; s[p++] = mm.tx; s[p++] = mm.ty }
                s[p++] = (g.deformation.warp?.size ?: -1).toFloat(); s[p++] = (g.deformation.perspective?.size ?: -1).toFloat()
                g.deformation.warp?.let { it.copyInto(s, p); p += it.size }
                g.deformation.perspective?.let { it.copyInto(s, p); p += it.size }
            }
            return if (p == n) s else s.copyOf(p)
        }
    }

    /** Итог стека: сетка (исходные u, v - текстурные координаты) и смещения вершин в долях слоя. */
    class Result(val mesh: Mesh, val offsets: FloatArray)

    /** Складывает деформации слоя (см. описание объекта). Никогда не бросает и не теряет деформацию. */
    fun compose(input: Input): Result {
        // 1. своя сетка (сглаженная) или равномерная сетка под Warp/перспективу/группы
        val mesh: Mesh
        val off: FloatArray
        if (input.mesh != null && input.meshOffsets != null && input.meshOffsets.size >= input.mesh.count * 2) {
            val plan = MeshSmoothPlan.of(input.mesh, input.smoothLevels)
            mesh = plan.mesh
            off = plan.apply(input.meshOffsets)
        } else {
            mesh = sharedGrid(if (input.dense != null) maxOf(input.cells, 32) else input.cells)
            off = FloatArray(mesh.count * 2)
        }
        val n = mesh.count
        val tmp = FloatArray(2); val tmp2 = FloatArray(2)
        // 2. Warp слоя (кубический + плотная детализация) в точках сетки
        val cubic = input.cubic?.takeIf { it.size >= 32 }
        if (cubic != null || input.dense != null) {
            for (k in 0 until n) {
                val px = mesh.u(k) + off[k * 2]; val py = mesh.v(k) + off[k * 2 + 1]
                var dx = 0f; var dy = 0f
                if (cubic != null) { warpAt(cubic, px, py, tmp); dx += tmp[0]; dy += tmp[1] }
                input.dense?.let { g -> val d = g.at(px, py); dx += d.first; dy += d.second }
                off[k * 2] += dx; off[k * 2 + 1] += dy
            }
        }
        // 3. перспектива слоя (запекается, только если после неё идут групповые деформеры)
        input.perspective?.takeIf { it.size >= 9 }?.let { h ->
            val sign = perspectiveSign(h)
            for (k in 0 until n) {
                val px = mesh.u(k) + off[k * 2]; val py = mesh.v(k) + off[k * 2 + 1]
                perspective(h, px, py, sign, tmp)
                off[k * 2] = tmp[0] - mesh.u(k); off[k * 2 + 1] = tmp[1] - mesh.v(k)
            }
        }
        // 4. групповые деформеры: единичный квадрат слоя -> холст -> оси группы -> деформация -> холст -> ... -> слой
        val leaf = input.leafWorld
        val leafInv = leaf?.inverse()
        if (input.groups.isNotEmpty() && leaf != null && leafInv != null) {
            for (k in 0 until n) {
                val px = mesh.u(k) + off[k * 2]; val py = mesh.v(k) + off[k * 2 + 1]
                var wx = leaf.applyX(px, py); var wy = leaf.applyY(px, py)
                for (g in input.groups) {
                    val lx = g.inverse.applyX(wx, wy); val ly = g.inverse.applyY(wx, wy)
                    mapGroup(g.deformation, lx, ly, tmp, tmp2)
                    wx = g.world.applyX(tmp[0], tmp[1]); wy = g.world.applyY(tmp[0], tmp[1])
                }
                val ux = leafInv.applyX(wx, wy); val uy = leafInv.applyY(wx, wy)
                if (ux.isFinite() && uy.isFinite()) { off[k * 2] = ux - mesh.u(k); off[k * 2 + 1] = uy - mesh.v(k) }
            }
        }
        for (i in off.indices) if (!off[i].isFinite()) off[i] = 0f
        return Result(mesh, off)
    }
}

/**
 * Спринт 43: кэш готовых сеток по id слоя. Изоляция: запись одного слоя никогда не затирает и не сбрасывает записи
 * других (новая сетка челюсти не трогает глаз и провод). Слой, у которого на кадре ничего не изменилось (статичная
 * сетка, неанимированный деформер, та же поза группы), берёт результат без пересчёта - это основная экономия Play и
 * скраббинга в тяжёлых сценах. Сравнение точное (идентичность неизменяемых объектов + все числа входа), поэтому
 * форма при перемотке назад/вперёд и на Play возвращается ровно. Записи удалённых слоёв вычищаются ([retain]).
 */
class DeformCache(private val capacity: Int = 4096) {
    private class Entry(val refs: Array<Any?>, val sig: FloatArray, val result: DeformStack.Result)
    private val map = LinkedHashMap<Int, Entry>(64, .75f, true)
    var hits = 0L; private set
    var misses = 0L; private set

    fun compose(id: Int, input: DeformStack.Input): DeformStack.Result {
        val refs = input.refs(); val sig = input.signature()
        synchronized(this) {
            val e = map[id]
            if (e != null && e.refs.size == refs.size && e.refs.indices.all { e.refs[it] === refs[it] } && e.sig.contentEquals(sig)) { hits++; return e.result }
            misses++
        }
        val r = DeformStack.compose(input)
        synchronized(this) {
            map[id] = Entry(refs, sig, r)
            while (map.size > capacity) map.remove(map.keys.first())
        }
        return r
    }

    /** Оставляет записи только слоёв [ids] (удалённые слои и выключенные деформеры не держат память). */
    @Synchronized fun retain(ids: Set<Int>) { map.keys.retainAll(ids) }
    @Synchronized fun size(): Int = map.size
    @Synchronized fun clear() { map.clear(); hits = 0; misses = 0 }
}
