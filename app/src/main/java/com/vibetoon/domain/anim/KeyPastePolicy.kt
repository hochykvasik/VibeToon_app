package com.vibetoon.domain.anim

/** A paste must fit completely and never overwrite existing or duplicate track/frame pairs. */
object KeyPastePolicy {
    data class Destination(val trackId: Int, val frame: Long)
    fun canPaste(animation: Animation, destinations: List<Destination>): Boolean {
        if (destinations.isEmpty()) return false
        val seen = HashSet<Pair<Int, Int>>()
        for (d in destinations) {
            if (d.frame < 0L || d.frame >= animation.settings.durationFrames.toLong()) return false
            val f = d.frame.toInt()
            if (!seen.add(d.trackId to f) || animation.tracks[d.trackId]?.keyAt(f) != null) return false
        }
        return true
    }
}
