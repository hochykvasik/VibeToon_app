package com.vibetoon.domain.project

/** Ключ громкости аудио-слоя: на кадре проекта [frame] громкость [value] (0..1). */
data class VolKey(val frame: Int, val value: Float)

/** Громкость по ключам: линейная интерполяция между ключами, до первого и после последнего значение держится; без ключей - 1. */
object VolumeMath {
    fun at(keys: List<VolKey>, frame: Double): Float {
        if (keys.isEmpty()) return 1f
        if (frame <= keys.first().frame) return keys.first().value
        if (frame >= keys.last().frame) return keys.last().value
        var i = 0
        while (i + 1 < keys.size && keys[i + 1].frame <= frame) i++
        val a = keys[i]; val b = keys[i + 1]
        val t = ((frame - a.frame) / (b.frame - a.frame)).toFloat()
        return a.value + (b.value - a.value) * t
    }

    fun withKey(keys: List<VolKey>, k: VolKey): List<VolKey> = (keys.filter { it.frame != k.frame } + k.copy(value = k.value.coerceIn(0f, 1f))).sortedBy { it.frame }
    fun withoutKey(keys: List<VolKey>, frame: Int): List<VolKey> = keys.filter { it.frame != frame }
}
