package com.vibetoon.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.vibetoon.domain.project.TextBidi
import com.vibetoon.domain.project.TextFx
import com.vibetoon.domain.project.TextFxMath
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Результат отрисовки текста: Bitmap для текстуры и размер блока в пикселях холста. */
class RenderedText(val bitmap: Bitmap, val layoutWidth: Float, val layoutHeight: Float)

/**
 * Текст -> Bitmap средствами Android (TextPaint и StaticLayout), без нативного рендера шрифтов.
 * Дальше Bitmap уходит в C++ как обычная текстура, поэтому текст работает с трансформациями и
 * ключами. Размер блока в пикселях холста не зависит от разрешения текстуры: если блок больше
 * [MAX_SIDE], текстура рисуется с уменьшением, а на холсте остаётся тот же размер.
 */
class TextRenderer(private val fonts: FontRegistry) {

    fun render(
        text: String, color: Int, fontKey: String, sizePx: Float,
        gradient: List<Long> = emptyList(), gradientAngle: Float = 0f,
        strokeWidth: Float = 0f, strokeColor: Int = 0xFF000000.toInt(),
        align: Int = 0, lineSpacing: Float = 1f, letterSpacing: Float = 0f, direction: Int = TextBidi.AUTO
    ): RenderedText {
        val size = sizePx.coerceIn(MIN_SIZE, MAX_SIZE)
        val content = text.ifEmpty { " " }
        val dir = TextBidi.coerce(direction)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = fonts.typeface(fontKey)
            this.letterSpacing = letterSpacing.coerceIn(-0.2f, 1f)
        }
        // спринт 39: выравнивание в модели физическое (слева/справа), а StaticLayout считает его от направления абзаца,
        // поэтому каждому абзацу ставится свой AlignmentSpan (в режиме «Авто» абзацы могут быть разного направления)
        val firstRtl = TextBidi.paragraphRtl(dir, content.substringBefore('\n'))
        val alignment = layoutAlignment(TextBidi.layoutAlign(align, firstRtl))
        val heuristic = when (dir) {
            TextBidi.RTL -> android.text.TextDirectionHeuristics.RTL
            TextBidi.LTR -> android.text.TextDirectionHeuristics.LTR
            else -> android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR
        }
        val spanned = android.text.SpannableStringBuilder(content)
        var paraStart = 0
        for (para in content.split('\n')) {
            val end = paraStart + para.length
            val a = layoutAlignment(TextBidi.layoutAlign(align, TextBidi.paragraphRtl(dir, para)))
            val spanEnd = minOf(content.length, end + 1)   // абзац вместе с его \n: границы PARAGRAPH-спана
            if (spanEnd > paraStart) spanned.setSpan(android.text.style.AlignmentSpan.Standard(a), paraStart, spanEnd, android.text.Spanned.SPAN_PARAGRAPH)
            paraStart = end + 1
        }
        val lineMul = lineSpacing.coerceIn(0.5f, 3f)
        // Без переноса по ширине: ширина блока = самая длинная строка (переносы только по Enter)
        val longest = content.split('\n').maxOf { paint.measureText(it) }
        val sw = strokeWidth.coerceIn(0f, MAX_STROKE)
        val pad = size * 0.12f + sw
        val layoutW = max(1, ceil(longest).toInt() + 1)
        val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, layoutW)
            .setAlignment(alignment)
            .setTextDirection(heuristic)
            .setLineSpacing(0f, lineMul)
            .setIncludePad(false)
            .build()
        // Градиентная заливка: линейный градиент вдоль угла через весь блок текста
        if (gradient.size >= 2) {
            val e = gradientEnds(gradientAngle, layoutW.toFloat(), layout.height.toFloat())
            paint.shader = android.graphics.LinearGradient(
                e[0], e[1], e[2], e[3],
                gradient.map { it.toInt() }.toIntArray(), null, android.graphics.Shader.TileMode.CLAMP
            )
        }
        val totalW = layoutW + 2 * pad
        val totalH = layout.height + 2 * pad

        val k = min(1f, MAX_SIDE / max(totalW, totalH))
        val bw = max(1, (totalW * k).toInt())
        val bh = max(1, (totalH * k).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)   // premultiplied
        val canvas = Canvas(bmp)
        canvas.scale(bw / totalW, bh / totalH)
        canvas.translate(pad, pad)
        if (sw > 0f) {   // обводка рисуется ДО заливки: снаружи букв остаётся sw px контура
            val sp = TextPaint(paint).apply {
                shader = null; this.color = strokeColor
                style = android.graphics.Paint.Style.STROKE; this.strokeWidth = 2f * sw; strokeJoin = android.graphics.Paint.Join.ROUND
            }
            StaticLayout.Builder.obtain(spanned, 0, spanned.length, sp, layoutW)
                .setAlignment(alignment).setTextDirection(heuristic).setLineSpacing(0f, lineMul).setIncludePad(false).build().draw(canvas)   // тот же layout, что и у заливки: обводка не разъезжается
        }
        layout.draw(canvas)
        return RenderedText(bmp, totalW, totalH)
    }

    /**
     * Текст с эффектами на момент [t] (секунды проекта): печать, счётчик, тряска, контур, толщина,
     * рандомизатор. Размер блока считается по самой длинной возможной строке и не зависит от [t],
     * поэтому слой не «дышит». Буквы рисуются по одной (каждая со своим смещением и углом).
     */
    fun renderFx(
        text: String, color: Int, fontKey: String, sizePx: Float,
        gradient: List<Long>, gradientAngle: Float, fx: TextFx, t: Float, fps: Int,
        strokeWidth: Float = 0f, strokeColor: Int = 0xFF000000.toInt(),
        align: Int = 0, lineSpacing: Float = 1f, letterSpacing: Float = 0f, direction: Int = TextBidi.AUTO
    ): RenderedText {
        // выравнивание и интервалы работают без текстовых эффектов; с эффектами буквы рисуются по одной и остаются с обычными интервалами
        if (!fx.active) return render(text, color, fontKey, sizePx, gradient, gradientAngle, strokeWidth, strokeColor, align, lineSpacing, letterSpacing, direction)
        val sw = strokeWidth.coerceIn(0f, MAX_STROKE)
        val size = sizePx.coerceIn(MIN_SIZE, MAX_SIZE)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = fonts.typeface(fontKey)
        }
        val template = (if (fx.counter) TextFxMath.counterWidest(text, fx, fps) else text).ifEmpty { " " }
        val metrics = paint.fontMetrics
        val lineH = metrics.descent - metrics.ascent
        val tLines = template.split('\n')
        val layoutW = max(1f, ceil(tLines.maxOf { paint.measureText(it) }) + 1f)
        val layoutH = lineH * tLines.size
        val strokeMax = size * 0.06f
        val pad = size * 0.12f + max(TextFxMath.pathPad(layoutW, fx.pathBend), fx.shake * size * 0.25f) + strokeMax + sw
        val totalW = layoutW + 2 * pad
        val totalH = layoutH + 2 * pad
        var gradShader: android.graphics.Shader? = null
        if (gradient.size >= 2) {
            val e = gradientEnds(gradientAngle, layoutW, layoutH)
            gradShader = android.graphics.LinearGradient(
                e[0] + pad, e[1] + pad, e[2] + pad, e[3] + pad,
                gradient.map { it.toInt() }.toIntArray(), null, android.graphics.Shader.TileMode.CLAMP
            )
            paint.shader = gradShader
        }
        val inv = android.graphics.Matrix()
        val strokePaint = if (sw > 0f) TextPaint(paint).apply {
            shader = null; this.color = strokeColor
            style = android.graphics.Paint.Style.STROKE; this.strokeWidth = 2f * sw + (if (fx.weight) paint.strokeWidth else 0f); strokeJoin = android.graphics.Paint.Join.ROUND
        } else null
        if (fx.weight) {
            paint.style = android.graphics.Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = strokeMax * (TextFxMath.weightAt(fx, t) - TextFxMath.WEIGHT_MIN) / (TextFxMath.WEIGHT_MAX - TextFxMath.WEIGHT_MIN)
            paint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val k = min(1f, MAX_SIDE / max(totalW, totalH))
        val bmp = Bitmap.createBitmap(max(1, (totalW * k).toInt()), max(1, (totalH * k).toInt()), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(bmp.width / totalW, bmp.height / totalH)
        val geometry = TextFxMath.display(text, fx.copy(progress = false), t, fps).split('\n')   // раскладка без обрезки печати
        val masked = TextFxMath.display(text, fx, t, fps).split('\n')
        val off = FloatArray(2)
        val arc = FloatArray(3)
        var glyph = 0
        // спринт 39: буквы рисуются в визуальном порядке (TextBidi): в RTL-абзаце справа налево, числа и латиница внутри -
        // слева направо, скобки зеркалятся; связанные арабские буквы рисуются словом, чтобы шрифт собрал их формы.
        // Строка встаёт по выравниванию (слева/по центру/справа), счётчик - как раньше по центру.
        val dir = TextBidi.coerce(direction)
        for ((li, line) in geometry.withIndex()) {
            val rtl = TextBidi.paragraphRtl(dir, line)
            val units = TextBidi.visualUnits(line, rtl)
            // спринт 40: невидимые знаки направления (LRM/RLM, изоляты LRI…PDI, встраивания) не рисуются и не занимают места
            val widths = FloatArray(units.size) { ui -> if (TextBidi.isControlUnit(line, units[ui].range)) 0f else paint.measureText(line, units[ui].range.first, units[ui].range.last + 1) }
            // строка без RTL - позиции как раньше, по ширине начала строки (с кернингом)
            val plain = units.all { it.level == 0 }
            val lineW = if (plain) paint.measureText(line) else widths.sum()
            val startX = if (fx.counter) (layoutW - lineW) / 2f else TextBidi.lineStartX(align, layoutW, lineW)
            val baseY = pad - metrics.ascent + li * lineH
            val maskLine = masked.getOrNull(li)
            var before = 0f
            for ((ui, u) in units.withIndex()) {
                val gw = widths[ui]
                val center = startX + (if (plain) paint.measureText(line, 0, u.range.first) else before) + gw / 2f
                before += gw
                if (TextBidi.isControlUnit(line, u.range)) continue
                // счёт букв для тряски идёт по логическому порядку, как и раньше (первая введённая буква - первая)
                val gi = glyph + u.range.first
                val visible = u.range.count { maskLine?.getOrNull(it) != '\u0000' }
                if (visible == 0) continue
                val full = line.substring(u.range.first, u.range.last + 1)
                val ch = when {
                    visible < full.length -> full.substring(0, visible)   // печать: видна логически первая часть слова
                    u.rtl && full.length == 1 -> TextBidi.mirror(full[0]).toString()
                    else -> full
                }
                val cw = if (ch.length == full.length) gw else paint.measureText(ch)
                // часть RTL-слова прижата к его правому краю (начало слова справа), LTR - к левому
                val dx = when {
                    ch.length == full.length -> -gw / 2f
                    u.rtl -> gw / 2f - cw
                    else -> -gw / 2f
                }
                TextFxMath.shakeOffset(gi, fx, t, size, off)
                canvas.save()
                var tx = pad + center + off[0]
                var ty = baseY + off[1]
                var deg = 0f
                if (fx.pathBend != 0f) {
                    TextFxMath.onArc(center, layoutW, fx.pathBend, arc)
                    tx = pad + arc[0] + off[0]; ty = baseY + arc[1] + off[1]
                    deg = Math.toDegrees(arc[2].toDouble()).toFloat()
                }
                canvas.translate(tx, ty)
                if (deg != 0f) canvas.rotate(deg)
                if (gradShader != null) {   // градиент общий для всего блока, а не едет за буквой
                    inv.setTranslate(-tx, -ty); inv.postRotate(-deg)
                    gradShader.setLocalMatrix(inv)
                }
                if (strokePaint != null) drawUnit(canvas, ch, dx, u.rtl, strokePaint)   // обводка до заливки
                drawUnit(canvas, ch, dx, u.rtl, paint)
                canvas.restore()
            }
            glyph += line.length
        }
        return RenderedText(bmp, totalW, totalH)
    }

    /** Единица текста: RTL-слово рисуется как RTL-ран (формы арабских букв), остальное - обычным drawText. */
    private fun drawUnit(canvas: Canvas, s: String, x: Float, rtl: Boolean, p: android.graphics.Paint) {
        if (rtl && s.length > 1) canvas.drawTextRun(s, 0, s.length, 0, s.length, x, 0f, true, p)
        else canvas.drawText(s, x, 0f, p)
    }

    /** 0 NORMAL, 1 CENTER, 2 OPPOSITE (TextBidi.layoutAlign) -> Layout.Alignment. */
    private fun layoutAlignment(code: Int): Layout.Alignment = when (code) {
        1 -> Layout.Alignment.ALIGN_CENTER
        2 -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    companion object {
        /**
         * Концы линии градиента [x0, y0, x1, y1] для угла в градусах (0 - слева направо, 90 - сверху вниз) в блоке w x h.
         * Линия проходит через центр и достаточно длинная, чтобы углы блока получили крайние цвета.
         */
        fun gradientEnds(angleDeg: Float, w: Float, h: Float): FloatArray {
            val rad = Math.toRadians(angleDeg.toDouble())
            val dx = Math.cos(rad).toFloat()
            val dy = Math.sin(rad).toFloat()
            val half = (kotlin.math.abs(dx) * w + kotlin.math.abs(dy) * h) / 2f
            val cx = w / 2f
            val cy = h / 2f
            return floatArrayOf(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half)
        }

        const val MAX_STROKE = 50f
        const val MAX_SIDE = 2048f
        const val MIN_SIZE = 4f
        const val MAX_SIZE = 1500f
    }
}
