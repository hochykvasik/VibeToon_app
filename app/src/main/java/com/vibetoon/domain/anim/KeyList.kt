package com.vibetoon.domain.anim

/**
 * Окно «Список ключей» (пакет 11): все ключи одного слоя или кости по кадрам с интерполяцией каждого ключа.
 * Ромб на строке слоя объединяет ключи всех его дорожек на кадре (трансформация, точки Warp, вершины Mesh, углы
 * Perspective...), поэтому интерполяция ставится сразу всем дорожкам слоя на этом кадре. У кости - одна дорожка.
 * Чистые функции - проверяются unit-тестом.
 */
object KeyList {
    /**
     * Строка списка: кадр, ключ трансформации (null - на кадре только ключи деформаций), его плавность,
     * есть ли своя кривая и сколько дорожек слоя имеют ключ на этом кадре.
     */
    data class Row(val frame: Int, val transform: Keyframe?, val easing: EasingType, val custom: Boolean, val tracks: Int)

    /** Дорожки узла: у кости - только её собственная, у слоя - все группы дорожек ([TrackGroups.ids]). */
    fun trackIds(id: Int, tracks: Map<Int, AnimationTrack>, isBone: Boolean): List<Int> =
        if (isBone) listOfNotNull(id.takeIf { tracks[it]?.isEmpty() == false }) else TrackGroups.ids(id, tracks)

    fun rows(id: Int, tracks: Map<Int, AnimationTrack>, isBone: Boolean = false): List<Row> {
        val ids = trackIds(id, tracks, isBone)
        val byFrame = sortedMapOf<Int, MutableList<Keyframe>>()
        for (t in ids) tracks[t]?.keys?.forEach { byFrame.getOrPut(it.frame) { ArrayList() }.add(it) }
        return byFrame.map { (f, ks) ->
            val own = tracks[id]?.keyAt(f)
            val ref = own ?: ks.first()
            Row(f, own, ref.easing, ref.curve != null, ks.size)
        }
    }

    /** Плавность [type] ключам всех дорожек узла на кадре [frame] (своя кривая сбрасывается). */
    fun withEasing(a: Animation, id: Int, frame: Int, type: EasingType, isBone: Boolean = false): Animation {
        var out = a
        for (t in trackIds(id, a.tracks, isBone)) if (out.hasKey(t, frame)) out = out.withEasing(t, frame, type)
        return out
    }

    /** Плавность [type] всем ключам узла (кнопка «Всем ключам»). */
    fun withEasingAll(a: Animation, id: Int, type: EasingType, isBone: Boolean = false): Animation {
        var out = a
        for (r in rows(id, a.tracks, isBone)) out = withEasing(out, id, r.frame, type, isBone)
        return out
    }

    /** Краткая сводка ключа трансформации: X, Y, масштаб %, поворот °, прозрачность %. */
    fun summary(k: Keyframe): String {
        fun n(v: Float) = Math.round(v).toString()
        val sx = Math.round(k.scaleX * 100f); val sy = Math.round(k.scaleY * 100f)
        val sc = if (sx == sy) "$sx%" else "$sx×$sy%"
        return "X ${n(k.dx)} · Y ${n(k.dy)} · S $sc · R ${n(Math.toDegrees(k.rotation.toDouble()).toFloat())}° · O ${Math.round(k.opacity * 100f)}%"
    }
}
