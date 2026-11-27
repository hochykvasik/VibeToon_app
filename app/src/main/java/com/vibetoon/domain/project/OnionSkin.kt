package com.vibetoon.domain.project

/**
 * Спринт 37: расширенные настройки кальки (Onion Skin), как в ToonSquid - отдельно для рисунков ДО и ПОСЛЕ текущего:
 * глубина (сколько соседних рисунков), непрозрачность и цвет тонировки; затухание дальних рисунков; тонировка
 * цветом или исходные цвета; «Out of Pegs» - призраки стоят там, где слой был на их собственных кадрах (с учётом
 * ключей трансформации), и не едут вместе с текущим рисунком во время перемещения/трансформации.
 * Настройки редактора: хранятся в настройках приложения (строка [encode]) и попадают в резервную копию настроек.
 * Чистые функции - JVM-тесты.
 */
data class OnionSettings(
    val before: Int = 1,
    val after: Int = 1,
    val beforeAlpha: Float = OnionSkin.DEFAULT_ALPHA,
    val afterAlpha: Float = OnionSkin.DEFAULT_ALPHA,
    val beforeColor: Int = OnionSkin.RED,
    val afterColor: Int = OnionSkin.GREEN,
    /** Каждый следующий рисунок слабее ближайшего (×0.6); выкл. - все рисунки стороны одной непрозрачности. */
    val falloff: Boolean = true,
    /** Тонировать призраков цветом стороны; выкл. - исходные цвета рисунков (только прозрачность). */
    val tint: Boolean = true,
    /** Out of Pegs: призрак берёт трансформацию слоя на своём кадре, а не текущую. */
    val outOfPegs: Boolean = false,
    /** Спринт 51: шаг - показывать каждый N-й соседний рисунок (1 - все подряд, 2 - через один...). */
    val step: Int = 1,
    /** Спринт 51: калька видна и во время воспроизведения. */
    val playback: Boolean = true,
    /** Спринт 51: соседние рисунки - и отдельные слои (Рисунок 1, Рисунок 2...) со своими плашками на таймлайне. */
    val layers: Boolean = true
)

object OnionSkin {
    const val MAX_DEPTH = 4          // 8 мест под призраков на слой: 4 до + 4 после
    const val SLOTS_PER_LAYER = 8
    const val MIN_ALPHA = 0.1f
    const val MAX_ALPHA = 0.9f
    const val DEFAULT_ALPHA = 0.35f
    const val FALLOFF = 0.6
    const val MAX_STEP = 4

    val RED = 0xFFFF3333.toInt()
    val GREEN = 0xFF33FF33.toInt()
    val BLUE = 0xFF3D7BFF.toInt()
    val ORANGE = 0xFFFF9800.toInt()
    val PURPLE = 0xFFB04DFF.toInt()
    val CYAN = 0xFF00C8D7.toInt()
    /** Палитра тонировки в диалоге кальки (можно задать и любой цвет из файла настроек). */
    val PALETTE = listOf(RED, GREEN, BLUE, ORANGE, PURPLE, CYAN)

    fun normalized(s: OnionSettings): OnionSettings = s.copy(
        before = s.before.coerceIn(0, MAX_DEPTH),
        after = s.after.coerceIn(0, MAX_DEPTH),
        beforeAlpha = alphaOf(s.beforeAlpha),
        afterAlpha = alphaOf(s.afterAlpha),
        beforeColor = s.beforeColor or 0xFF000000.toInt(),
        afterColor = s.afterColor or 0xFF000000.toInt(),
        step = s.step.coerceIn(1, MAX_STEP)
    )

    private fun alphaOf(a: Float) = if (a.isFinite()) a.coerceIn(MIN_ALPHA, MAX_ALPHA) else DEFAULT_ALPHA

    /** Непрозрачность [index]-го (0 - ближайший) призрака стороны с базой [base]. */
    fun alpha(base: Float, index: Int, falloff: Boolean): Float =
        if (falloff) (base * Math.pow(FALLOFF, index.toDouble())).toFloat().coerceIn(0f, 1f) else base.coerceIn(0f, 1f)

    /** Один призрак: место [slot] (0..7) среди призраков слоя, рисунок [cell], цвет тонировки (0..1) и непрозрачность. */
    data class Ghost(val slot: Int, val before: Boolean, val index: Int, val cell: FrameCell,
                     val r: Float, val g: Float, val b: Float, val alpha: Float, val tint: Boolean)

    /** Призраки покадрового слоя [cells] на кадре [frame]: сначала предыдущие (ближайший первым), потом следующие. */
    fun plan(cells: List<FrameCell>, frame: Int, settings: OnionSettings): List<Ghost> {
        val s = normalized(settings)
        if (cells.size < 2 || (s.before == 0 && s.after == 0)) return emptyList()
        val (prev0, next0) = FrameMath.neighbors(cells, frame, maxOf(s.before, s.after) * s.step)
        val prev = stepped(prev0, s.step); val next = stepped(next0, s.step)
        val out = ArrayList<Ghost>()
        prev.take(s.before).forEachIndexed { i, c -> out += ghost(i, true, i, c, s.beforeColor, alpha(s.beforeAlpha, i, s.falloff), s.tint) }
        next.take(s.after).forEachIndexed { i, c -> out += ghost(MAX_DEPTH + i, false, i, c, s.afterColor, alpha(s.afterAlpha, i, s.falloff), s.tint) }
        return out
    }

    /** Каждый [step]-й элемент (ближайший первым): шаг 2 - второй, четвёртый... */
    fun <T> stepped(list: List<T>, step: Int): List<T> = if (step <= 1) list else list.filterIndexed { i, _ -> (i + 1) % step == 0 }

    /** Спринт 51: рисунок-слой для кальки по слоям: слой [id] виден на кадрах [start, end). */
    data class Span(val id: Int, val start: Int, val end: Int)

    /** Призрак-слой: соседний рисунок [id] (его плашка начинается на [frame]), сторона, номер, цвет и непрозрачность. */
    data class LayerGhost(val id: Int, val slot: Int, val before: Boolean, val index: Int, val frame: Int,
                          val r: Float, val g: Float, val b: Float, val alpha: Float, val tint: Boolean)

    /**
     * Спринт 51: калька по отдельным слоям-рисункам (раскладка «лесенкой», как Рисунок 1 / Рисунок 2 на таймлайне).
     * ДО - слои, чья плашка закончилась не позже [frame] (ближайший конец первым), ПОСЛЕ - начинающиеся позже [frame]
     * (ближайшее начало первым); слои, видимые на кадре, - текущие и призраками не становятся. Шаг и глубина - как у
     * рисунков одной дорожки.
     */
    fun planLayers(spans: List<Span>, frame: Int, settings: OnionSettings): List<LayerGhost> {
        val s = normalized(settings)
        if (spans.size < 2 || (s.before == 0 && s.after == 0)) return emptyList()
        val prev = stepped(spans.filter { it.end <= frame && it.end > it.start }.sortedWith(compareByDescending<Span> { it.end }.thenByDescending { it.start }), s.step).take(s.before)
        val next = stepped(spans.filter { it.start > frame && it.end > it.start }.sortedWith(compareBy<Span> { it.start }.thenBy { it.end }), s.step).take(s.after)
        val out = ArrayList<LayerGhost>()
        fun c(color: Int, sh: Int) = ((color shr sh) and 0xFF) / 255f
        prev.forEachIndexed { i, sp -> out += LayerGhost(sp.id, i, true, i, sp.start, c(s.beforeColor, 16), c(s.beforeColor, 8), c(s.beforeColor, 0), alpha(s.beforeAlpha, i, s.falloff), s.tint) }
        next.forEachIndexed { i, sp -> out += LayerGhost(sp.id, MAX_DEPTH + i, false, i, sp.start, c(s.afterColor, 16), c(s.afterColor, 8), c(s.afterColor, 0), alpha(s.afterAlpha, i, s.falloff), s.tint) }
        return out
    }

    /** Показывать ли кальку сейчас: включена и (не идёт Play или разрешена при воспроизведении). */
    fun activeNow(enabled: Boolean, playing: Boolean, settings: OnionSettings): Boolean = enabled && (!playing || settings.playback)

    private fun ghost(slot: Int, before: Boolean, i: Int, c: FrameCell, color: Int, a: Float, tint: Boolean) =
        Ghost(slot, before, i, c, ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f, a, tint)

    /** Строка для настроек: «v1;b=1;a=1;ba=35;aa=35;bc=FF3333;ac=33FF33;f=1;t=1;p=0;s=1;pl=1;ly=1» (непрозрачность в процентах; s, pl, ly - спринт 51, старые строки без них читаются). */
    fun encode(settings: OnionSettings): String {
        val s = normalized(settings)
        fun pct(a: Float) = Math.round(a * 100)
        fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
        fun bit(b: Boolean) = if (b) 1 else 0
        return "v1;b=${s.before};a=${s.after};ba=${pct(s.beforeAlpha)};aa=${pct(s.afterAlpha)};bc=${hex(s.beforeColor)};ac=${hex(s.afterColor)};" +
            "f=${bit(s.falloff)};t=${bit(s.tint)};p=${bit(s.outOfPegs)};s=${s.step};pl=${bit(s.playback)};ly=${bit(s.layers)}"
    }

    /** Разбор строки настроек; нет строки или мусор - значения по умолчанию, отдельные битые поля - их значения по умолчанию. */
    fun decode(text: String?): OnionSettings {
        val d = OnionSettings()
        if (text.isNullOrBlank() || !text.startsWith("v1")) return d
        val kv = text.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        fun int(k: String, def: Int) = kv[k]?.toIntOrNull() ?: def
        fun pct(k: String, def: Float) = kv[k]?.toIntOrNull()?.let { it / 100f } ?: def
        fun color(k: String, def: Int) = kv[k]?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { it or 0xFF000000.toInt() } ?: def
        fun bool(k: String, def: Boolean) = when (kv[k]) { "1" -> true; "0" -> false; else -> def }
        return normalized(OnionSettings(
            before = int("b", d.before), after = int("a", d.after),
            beforeAlpha = pct("ba", d.beforeAlpha), afterAlpha = pct("aa", d.afterAlpha),
            beforeColor = color("bc", d.beforeColor), afterColor = color("ac", d.afterColor),
            falloff = bool("f", d.falloff), tint = bool("t", d.tint), outOfPegs = bool("p", d.outOfPegs),
            step = int("s", d.step), playback = bool("pl", d.playback), layers = bool("ly", d.layers)
        ))
    }

    /** Кадр, на котором призрак берёт трансформацию слоя: Out of Pegs - кадр начала его рисунка, иначе текущий. */
    fun transformFrame(ghost: Ghost, current: Int, outOfPegs: Boolean): Int = if (outOfPegs) ghost.cell.start else current
}
