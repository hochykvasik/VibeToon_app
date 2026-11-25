package com.vibetoon.domain.anim

/**
 * Спринт 44: кривая интерполяции ОДНОГО ключа. «Общая кривая» (спринт 43) меняет все выделенные ключи сразу; здесь
 * выбирается ровно один ромб и правится только он. Ромб слоя - это ключи всех его дорожек на этом кадре
 * (трансформация, Warp, Mesh, перспектива, пины, эффекты), поэтому кривая ставится им всем, а соседние ключи и
 * другие слои не меняются. У кости - одна собственная дорожка. Кривая ключа действует до следующего ключа.
 *
 * Какой ключ правится ([pick]):
 *  1. на таймлайне выделен ровно один ромб - он;
 *  2. иначе - единственный выделенный ромб выбранного слоя/кости (несколько - это «Общая кривая», не «Кривая ключа»);
 *  3. иначе - ключ выбранного слоя/кости на текущем кадре (панель свойств).
 * Чистые функции - Sprint44Test.
 */
object SingleKeyCurve {
    /**
     * Состояние ключа для панели свойств: плавность и своя кривая опорного ключа (ключ трансформации, если он есть),
     * сколько дорожек слоя имеют ключ на кадре и различаются ли их кривые ([mixed] - например, после правок по одной дорожке).
     */
    data class Info(val id: Int, val frame: Int, val easing: EasingType, val curve: List<Float>?, val tracks: Int, val mixed: Boolean) {
        val custom: Boolean get() = curve != null
    }

    /** Ключ (слой/кость, кадр), который правит «Кривая ключа», или null - ключ не выбран однозначно. */
    fun pick(
        keySel: Set<Pair<Int, Int>>, selected: Int?, frame: Int,
        tracks: Map<Int, AnimationTrack>, isBone: (Int) -> Boolean
    ): Pair<Int, Int>? {
        fun valid(k: Pair<Int, Int>) = targets(k.first, k.second, tracks, isBone(k.first)).isNotEmpty()
        if (keySel.size == 1) return keySel.first().takeIf(::valid)
        if (selected == null) return null
        val mine = keySel.filter { it.first == selected }
        if (mine.size > 1) return null
        if (mine.size == 1) return mine[0].takeIf(::valid)
        return (selected to frame).takeIf(::valid)
    }

    /** Дорожки ромба [id] на кадре [frame], у которых есть ключ: именно они получают кривую. */
    fun targets(id: Int, frame: Int, tracks: Map<Int, AnimationTrack>, isBone: Boolean): List<Pair<Int, Int>> =
        KeyList.trackIds(id, tracks, isBone).filter { tracks[it]?.keyAt(frame) != null }
            .sortedBy { if (it == id) 0 else 1 }   // своя дорожка (трансформация) первой: по ней редактор берёт исходную кривую
            .map { it to frame }

    fun info(id: Int, frame: Int, tracks: Map<Int, AnimationTrack>, isBone: Boolean): Info? {
        val t = targets(id, frame, tracks, isBone)
        if (t.isEmpty()) return null
        val keys = t.mapNotNull { tracks[it.first]?.keyAt(frame) }
        val ref = tracks[id]?.keyAt(frame) ?: keys.first()
        val mixed = keys.any { it.easing != ref.easing || it.curve != ref.curve }
        return Info(id, frame, ref.easing, ref.curve, t.size, mixed)
    }

    /** Кривая для редактора: своя кривая ключа или кривая его пресета плавности. */
    fun curveOf(info: Info?): Curve =
        info?.curve?.let { Curve.parse(it) } ?: info?.let { Curve.of(it.easing) } ?: Curve.LINEAR

    /** Своя кривая [data] (null - вернуть к плавности ключа) только ключу [id] на кадре [frame]. */
    fun withCurve(a: Animation, id: Int, frame: Int, isBone: Boolean, data: List<Float>?): Animation {
        var out = a
        for ((t, f) in targets(id, frame, a.tracks, isBone)) out = out.withCurve(t, f, data)
        return out
    }

    /** Пресет плавности [type] только ключу [id] на кадре [frame] (своя кривая сбрасывается). */
    fun withEasing(a: Animation, id: Int, frame: Int, isBone: Boolean, type: EasingType): Animation =
        KeyList.withEasing(a, id, frame, type, isBone)
}
