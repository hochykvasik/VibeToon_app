package com.vibetoon.domain.raster

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Sprint 33: перенос выделенных пикселей в рамку [TransformBox] (масштаб, поворот, наклон, искажение по 4 углам).
 * Обратная гомография + билинейная выборка только по выделенным пикселям (premultiplied RGBA, source-over).
 * Как и FreeSelectionTransform: если рамка выходит за слой, операция отклоняется целиком (без обрезки и без смены геометрии слоя).
 */
object SelectionWarp {
    fun prepare(media: MediaItem, rgba: ByteArray, offset: Int, width: Int, height: Int, mask: ByteArray,
                box: TransformBox.Box, budget: Long): ExpandedLayerSnapshot.Change? {
        val n = width.toLong() * height
        if (media.kind != MediaKind.IMAGE || media.vector || media.background || media.cells.isNotEmpty() ||
            width !in 1..2048 || height !in 1..2048 || n > ExpandedSelectionMove.MAX_PIXELS ||
            mask.size.toLong() != n || offset < 0 || offset.toLong() + n * 4 > rgba.size || budget < n * 22 + 8L * 1024 * 1024) return null
        if (!TransformBox.valid(box)) return null
        val src = box.src
        if (src.x < 0 || src.y < 0 || src.x + src.w > width || src.y + src.h > height) return null
        val h = TransformBox.homography(box) ?: return null
        val inv = TransformBox.invert(h) ?: return null
        val xs = box.corners.map { it.x }; val ys = box.corners.map { it.y }
        val left = floor(xs.min()); val right = ceil(xs.max()); val top = floor(ys.min()); val bottom = ceil(ys.max())
        if (left < 0 || top < 0 || right > width || bottom > height || left >= right || top >= bottom) return null
        val before = rgba.copyOfRange(offset, offset + n.toInt() * 4)
        val after = before.copyOf()
        val outMask = ByteArray(mask.size)
        for (i in mask.indices) if (mask[i].toInt() != 0) for (c in 0..3) after[i * 4 + c] = 0
        fun sel(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && mask[y * width + x].toInt() != 0
        val acc = DoubleArray(4)
        var count = 0
        for (y in top.toInt() until bottom.toInt()) for (x in left.toInt() until right.toInt()) {
            val X = x + .5; val Y = y + .5
            val w = inv[6] * X + inv[7] * Y + inv[8]
            if (w <= 1e-12 || !w.isFinite()) continue
            val u = (inv[0] * X + inv[1] * Y + inv[2]) / w
            val v = (inv[3] * X + inv[4] * Y + inv[5]) / w
            if (u < 0 || u > 1 || v < 0 || v > 1) continue
            // центр исходного пикселя -> билинейные соседи
            val sx = src.x + u * src.w - .5; val sy = src.y + v * src.h - .5
            val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
            val fx = sx - x0; val fy = sy - y0
            var cover = 0.0
            acc.fill(0.0)
            for (j in 0..1) for (i in 0..1) {
                val px = x0 + i; val py = y0 + j
                if (!sel(px, py)) continue
                val wt = (if (i == 0) 1 - fx else fx) * (if (j == 0) 1 - fy else fy)
                if (wt <= 0) continue
                cover += wt
                val k = (py * width + px) * 4
                for (c in 0..3) acc[c] += wt * (before[k + c].toInt() and 255)
            }
            if (cover < .5) continue
            val t = y * width + x
            outMask[t] = 1; count++
            val sa = (acc[3] / cover).toInt().coerceIn(0, 255); val inv255 = 255 - sa
            for (c in 0..3) {
                val s = (acc[c] / cover).toInt().coerceIn(0, 255)
                after[t * 4 + c] = minOf(255, s + (after[t * 4 + c].toInt() and 255) * inv255 / 255).toByte()
            }
        }
        if (count == 0) return null
        return ExpandedLayerSnapshot.Change(ExpandedLayerSnapshot.State(media, width, height, before),
            ExpandedLayerSnapshot.State(media, width, height, after), outMask)
    }
}
