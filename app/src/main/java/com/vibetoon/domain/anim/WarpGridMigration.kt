package com.vibetoon.domain.anim

/** Подготовка миграции отдельно от проекта и GL. Не меняет старые id и ещё не подключена к UI.
 * У всех существующих дорожек должны совпадать кадры и кривые: иначе смешивание ключей
 * меняет значения между кадрами, даже если в самих ключах пересчёт выглядит корректным. */
object WarpGridMigration {
    data class PointKeys(val keys: List<Keyframe>)
    data class Result(val size: Int, val base: FloatArray, val points: List<PointKeys>)

    data class RectResult(val width: Int, val height: Int, val base: FloatArray, val points: List<PointKeys>)

    fun convert(base: FloatArray, points: List<PointKeys>, size: Int, target: Int): Result? {
        val result = convertRect(base, points, size, size, target, target) ?: return null
        return Result(result.width, result.base, result.points)
    }

    /** Independent X/Y degree changes, with synchronized tracks and no approximation on reduction. */
    fun convertRect(base: FloatArray, points: List<PointKeys>, width: Int, height: Int, targetWidth: Int, targetHeight: Int): RectResult? {
        if (width !in WarpGridMath.MIN_POINTS..WarpGridMath.MAX_POINTS || height !in WarpGridMath.MIN_POINTS..WarpGridMath.MAX_POINTS ||
            targetWidth !in WarpGridMath.MIN_POINTS..WarpGridMath.MAX_POINTS || targetHeight !in WarpGridMath.MIN_POINTS..WarpGridMath.MAX_POINTS ||
            points.size != width * height || base.size != width * height * 2 || base.any { !it.isFinite() }) return null
        val animated = points.map { it.keys }.filter { it.isNotEmpty() }
        val reference = animated.firstOrNull().orEmpty()
        if (animated.any { keys -> keys.size != reference.size || keys.indices.any { i ->
                    val a = keys[i]; val b = reference[i]
                    a.frame != b.frame || a.easing != b.easing || a.curve != b.curve
                } } || reference.zipWithNext().any { (a, b) -> a.frame >= b.frame } ||
            animated.flatten().any { !it.dx.isFinite() || !it.dy.isFinite() || it.frame < 0 ||
                it.scaleX != 1f || it.scaleY != 1f || it.rotation != 0f || it.opacity != 1f ||
                it.curve?.any { x -> !x.isFinite() } == true }) return null
        fun convertGrid(values: FloatArray): FloatArray? =
            RectWarpGridMath.convert(values, width, height, targetWidth, targetHeight)
        val convertedBase = convertGrid(base) ?: return null
        val output = List(targetWidth * targetHeight) { mutableListOf<Keyframe>() }
        for (k in reference.indices) {
            val grid = FloatArray(width * height * 2)
            for (i in points.indices) points[i].keys.getOrNull(k)?.let { key ->
                grid[i * 2] = key.dx; grid[i * 2 + 1] = key.dy
            }
            val converted = convertGrid(grid) ?: return null
            val prototype = reference[k]
            for (i in output.indices) output[i].add(prototype.copy(dx = converted[i * 2], dy = converted[i * 2 + 1]))
        }
        return RectResult(targetWidth, targetHeight, convertedBase, output.map { PointKeys(it.toList()) })
    }
}
