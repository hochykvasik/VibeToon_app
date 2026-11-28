package com.vibetoon.domain.paint

/**
 * Спринт 62: окно выбора цвета помнит оттенок и насыщенность, когда яркость уведена в 0.
 *
 * Было: у чёрного (яркость 0) оттенок и насыщенность не определены - после закрытия окна на чёрном цвете при
 * следующем открытии маркер круга стоял в центре (оттенок 0, насыщенность 0), и, вернув яркость, пользователь
 * получал серый вместо прежнего цвета. Тап по кругу на чёрном ставил яркость 100 %, а не прежнюю.
 *
 * Стало: [Memo] - точные H, S, V последнего цвета окна и [Memo.lit] - яркость до обнуления. Он хранится рядом с
 * последними цветами (vt_colors) и применяется, если окно открывается на том же цвете или на чёрном/сером
 * (у них оттенок не виден). Яркость 0 → 100 % возвращает прежний цвет, тап по кругу на чёрном - прежнюю яркость.
 * Чистые функции - Sprint62Test.
 */
object ColorMemory {
    /** Почти чёрный: оттенок по кругу не различим (как порог тапа по кругу в окне). */
    const val DARK = 0.05f
    /** Ключ записи в настройках окна цвета (vt_colors). */
    const val PREF_KEY = "memo"

    data class Memo(val argb: Long, val h: Float, val s: Float, val v: Float, val lit: Float = 1f) {
        val color: Long get() = ColorModels.argb(h, s, v)
    }

    private fun f01(x: Float, def: Float) = if (x.isFinite()) x.coerceIn(0f, 1f) else def
    private fun hue(x: Float) = if (x.isFinite()) ((x % 360f) + 360f) % 360f else 0f
    private fun rgb(c: Long) = c and 0xFFFFFFL

    /** Нормализованная запись: оттенок 0..360, S/V 0..1, «яркость до обнуления» - не меньше [DARK]. */
    fun of(h: Float, s: Float, v: Float, lit: Float = 1f): Memo {
        val vv = f01(v, 1f)
        val l = f01(lit, 1f).let { if (it < DARK) 1f else it }
        return Memo(ColorModels.argb(hue(h), f01(s, 0f), vv) and 0xFFFFFFFFL, hue(h), f01(s, 0f), vv, l)
    }

    /**
     * Начальное состояние окна для цвета [initial]. Тот же цвет, что в [memo], - точные значения записи (без потерь
     * округления до 8 бит). Чёрный - оттенок и насыщенность из записи (маркер на прежнем месте), серый - оттенок.
     * Иначе - цвет как есть.
     */
    fun open(initial: Long, memo: Memo?): Memo {
        if (memo != null && rgb(memo.argb) == rgb(initial)) return of(memo.h, memo.s, memo.v, memo.lit)
        val h = ColorModels.hsbOf(initial, memo?.h ?: 0f, memo?.s ?: 0f)
        return of(h.h, h.s, h.v, if (h.v >= DARK) h.v else memo?.lit ?: 1f)
    }

    /** Ползунок яркости: оттенок и насыщенность не трогаются; уходя в тёмное, запоминаем яркость до обнуления. */
    fun withValue(m: Memo, v: Float): Memo {
        val nv = f01(v, m.v)
        val lit = if (m.v >= DARK) m.v else m.lit
        return of(m.h, m.s, nv, if (nv >= DARK) nv else lit)
    }

    fun withHue(m: Memo, h: Float): Memo = of(h, m.s, m.v, m.lit)
    fun withSat(m: Memo, s: Float): Memo = of(m.h, s, m.v, m.lit)

    /**
     * Тап/протяжка по кругу: оттенок и насыщенность из точки; на почти чёрном яркость возвращается к той, что была
     * до обнуления (а не всегда к 100 %), иначе выбранный оттенок не виден.
     */
    fun pickWheel(m: Memo, h: Float, s: Float): Memo =
        if (m.v < DARK) of(h, s, m.lit, m.lit) else of(h, s, m.v, m.lit)

    /** Готовый цвет (палитра, HEX, RGB, пипетка): у чёрного и серого оттенок (и у чёрного - насыщенность) остаются. */
    fun withArgb(m: Memo, argb: Long): Memo {
        val h = ColorModels.hsbOf(argb, m.h, m.s)
        val lit = if (h.v >= DARK) h.v else if (m.v >= DARK) m.v else m.lit
        return of(h.h, h.s, h.v, lit)
    }

    fun encode(m: Memo): String = "v1;%08X;%.3f;%.4f;%.4f;%.4f".format(java.util.Locale.ROOT, m.argb and 0xFFFFFFFFL, m.h, m.s, m.v, m.lit)

    /** Разбор записи; мусор или чужая версия - null (окно открывается по цвету как раньше). */
    fun decode(text: String?): Memo? {
        val p = text?.split(';') ?: return null
        if (p.size != 6 || p[0] != "v1") return null
        val argb = p[1].toLongOrNull(16) ?: return null
        val n = p.drop(2).map { it.toFloatOrNull() ?: return null }
        if (n.any { !it.isFinite() }) return null
        val m = of(n[0], n[1], n[2], n[3])
        // запись должна соответствовать своему цвету (иначе файл настроек испорчен)
        return if (rgb(m.argb) == rgb(argb)) m else null
    }
}
