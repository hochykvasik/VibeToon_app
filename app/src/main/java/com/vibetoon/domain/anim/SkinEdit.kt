package com.vibetoon.domain.anim

import com.vibetoon.domain.model.Affine2D

/**
 * Спринт 49: правка сетки слоя, привязанного к костям (рукав на кости руки).
 *
 * Скининг вершины k в единицах квадрата слоя - аффинное преобразование M_k = rest⁻¹ · B_k · rest, где rest - квадрат
 * слоя в позе привязки, B_k - смешанная дельта костей по весам вершины (веса берутся по позе привязки, поэтому M_k не
 * зависит от собственного смещения вершины). Итоговая точка = M_k · (u + смещение). Значит, чтобы вершина встала под
 * палец, собственное смещение считается от точки, возвращённой через M_k⁻¹: поза костей и локальная правка сетки
 * складываются, а не перетирают друг друга (и на холсте, и в экспорте - одна и та же формула сцены).
 *
 * Матрицы хранятся по 6 float на вершину (a, b, c, d, tx, ty) (SceneData.skinAffines), без объектов на каждый кадр.
 */
object SkinEdit {
    const val STRIDE = 6

    /** M_k для смешанной дельты [blend] и квадрата позы привязки [rest] (null - вырожденный квадрат). */
    fun local(rest: Affine2D, blend: Affine2D): Affine2D? = rest.inverse()?.let { it * blend * rest }

    fun write(out: FloatArray, k: Int, m: Affine2D) {
        val o = k * STRIDE
        out[o] = m.a; out[o + 1] = m.b; out[o + 2] = m.c; out[o + 3] = m.d; out[o + 4] = m.tx; out[o + 5] = m.ty
    }

    fun read(arr: FloatArray, k: Int): Affine2D? {
        val o = k * STRIDE
        if (k < 0 || o + 5 >= arr.size) return null
        return Affine2D(arr[o], arr[o + 1], arr[o + 2], arr[o + 3], arr[o + 4], arr[o + 5])
    }

    /** Точка до скининга, которая после M_k окажется в [u], [v] (единицы квадрата). null - матрицы нет или вырождена. */
    fun unskin(arr: FloatArray?, k: Int, u: Float, v: Float): Pair<Float, Float>? {
        val inv = read(arr ?: return null, k)?.inverse() ?: return null
        val x = inv.applyX(u, v); val y = inv.applyY(u, v)
        if (!x.isFinite() || !y.isFinite()) return null
        return x to y
    }

    /** Итоговая (показанная) точка вершины: M_k · (u, v). Без матрицы - точка как есть. */
    fun skin(arr: FloatArray?, k: Int, u: Float, v: Float): Pair<Float, Float> {
        val m = arr?.let { read(it, k) } ?: return u to v
        return m.applyX(u, v) to m.applyY(u, v)
    }

    /**
     * Вершина, по которой кисть переводит палец в позу до скининга: ближайшая к пальцу ПОКАЗАННАЯ вершина
     * ([shownU]/[shownV] - u + итоговое смещение). Матрица скининга меняется по сетке плавно, поэтому локально мазок
     * и радиус кисти переводятся одной матрицей; -1 - сетки нет.
     */
    fun nearest(count: Int, shown: (Int) -> Pair<Float, Float>, u: Float, v: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (k in 0 until count) {
            val (x, y) = shown(k)
            val d = (x - u) * (x - u) + (y - v) * (y - v)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /**
     * Собственное смещение вершины [k] (u0, v0 - её исходная точка), при котором после скининга она окажется
     * под пальцем ([u], [v] - единицы квадрата позы привязки). Без матрицы - как у слоя без костей.
     */
    fun grabOffset(arr: FloatArray?, k: Int, u0: Float, v0: Float, u: Float, v: Float): Pair<Float, Float> {
        val (pu, pv) = unskin(arr, k, u, v) ?: (u to v)
        return (pu - u0) to (pv - v0)
    }
}
