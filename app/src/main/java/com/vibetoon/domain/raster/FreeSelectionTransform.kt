package com.vibetoon.domain.raster

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import kotlin.math.*

/** Similarity transform in source texture pixels, from the initial two contacts to their final positions. */
object FreeSelectionTransform {
    data class Point(val x: Float, val y: Float)
    data class Gesture(val a: Point, val b: Point, val endA: Point, val endB: Point)
    data class Matrix(val a: Double, val b: Double, val tx: Double, val ty: Double) {
        fun x(x: Double, y: Double) = a * x - b * y + tx
        fun y(x: Double, y: Double) = b * x + a * y + ty
    }
    fun matrix(g: Gesture): Matrix? {
        val all = listOf(g.a.x, g.a.y, g.b.x, g.b.y, g.endA.x, g.endA.y, g.endB.x, g.endB.y)
        if (all.any { !it.isFinite() }) return null
        val x = (g.b.x - g.a.x).toDouble(); val y = (g.b.y - g.a.y).toDouble()
        val u = (g.endB.x - g.endA.x).toDouble(); val v = (g.endB.y - g.endA.y).toDouble()
        val len2 = x*x + y*y
        if (len2 < 16.0) return null
        val a = (x*u + y*v) / len2; val b = (x*v - y*u) / len2
        val scale = hypot(a, b)
        if (!scale.isFinite() || scale !in .25..4.0) return null
        return Matrix(a, b, g.endA.x - a*g.a.x + b*g.a.y, g.endA.y - b*g.a.x - a*g.a.y)
    }
    fun prepare(media: MediaItem, rgba: ByteArray, offset: Int, width: Int, height: Int, mask: ByteArray,
                gesture: Gesture, budget: Long): ExpandedLayerSnapshot.Change? {
        val n = width.toLong() * height
        if (media.kind != MediaKind.IMAGE || media.vector || media.background || media.cells.isNotEmpty() ||
            width !in 1..2048 || height !in 1..2048 || n > ExpandedSelectionMove.MAX_PIXELS ||
            mask.size.toLong() != n || offset < 0 || offset.toLong() + n * 4 > rgba.size || budget < n * 22 + 8L * 1024 * 1024) return null
        val t = matrix(gesture) ?: return null
        val box = RasterMath.bounds(mask, width, height) ?: return null
        val corners = listOf(box.x.toDouble() to box.y.toDouble(), (box.x + box.w).toDouble() to box.y.toDouble(),
            box.x.toDouble() to (box.y + box.h).toDouble(), (box.x + box.w).toDouble() to (box.y + box.h).toDouble())
        val xs = corners.map { (x, y) -> t.x(x, y) }; val ys = corners.map { (x, y) -> t.y(x, y) }
        // Reject the entire operation instead of clipping or changing layer geometry.
        val left = floor(xs.minOrNull()!!); val right = ceil(xs.maxOrNull()!!)
        val top = floor(ys.minOrNull()!!); val bottom = ceil(ys.maxOrNull()!!)
        if (left < 0 || top < 0 || right > width || bottom > height || left >= right || top >= bottom) return null
        val before = rgba.copyOfRange(offset, offset + n.toInt() * 4); val after = before.copyOf(); val outMask = ByteArray(mask.size)
        for (i in mask.indices) if (mask[i].toInt() != 0) for (c in 0..3) after[i * 4 + c] = 0
        val d = t.a*t.a + t.b*t.b
        var count = 0
        for (y in top.toInt() until bottom.toInt()) for (x in left.toInt() until right.toInt()) {
            val dx = x + .5 - t.tx; val dy = y + .5 - t.ty
            val sx = floor((t.a*dx + t.b*dy) / d).toInt(); val sy = floor((-t.b*dx + t.a*dy) / d).toInt()
            if (sx !in 0 until width || sy !in 0 until height) continue
            val source = sy * width + sx
            if (mask[source].toInt() == 0) continue
            val target = y * width + x; outMask[target] = 1; count++
            val alpha = before[source * 4 + 3].toInt() and 255; val inv = 255 - alpha
            for (c in 0..3) after[target * 4 + c] = minOf(255, (before[source * 4 + c].toInt() and 255) + (after[target * 4 + c].toInt() and 255) * inv / 255).toByte()
        }
        if (count == 0) return null
        return ExpandedLayerSnapshot.Change(ExpandedLayerSnapshot.State(media, width, height, before),
            ExpandedLayerSnapshot.State(media, width, height, after), outMask)
    }
}
