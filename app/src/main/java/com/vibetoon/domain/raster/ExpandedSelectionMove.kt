package com.vibetoon.domain.raster

/** Подготовка расширенного буфера без изменения слоя или GL. RGBA premultiplied, строки сверху вниз. */
object ExpandedSelectionMove {
    const val MAX_PIXELS = 4_194_304
    // Обычная загрузка PNG уменьшает сторону больше 2048: нельзя подготовить снимок,
    // который потеряет разрешение после повторного открытия проекта.
    const val MAX_SIDE = 2048

    // Верхняя граница оценки рабочих массивов; реальная доступная память проверяется вызывающим.
    const val DEFAULT_CPU_BUDGET = 128L * 1024 * 1024
    private const val CPU_RESERVE = 8L * 1024 * 1024
    data class Plan(val width: Int, val height: Int, val left: Int, val top: Int, val estimatedCpuBytes: Long)

    /** Не выделяет пиксельные буферы. Бюджет — доступный запас CPU, не размер всей кучи. */
    fun plan(width: Int, height: Int, selection: ByteArray, dx: Int, dy: Int, cpuBudgetBytes: Long = DEFAULT_CPU_BUDGET): Plan? {
        val pixels = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || pixels > MAX_PIXELS || pixels > selection.size.toLong() ||
            (dx == 0 && dy == 0) || cpuBudgetBytes < 0) return null
        val b = RasterMath.bounds(selection, width, height) ?: return null
        val left = minOf(0L, b.x.toLong() + dx)
        val top = minOf(0L, b.y.toLong() + dy)
        val right = maxOf(width.toLong(), b.x.toLong() + b.w + dx)
        val bottom = maxOf(height.toLong(), b.y.toLong() + b.h + dy)
        val w = right - left; val h = bottom - top
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || w * h > MAX_PIXELS) return null
        // До/после, маски, область патча, временная копия JNI и запас на вспомогательные объекты.
        val estimated = pixels * 9L + w * h * 22L + CPU_RESERVE
        if (estimated > cpuBudgetBytes) return null
        return Plan(w.toInt(), h.toInt(), left.toInt(), top.toInt(), estimated)
    }

    data class Result(
        val width: Int, val height: Int, val left: Int, val top: Int,
        val before: ByteArray, val after: ByteArray, val mask: ByteArray
    )

    /** null означает неподдерживаемые размеры, неверные данные, пустое выделение или нулевой сдвиг. */
    fun prepare(rgba: ByteArray, offset: Int, width: Int, height: Int, selection: ByteArray, dx: Int, dy: Int, cpuBudgetBytes: Long = DEFAULT_CPU_BUDGET): Result? {
        val pixels = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || pixels > MAX_PIXELS || offset < 0 ||
            pixels > selection.size.toLong() || offset.toLong() + pixels * 4 > rgba.size.toLong() ||
            (dx == 0 && dy == 0)) return null
        val plan = plan(width, height, selection, dx, dy, cpuBudgetBytes) ?: return null
        val nw = plan.width; val nh = plan.height
        val ox = -plan.left; val oy = -plan.top
        val padded = ByteArray(nw * nh * 4)
        val oldMask = ByteArray(nw * nh)
        for (y in 0 until height) {
            System.arraycopy(rgba, offset + y * width * 4, padded, ((y + oy) * nw + ox) * 4, width * 4)
            System.arraycopy(selection, y * width, oldMask, (y + oy) * nw + ox, width)
        }
        val moved = RasterMath.moveResult(padded, 0, nw, nh, oldMask, dx, dy) ?: return null
        val after = padded.copyOf()
        for (y in 0 until moved.rect.h) {
            System.arraycopy(moved.after, y * moved.rect.w * 4, after,
                ((moved.rect.y + y) * nw + moved.rect.x) * 4, moved.rect.w * 4)
        }
        val shifted = ByteArray(nw * nh)
        for (y in 0 until height) for (x in 0 until width) {
            if (selection[y * width + x].toInt() != 0) shifted[(y + oy + dy) * nw + x + ox + dx] = 1
        }
        return Result(nw, nh, plan.left, plan.top, rgba.copyOfRange(offset, offset + pixels.toInt() * 4), after, shifted)
    }
}
