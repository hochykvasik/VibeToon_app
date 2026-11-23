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

    fun apply(animation: Animation, targets: List<Pair<Int, Int>>, data: List<Float>): Animation {
        var next = animation
        for ((id, frame) in targets) next = next.withCurve(id, frame, data)
        return next
    }
}
