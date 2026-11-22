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

    fun apply(animation: Animation, targets: List<Pair<Int, Int>>, data: List<Float>): Animation {
        var next = animation
        for ((id, frame) in targets) next = next.withCurve(id, frame, data)
        return next
    }
}
