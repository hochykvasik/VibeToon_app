package com.vibetoon.domain.anim

/** Только явная пауза возвращает кадр запуска; техническая остановка и конец сцены — нет. */
object PausePolicy {
    fun frameAfterStop(current: Int, start: Int, reset: Boolean, explicitPause: Boolean): Int =
        if (reset && explicitPause) start else current
}
