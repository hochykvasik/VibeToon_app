package com.vibetoon.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import com.vibetoon.domain.project.PsdInsertPlan
import kotlin.math.roundToInt

/**
 * Пакет 23: сведение слоёв PSD в одну картинку (режимы «Одним слоем» и «Слои как кадры»). Слои рисуются по операциям
 * [PsdInsertPlan.composeOps] снизу вверх: прозрачность групп уже умножена, обтравка - через слой холста основы
 * (обтравленные рисуются SRC_ATOP), режимы наложения - BlendMode (API 29+) или ближайший PorterDuff.
 * Приближение: группы не изолируются, наложение обтравленных слоёв обычное.
 */
object PsdComposer {
    /** Декодированный слой: картинка и её место на итоговом холсте (пиксели результата). */
    class Piece(val bitmap: Bitmap, val dst: RectF)

    fun compose(width: Int, height: Int, ops: List<PsdInsertPlan.Op>, decode: (Int) -> Piece?): Bitmap {
        val out = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var open = false
        for (op in ops) {
            if (op.role != PsdInsertPlan.Role.CLIPPED && open) { canvas.restore(); open = false }
            if (op.role == PsdInsertPlan.Role.BASE) { canvas.saveLayer(null, paint(op.opacity, op.blend)); open = true }
            val piece = decode(op.psdId) ?: continue
            try {
                when (op.role) {
                    PsdInsertPlan.Role.PLAIN -> canvas.drawBitmap(piece.bitmap, null, piece.dst, paint(op.opacity, op.blend))
                    PsdInsertPlan.Role.BASE -> canvas.drawBitmap(piece.bitmap, null, piece.dst, Paint(Paint.FILTER_BITMAP_FLAG))
                    PsdInsertPlan.Role.CLIPPED -> canvas.drawBitmap(piece.bitmap, null, piece.dst,
                        if (open) Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = alphaOf(op.opacity); xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
                        else paint(op.opacity, op.blend))
                }
            } finally { piece.bitmap.recycle() }
        }
        if (open) canvas.restore()
        return out
    }

    private fun alphaOf(opacity: Float) = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()

    private fun paint(opacity: Float, blend: String): Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = alphaOf(opacity)
        val name = PsdInsertPlan.blendName(blend) ?: return@apply
        if (Build.VERSION.SDK_INT >= 29) runCatching { blendMode = android.graphics.BlendMode.valueOf(name) }
        else PsdInsertPlan.porterDuffName(name)?.let { pd -> runCatching { xfermode = PorterDuffXfermode(PorterDuff.Mode.valueOf(pd)) } }
    }
}
