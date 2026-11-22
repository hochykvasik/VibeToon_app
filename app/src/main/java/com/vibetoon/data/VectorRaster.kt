package com.vibetoon.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.vibetoon.domain.paint.ShapeKind
import com.vibetoon.domain.paint.ShapeMath
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorShape

/** Растеризация векторного слоя: примитивы (координаты в px холста) рисуются в прозрачный Bitmap слоя [bw] x [bh]. */
object VectorRaster {
    fun render(shapes: List<VectorShape>, bw: Int, bh: Int, canvasW: Int, canvasH: Int): Bitmap {
        val bmp = Bitmap.createBitmap(bw.coerceAtLeast(1), bh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val sx = bw.toFloat() / canvasW.coerceAtLeast(1)
        val sy = bh.toFloat() / canvasH.coerceAtLeast(1)
        cv.scale(sx, sy)
        for (s in shapes) drawShape(cv, s)
        return bmp
    }

    private fun drawShape(cv: Canvas, s: VectorShape) {
        val fillPaint = if (s.fill != 0L) Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = s.fill.toInt() } else null
        val strokePaint = if (s.width > 0f && (s.stroke ushr 24) != 0L) Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = s.stroke.toInt(); strokeWidth = s.width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        } else null
        fun draw(path: Path) { fillPaint?.let { cv.drawPath(path, it) }; strokePaint?.let { cv.drawPath(path, it) } }
        val p = s.pts
        when (s.kind) {
            VecKind.PATH -> draw(smooth(p))
            VecKind.POLY -> strokePaint?.let { cv.drawPath(poly(p, false), it) }
            VecKind.POLYGON -> draw(poly(p, true))
            VecKind.LINE -> strokePaint?.let { cv.drawLine(p[0], p[1], p[p.size - 2], p[p.size - 1], it) }
            VecKind.RECT -> draw(Path().apply { addRect(minOf(p[0], p[2]), minOf(p[1], p[3]), maxOf(p[0], p[2]), maxOf(p[1], p[3]), Path.Direction.CW) })
            VecKind.ELLIPSE -> draw(Path().apply { addOval(minOf(p[0], p[2]), minOf(p[1], p[3]), maxOf(p[0], p[2]), maxOf(p[1], p[3]), Path.Direction.CW) })
            VecKind.PRESET -> {
                val kind = runCatching { ShapeKind.valueOf(s.preset) }.getOrNull() ?: return
                val x0 = minOf(p[0], p[2]); val y0 = minOf(p[1], p[3]); val w = kotlin.math.abs(p[2] - p[0]); val h = kotlin.math.abs(p[3] - p[1])
                for (poly in ShapeMath.polygons(kind)) {
                    val path = Path()
                    for (i in poly.indices step 2) {
                        val x = x0 + poly[i] * w; val y = y0 + poly[i + 1] * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close(); draw(path)
                }
            }
        }
    }

    private fun poly(p: List<Float>, close: Boolean): Path {
        val path = Path()
        if (p.size < 2) return path
        path.moveTo(p[0], p[1])
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        if (close) path.close()
        return path
    }

    /** Линия от руки: квадратичные сплайны через середины отрезков - гладкая кривая без углов от шума пальца. */
    private fun smooth(p: List<Float>): Path {
        val path = Path()
        val n = p.size / 2
        if (n == 0) return path
        path.moveTo(p[0], p[1])
        if (n == 1) { path.lineTo(p[0] + 0.01f, p[1]); return path }
        for (i in 1 until n - 1) {
            val mx = (p[i * 2] + p[i * 2 + 2]) / 2f; val my = (p[i * 2 + 1] + p[i * 2 + 3]) / 2f
            path.quadTo(p[i * 2], p[i * 2 + 1], mx, my)
        }
        path.lineTo(p[(n - 1) * 2], p[(n - 1) * 2 + 1])
        return path
    }
}
