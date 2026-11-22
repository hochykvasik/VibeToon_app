package com.vibetoon.domain.raster

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind

/** Mirror only selected pixels in their bounding box; RGBA premultiplied source-over. */
object SelectionMirror {
    enum class Operation { FLIP_X, FLIP_Y, ROTATE_CW, ROTATE_CCW, SCALE_HALF, SCALE_DOUBLE }
    fun prepare(media: MediaItem, rgba: ByteArray, offset: Int, width: Int, height: Int,
                mask: ByteArray, horizontal: Boolean, budget: Long): ExpandedLayerSnapshot.Change? =
        prepare(media, rgba, offset, width, height, mask, if (horizontal) Operation.FLIP_X else Operation.FLIP_Y, budget)

    fun prepare(media: MediaItem, rgba: ByteArray, offset: Int, width: Int, height: Int,
                mask: ByteArray, operation: Operation, budget: Long): ExpandedLayerSnapshot.Change? {
        val n = width.toLong() * height
        if (media.kind != MediaKind.IMAGE || media.vector || media.background || media.cells.isNotEmpty() ||
            width <= 0 || height <= 0 || width > 2048 || height > 2048 || n > ExpandedSelectionMove.MAX_PIXELS ||
            mask.size.toLong() != n || offset < 0 || offset.toLong() + n * 4 > rgba.size ||
            budget < n * 22 + 8L * 1024 * 1024) return null
        val b = RasterMath.bounds(mask, width, height) ?: return null
        val rotated = operation == Operation.ROTATE_CW || operation == Operation.ROTATE_CCW
        // Keep the centre as close as possible on the integer pixel grid; odd parity changes can shift half a pixel.
        val scaling = operation == Operation.SCALE_HALF || operation == Operation.SCALE_DOUBLE
        val outW = when (operation) { Operation.ROTATE_CW, Operation.ROTATE_CCW -> b.h; Operation.SCALE_HALF -> (b.w + 1) / 2; Operation.SCALE_DOUBLE -> b.w * 2; else -> b.w }
        val outH = when (operation) { Operation.ROTATE_CW, Operation.ROTATE_CCW -> b.w; Operation.SCALE_HALF -> (b.h + 1) / 2; Operation.SCALE_DOUBLE -> b.h * 2; else -> b.h }
        val left = if (rotated || scaling) Math.floorDiv(2 * b.x + b.w - outW, 2) else b.x
        val top = if (rotated || scaling) Math.floorDiv(2 * b.y + b.h - outH, 2) else b.y
        if (left < 0 || top < 0 || left.toLong() + outW > width || top.toLong() + outH > height) return null
        val before = rgba.copyOfRange(offset, offset + n.toInt() * 4)
        val after = before.copyOf()
        val transformedMask = ByteArray(mask.size)
        for (y in b.y until b.y + b.h) for (x in b.x until b.x + b.w) {
            val k = y * width + x
            if (mask[k].toInt() != 0) for (c in 0..3) after[k * 4 + c] = 0
        }
        // Inverse sampling covers every output pixel, including enlargement, without holes.
        for (v in 0 until outH) for (u in 0 until outW) {
            val (sx, sy) = when (operation) {
                Operation.FLIP_X -> (b.w - 1 - u) to v
                Operation.FLIP_Y -> u to (b.h - 1 - v)
                Operation.ROTATE_CW -> v to (b.h - 1 - u)
                Operation.ROTATE_CCW -> (b.w - 1 - v) to u
                Operation.SCALE_HALF, Operation.SCALE_DOUBLE ->
                    (((u + .5f) * b.w / outW).toInt().coerceAtMost(b.w - 1)) to (((v + .5f) * b.h / outH).toInt().coerceAtMost(b.h - 1))
            }
            val k = (b.y + sy) * width + b.x + sx
            if (mask[k].toInt() == 0) continue
            val d = (top + v) * width + left + u; transformedMask[d] = 1
            val a = before[k * 4 + 3].toInt() and 255; val inv = 255 - a
            for (c in 0..3) after[d * 4 + c] = minOf(255, (before[k * 4 + c].toInt() and 255) + (after[d * 4 + c].toInt() and 255) * inv / 255).toByte()
        }
        return ExpandedLayerSnapshot.Change(ExpandedLayerSnapshot.State(media, width, height, before),
            ExpandedLayerSnapshot.State(media, width, height, after), transformedMask)
    }
}
