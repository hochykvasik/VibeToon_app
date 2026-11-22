package com.vibetoon.domain.project

/** Проверка и расчёт разрезания до изменения проекта: все клипы или ни один. */
object ClipSplit {
    fun split(clips: List<Clip>, frame: Int): List<Pair<Clip, Clip>>? {
        if (clips.isEmpty() || clips.any { frame <= it.start || frame >= it.end }) return null
        return clips.map { clip ->
            val left = frame - clip.start
            val right = clip.end - frame
            val firstOffset = if (clip.reverse) clip.offset + Math.round(right * clip.speed) else clip.offset
            val secondOffset = if (clip.reverse) clip.offset else clip.offset + Math.round(left * clip.speed)
            clip.copy(end = frame, offset = firstOffset) to clip.copy(start = frame, offset = secondOffset)
        }
    }
}
