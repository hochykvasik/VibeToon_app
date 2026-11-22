package com.vibetoon.domain.anim

/** Перенос не должен молча перезаписывать чужие ключи. */
object KeyMovePolicy {
    fun canMove(animation: Animation, selection: Set<Pair<Int, Int>>, delta: Int): Boolean {
        if (selection.isEmpty()) return false
        for ((layer, frames) in selection.groupBy({ it.first }, { it.second })) {
            val chosen = frames.toSet()
            for (id in TrackGroups.ids(layer, animation.tracks)) {
                val track = animation.tracks[id] ?: continue
                for (key in track.keys) {
                    if (key.frame !in chosen) continue
                    val dest = key.frame.toLong() + delta
                    if (dest < 0 || dest >= animation.settings.durationFrames) return false
                    if (track.keyAt(dest.toInt()) != null && dest.toInt() !in chosen) return false
                }
            }
        }
        return true
    }
    /** Групповое масштабирование не должно сливать выбранные ключи или затирать соседние. */
    fun canRemap(animation: Animation, selection: Set<Pair<Int, Int>>, destination: (Int) -> Int): Boolean {
        if (selection.isEmpty()) return false
        for ((layer, frames) in selection.groupBy({ it.first }, { it.second })) {
            val chosen = frames.toSet()
            for (id in TrackGroups.ids(layer, animation.tracks)) {
                val track = animation.tracks[id] ?: continue
                val moving = track.keys.filter { it.frame in chosen }
                val targets = HashSet<Int>()
                for (key in moving) {
                    val dest = destination(key.frame)
                    if (dest !in 0 until animation.settings.durationFrames || !targets.add(dest)) return false
                    if (track.keyAt(dest) != null && dest !in chosen) return false
                }
            }
        }
        return true
    }

}
