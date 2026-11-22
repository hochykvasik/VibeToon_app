package com.vibetoon.domain.raster

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind

/** Подготовленные снимки для будущей атомарной замены текстуры. Сам по себе слой и GL не меняет. */
object ExpandedLayerSnapshot {
    data class State(val media: MediaItem, val width: Int, val height: Int, val rgba: ByteArray)
    data class Change(val before: State, val after: State, val selection: ByteArray)

    /** Только геометрия независимого растрового слоя без трансформаций; вызывающий обязан проверить дерево и эффекты. */
    fun prepare(media: MediaItem, rgba: ByteArray, offset: Int, width: Int, height: Int, mask: ByteArray, dx: Int, dy: Int, cpuBudgetBytes: Long = ExpandedSelectionMove.DEFAULT_CPU_BUDGET): Change? {
        if (media.kind != MediaKind.IMAGE || media.vector || media.background || media.cells.isNotEmpty() ||
            !media.x.isFinite() || !media.y.isFinite() || !media.w.isFinite() || !media.h.isFinite() ||
            media.w <= 0f || media.h <= 0f) return null
        val result = ExpandedSelectionMove.prepare(rgba, offset, width, height, mask, dx, dy, cpuBudgetBytes) ?: return null
        val sx = media.w / width
        val sy = media.h / height
        val x = media.x + result.left * sx
        val y = media.y + result.top * sy
        val w = result.width * sx
        val h = result.height * sy
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return null
        val updated = media.copy(srcWidth = result.width, srcHeight = result.height, x = x, y = y, w = w, h = h)
        return Change(State(media, width, height, result.before),
            State(updated, result.width, result.height, result.after), result.mask)
    }
    /** Минимальная область после переноса: сохраняет все невыделенные RGBA и всю перемещённую маску. */
    fun prepareTrimmed(media: MediaItem, rgba: ByteArray, offset: Int, width: Int, height: Int,
                       mask: ByteArray, dx: Int, dy: Int, cpuBudgetBytes: Long): Change? {
        val pixels = width.toLong() * height
        if (media.kind != MediaKind.IMAGE || media.vector || media.background || media.cells.isNotEmpty() ||
            !media.x.isFinite() || !media.y.isFinite() || !media.w.isFinite() || !media.h.isFinite() || media.w <= 0f || media.h <= 0f ||
            width <= 0 || height <= 0 || pixels > ExpandedSelectionMove.MAX_PIXELS || offset < 0 ||
            pixels > mask.size || offset.toLong() + pixels * 4 > rgba.size.toLong() || cpuBudgetBytes < 0 || (dx == 0 && dy == 0)) return null
        var left = Long.MAX_VALUE; var top = Long.MAX_VALUE; var right = Long.MIN_VALUE; var bottom = Long.MIN_VALUE
        var hasSelection = false
        for (y in 0 until height) for (x in 0 until width) {
            val k = y * width + x; val p = offset + k * 4
            val selected = mask[k].toInt() != 0
            if (selected) hasSelection = true
            val keep = !selected && (rgba[p].toInt() != 0 || rgba[p + 1].toInt() != 0 || rgba[p + 2].toInt() != 0 || rgba[p + 3].toInt() != 0)
            if (selected || keep) {
                val nx = x.toLong() + if (selected) dx.toLong() else 0L
                val ny = y.toLong() + if (selected) dy.toLong() else 0L
                left = minOf(left, nx); top = minOf(top, ny); right = maxOf(right, nx); bottom = maxOf(bottom, ny)
            }
        }
        if (!hasSelection) return null
        val nw = right - left + 1; val nh = bottom - top + 1
        if (nw <= 0 || nh <= 0 || nw > ExpandedSelectionMove.MAX_SIDE || nh > ExpandedSelectionMove.MAX_SIDE || nw * nh > ExpandedSelectionMove.MAX_PIXELS) return null
        // GL-снимок и полный Undo, результат, маска, копия JNI и резерв. GPU проверяется при GL-замене.
        val needed = pixels * 9L + nw * nh * 10L + 8L * 1024 * 1024
        if (needed > cpuBudgetBytes) return null
        val outW = nw.toInt(); val outH = nh.toInt()
        val after = ByteArray(outW * outH * 4)
        val selection = ByteArray(outW * outH)
        // Сначала копируем невыделенные пиксели, включая RGB при альфе 0.
        for (y in 0 until height) for (x in 0 until width) {
            val k = y * width + x; val p = offset + k * 4
            if (mask[k].toInt() == 0 && (rgba[p].toInt() != 0 || rgba[p + 1].toInt() != 0 || rgba[p + 2].toInt() != 0 || rgba[p + 3].toInt() != 0)) {
                val d = (((y.toLong() - top).toInt() * outW) + (x.toLong() - left).toInt()) * 4
                System.arraycopy(rgba, p, after, d, 4)
            }
        }
        // Затем накладываем выделенные пиксели premultiplied поверх остатка, как RasterMath.moveResult.
        for (y in 0 until height) for (x in 0 until width) {
            val k = y * width + x
            if (mask[k].toInt() == 0) continue
            val nx = (x.toLong() + dx - left).toInt(); val ny = (y.toLong() + dy - top).toInt()
            val index = ny * outW + nx; selection[index] = 1
            val p = offset + k * 4; val d = index * 4
            val alpha = rgba[p + 3].toInt() and 255; val inv = 255 - alpha
            for (c in 0..3) after[d + c] = minOf(255, (rgba[p + c].toInt() and 255) + (after[d + c].toInt() and 255) * inv / 255).toByte()
        }
        val sx = media.w / width; val sy = media.h / height
        val x = media.x + left.toFloat() * sx; val y = media.y + top.toFloat() * sy
        val w = outW * sx; val h = outH * sy
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return null
        val updated = media.copy(srcWidth = outW, srcHeight = outH, x = x, y = y, w = w, h = h)
        return Change(State(media, width, height, rgba.copyOfRange(offset, offset + pixels.toInt() * 4)), State(updated, outW, outH, after), selection)
    }

}
