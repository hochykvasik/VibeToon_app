package com.vibetoon.domain.render

import com.vibetoon.domain.model.Affine2D

/**
 * Пакет 22: Show Symbol Context / Symbol Context Opacity. При редактировании символа слои главной сцены видны
 * полупрозрачно вокруг него и выровнены по экземпляру, через который символ открыт: если экземпляр сдвинут,
 * повёрнут или уменьшен, главная сцена в режиме редактирования стоит так, как содержимое символа выглядит
 * относительно неё на главной сцене.
 *
 * Пространства: «форма» - координаты снимка символа (участники без общих с экземплярами предков, без камеры);
 * форма - снимок всего холста (x, y, w, h) = (0, 0, ширина, высота), экземпляр растягивает его на свой единичный квадрат
 * матрицей «единичный квадрат -> холст» [instanceUnit] (рамку экземпляра и его трансформации она уже содержит);
 * при редактировании форма стоит на холсте матрицей [formToEdit] (мир самого глубокого общего предка или вид камеры).
 */
object SymbolContext {
    const val DEFAULT_OPACITY_PERCENT = 30
    const val MIN_OPACITY_PERCENT = 5

    fun opacity(percent: Int): Float = percent.coerceIn(MIN_OPACITY_PERCENT, 100) / 100f

    /** Холст главной сцены -> холст редактирования символа; null - экземпляр вырожден (нулевой масштаб). */
    fun mainToEdit(formToEdit: Affine2D, instanceUnit: Affine2D, x: Float, y: Float, w: Float, h: Float): Affine2D? {
        if (!(w > 0f && h > 0f)) return null
        val formToMain = instanceUnit * (Affine2D.translate(x, y) * Affine2D.scale(w, h)).inverse()!!
        val inv = formToMain.inverse() ?: return null
        val r = formToEdit * inv
        return if (listOf(r.a, r.b, r.c, r.d, r.tx, r.ty).all { it.isFinite() }) r else null
    }

    /** Матрица «единичный квадрат -> холст» слоя [id] в сцене (op слоя), null - слоя нет. */
    fun layerMatrix(sc: SceneData, id: Int): Affine2D? {
        val n = sc.ops.size / 4
        for (i in 0 until n) {
            if (sc.ops[i * 4] != SceneBuilder.OP_LAYER || sc.ops[i * 4 + 1] != id) continue
            if (sc.ops[i * 4 + 3] and SceneBuilder.FLAG_MASK_SRC != 0) continue   // маска «Оси растяжения» - не сам слой
            val p = i * SceneData.PARAM_STRIDE
            return Affine2D(sc.params[p], sc.params[p + 1], sc.params[p + 2], sc.params[p + 3], sc.params[p + 4], sc.params[p + 5])
        }
        return null
    }

    /** Самый глубокий из общих предков [shared] (у кого больше всего предков в этом же наборе); null - набор пуст. */
    fun deepest(shared: Set<Int>, parentOf: (Int) -> Int?): Int? = shared.maxByOrNull { id ->
        var depth = 0; var cur = parentOf(id); val seen = HashSet<Int>()
        while (cur != null && cur >= 0 && seen.add(cur)) { if (cur in shared) depth++; cur = parentOf(cur) }
        depth
    }

    /**
     * Слои контекста [ids] переводятся матрицей [mainToEdit]; их рамки и мировые матрицы убираются из сцены -
     * контекст не выбирается касанием и не участвует в жестах. Остальные данные сцены не меняются.
     */
    fun apply(sc: SceneData, ids: Set<Int>, mainToEdit: Affine2D): SceneData {
        if (ids.isEmpty()) return sc
        val params = sc.params.copyOf()
        val n = sc.ops.size / 4
        for (i in 0 until n) {
            if (sc.ops[i * 4] != SceneBuilder.OP_LAYER || sc.ops[i * 4 + 1] !in ids) continue
            val p = i * SceneData.PARAM_STRIDE
            val m = mainToEdit * Affine2D(params[p], params[p + 1], params[p + 2], params[p + 3], params[p + 4], params[p + 5])
            params[p] = m.a; params[p + 1] = m.b; params[p + 2] = m.c; params[p + 3] = m.d; params[p + 4] = m.tx; params[p + 5] = m.ty
        }
        return SceneData(
            sc.canvasWidth, sc.canvasHeight, sc.ops, params, sc.textureIds,
            sc.selectionQuads - ids, sc.parentWorlds - ids, sc.boneDeltas,
            sc.warpIds, sc.warpData, sc.meshIds, sc.meshCounts, sc.meshVerts, sc.meshTris,
            sc.perspIds, sc.perspData, sc.turbIds, sc.turbData, sc.fxIds, sc.fxCounts, sc.fxData,
            sc.groupLocalBounds, sc.groupWorlds
        ).let { sc.copyExtrasTo(it) }.also { it.textureQuads = sc.textureQuads - ids }
    }
}
