package com.vibetoon.domain.anim

import kotlin.math.max

/**
 * Спринт 43: деформер-путь «как Path Deformer в Live2D» для прядей, хвостов, проводов.
 *
 * Привязка: каждая вершина запоминает `t` - долю длины пути (0 - корень, 1 - кончик; за концами < 0 и > 1; от
 * исходной длины, поэтому прядь не растягивается) и `d` -
 * знаковое смещение по нормали (плюс остаток вдоль касательной). При движении точек пути (пинов) кривая
 * (центрипетальный Catmull-Rom через [PathMath]) строится заново, касательные и нормали пересчитываются, и вершина
 * восстанавливается из тех же `t, d`: дуга изгибается плавно, а не поворачивается вокруг одной точки.
 *
 * Влияние = ширина поперёк (геодезическое расстояние до пути, плавный спад к радиусу) x спад вдоль длины: от корня
 * влияние нарастает плавно на [ROOT_SOFT] длины, поэтому корень не «приклеен» и нет залома у головы. Вершины за корнем
 * (t < 0) стоят на месте: первую точку пути можно вынести за пределы сетки (в голову), 3-4 точек достаточно. Расчёт
 * идёт в пикселях с учётом масштаба слоя ([PuppetMath.addTo]), поэтому нормали верны и у растянутого слоя; родительский
 * Warp/трансформация (инерция головы) складывается с путём в SceneBuilder (DeformStack): сначала изгиб пряди, затем
 * деформер головы.
 */
object PathDeformer {
    /** Доля длины пути от корня, на которой влияние плавно растёт от 0 до 1 (мягкий корень). */
    const val ROOT_SOFT = 0.18f
    /** Сколько «рядов» вершин вдоль пути нужно для плавного S-изгиба. */
    const val MIN_ROWS = 6

    class Binding(val t: FloatArray, val d: FloatArray, val u: FloatArray, val across: FloatArray, val along: FloatArray,
                  val restLength: Float, val rootX: Float, val rootY: Float)

    /** Спад влияния вдоль пути: 0 за корнем, плавный рост на [rootSoft] длины, 1 дальше (кончик хлещет целиком). */
    fun alongWeight(t: Float, rootSoft: Float = ROOT_SOFT): Float {
        if (!t.isFinite() || t <= 0f) return 0f
        if (rootSoft <= 0f || t >= rootSoft) return 1f
        val x = t / rootSoft
        return x * x * (3f - 2f * x)
    }

    /**
     * Вес поперёк пути по «сырому» весу [PathMath.bind] (smoothstep от геодезического расстояния): плато 1 до половины
     * радиуса и плавный спад после - края пряди не отстают от середины, а соседние пряди за радиусом не задеты.
     */
    fun acrossWeight(raw: Float): Float {
        if (!raw.isFinite() || raw <= 0f) return 0f
        val x = (raw * 2f).coerceAtMost(1f)
        return x * x * (3f - 2f * x)
    }

    /** Привязывает вершины [q] (пиксели) к исходной кривой [rest]; [radius] - ширина влияния поперёк пути. */
    fun bind(q: FloatArray, tris: IntArray, rest: PathMath.Curve, radius: Float, rootSoft: Float = ROOT_SOFT): Binding {
        val b = PathMath.bind(q, tris, rest, radius)
        val len = max(rest.length, 1e-3f)
        val n = q.size / 2
        val t = FloatArray(n) { b.s[it] / len }
        // ширина: полное влияние до половины радиуса (прядь движется целиком), дальше - плавный спад к краю радиуса
        val across = FloatArray(n) { acrossWeight(b.w[it]) }
        return Binding(t, b.d.copyOf(), b.u.copyOf(), across, FloatArray(n) { alongWeight(t[it], rootSoft) }, len, rest.x[0], rest.y[0])
    }

    /**
     * Новые позиции вершин (пиксели) для текущей кривой [cur]: из тех же t, d с новыми касательными и нормалями.
     * Сдвиг корня пути переносит всю прядь (в пределах ширины влияния), а изгиб нарастает от корня плавно: так
     * корень не «приклеен» и не ломается, а прядь целиком следует за перенесённой первой точкой.
     */
    fun deform(q: FloatArray, b: Binding, cur: PathMath.Curve): FloatArray {
        val out = q.copyOf()
        val tmp = FloatArray(4)
        // t - доля ИСХОДНОЙ длины: прядь не растягивается, когда кончик тянут дальше (длина волоса сохраняется)
        val len = b.restLength
        val rx = (cur.x[0] - b.rootX).takeIf { it.isFinite() } ?: 0f
        val ry = (cur.y[0] - b.rootY).takeIf { it.isFinite() } ?: 0f
        for (i in 0 until q.size / 2) {
            val a = b.across[i]
            if (a <= 1e-4f) continue
            val w = b.along[i]
            // перенос корнем (жёсткий, по ширине влияния)
            val bx = q[i * 2] + rx * a
            val by = q[i * 2 + 1] + ry * a
            if (w <= 1e-4f) { out[i * 2] = bx; out[i * 2 + 1] = by; continue }
            PathMath.at(cur, b.t[i] * len, tmp)
            // нормаль = (-ty, tx): та же, что при привязке
            val px = tmp[0] + tmp[2] * b.u[i] - tmp[3] * b.d[i]
            val py = tmp[1] + tmp[3] * b.u[i] + tmp[2] * b.d[i]
            if (!px.isFinite() || !py.isFinite()) { out[i * 2] = bx; out[i * 2 + 1] = by; continue }
            // жёсткий перенос -> полный изгиб по длине, затем спад по ширине
            val al = b.along[i]
            out[i * 2] = q[i * 2] + a * (rx + (px - q[i * 2] - rx) * al)
            out[i * 2 + 1] = q[i * 2 + 1] + a * (ry + (py - q[i * 2 + 1] - ry) * al)
        }
        return out
    }

    /**
     * Совет по плотности сетки: сколько «рядов» вдоль пути (из [rows]) содержат вершины под влиянием пути. Если меньше
     * [MIN_ROWS] - изгиб будет ломаным, нужна более плотная сетка (кнопка «Сгенерировать» с большей плотностью).
     */
    fun coveredRows(b: Binding, rows: Int = 12): Int {
        val seen = BooleanArray(rows)
        for (i in b.t.indices) {
            if (b.across[i] <= 0.05f) continue
            val t = b.t[i]
            if (t < 0f || t > 1f) continue
            seen[(t * rows).toInt().coerceIn(0, rows - 1)] = true
        }
        return seen.count { it }
    }

    fun needsDenserMesh(b: Binding): Boolean = coveredRows(b) < MIN_ROWS
}
