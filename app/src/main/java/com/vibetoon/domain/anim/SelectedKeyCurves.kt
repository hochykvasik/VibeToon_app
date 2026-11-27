package com.vibetoon.domain.anim

/** Применение одной кривой ко всем реальным дорожкам выбранных ромбов. */
object SelectedKeyCurves {
    fun targets(animation: Animation, selection: Set<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        for ((layer, frames) in selection.groupBy({ it.first }, { it.second })) {
            for (id in TrackGroups.ids(layer, animation.tracks)) {
                val track = animation.tracks[id] ?: continue
                for (f in frames) if (track.keyAt(f) != null) result += id to f
            }
        }
        return result.sortedWith(compareBy({ it.first }, { it.second }))
    }

    /**
     * Ключи времени символов в выделении таймлайна: экземпляр -> кадры его ключей. У ключа времени «кривая» - это переход
     * к следующему ключу (Hold / Линейно / Плавно...), поэтому «Общая кривая» для них задаёт общий переход, а не
     * останавливается сообщением «В выделении нет ключей».
     */
    fun timeKeyTargets(selection: Set<Pair<Int, Int>>, timeKeys: Map<Int, List<com.vibetoon.domain.project.TimeKey>>): Map<Int, Set<Int>> {
        val out = LinkedHashMap<Int, Set<Int>>()
        for ((inst, frames) in selection.groupBy({ it.first }, { it.second })) {
            val keys = timeKeys[inst] ?: continue
            val fs = frames.filterTo(HashSet()) { f -> keys.any { it.frame == f } }
            if (fs.isNotEmpty()) out[inst] = fs
        }
        return out
    }

    /** Выделены маркеры открытого символа: ключи времени экземпляров, выбирающие эти формы (значение = кадр формы). */
    fun markerTimeKeyTargets(forms: Set<Int>, timeKeys: Map<Int, List<com.vibetoon.domain.project.TimeKey>>): Map<Int, Set<Int>> {
        val out = LinkedHashMap<Int, Set<Int>>()
        for ((inst, keys) in timeKeys) {
            val fs = keys.filter { it.value in forms }.mapTo(HashSet()) { it.frame }
            if (fs.isNotEmpty()) out[inst] = fs
        }
        return out
    }

    /**
     * Спринт 45: выделенные ромбы как один набор (например, по ключу на трёх разных слоях). [keys] - сколько ромбов
     * получают кривую, [layers] - на скольких слоях/костях, опорная плавность и кривая - у первого ключа,
     * [mixed] - у ключей набора разные кривые (выбор задаст одну всем).
     */
    data class SetInfo(val keys: Int, val layers: Int, val easing: EasingType, val curve: List<Float>?, val mixed: Boolean) {
        val custom: Boolean get() = curve != null
    }

    /** Ромбы выделения, у которых есть хотя бы одна дорожка с ключом (пустые выделения - например, ключи времени - не считаются). */
    fun keysWithTracks(animation: Animation, selection: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
        val out = LinkedHashSet<Pair<Int, Int>>()
        for ((layer, frames) in selection.groupBy({ it.first }, { it.second })) {
            val ids = TrackGroups.ids(layer, animation.tracks)
            for (f in frames) if (ids.any { animation.tracks[it]?.keyAt(f) != null }) out += layer to f
        }
        return out
    }

    /** Состояние набора для панели свойств; null - в выделении меньше двух ромбов с ключами (это «Кривая ключа»). */
    fun info(animation: Animation, selection: Set<Pair<Int, Int>>): SetInfo? {
        val keys = keysWithTracks(animation, selection)
        if (keys.size < 2) return null
        val t = targets(animation, keys)
        val ks = t.mapNotNull { animation.tracks[it.first]?.keyAt(it.second) }
        if (ks.isEmpty()) return null
        val ref = ks.first()
        val mixed = ks.any { it.easing != ref.easing || it.curve != ref.curve }
        return SetInfo(keys.size, keys.mapTo(HashSet()) { it.first }.size, ref.easing, ref.curve, mixed)
    }

    /** Заголовок редактора кривой для набора: число ромбов, а не дорожек. */
    fun title(keys: Int): String = "Кривая выбранных ключей ($keys)"

    /** Пояснение под заголовком: сколько слоёв затронуто; остальные ключи не меняются. */
    fun note(keys: Int, layers: Int): String =
        if (layers > 1) "Ключей: $keys на слоях: $layers - одна кривая всем, остальные ключи не меняются"
        else "Ключей: $keys - одна кривая всем, остальные ключи не меняются"

    /** Пресет плавности [type] всем дорожкам набора (своя кривая сбрасывается). */
    fun withEasing(animation: Animation, targets: List<Pair<Int, Int>>, type: EasingType): Animation {
        var next = animation
        for ((id, frame) in targets) next = next.withEasing(id, frame, type)
        return next
    }

    fun apply(animation: Animation, targets: List<Pair<Int, Int>>, data: List<Float>): Animation {
        var next = animation
        for ((id, frame) in targets) next = next.withCurve(id, frame, data)
        return next
    }
}
