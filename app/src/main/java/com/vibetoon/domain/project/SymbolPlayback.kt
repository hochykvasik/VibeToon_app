package com.vibetoon.domain.project

/**
 * Спринт 37: режим проигрывания экземпляра символа без ключей времени (как в ToonSquid: Hold / Loop / Play Once).
 *  - [HOLD] - стоит первый кадр символа (по умолчанию);
 *  - [LOOP] - внутренняя анимация по кругу (кадр сцены по модулю длительности символа, как раньше);
 *  - [ONCE] - внутренняя анимация проигрывается один раз с начала плашки экземпляра на таймлайне и замирает
 *    на последнем кадре символа (до начала плашки - первый кадр).
 * Ключи времени (Time Remapping) главнее режима. Одно правило для холста, Play и всех видов экспорта.
 * В проекте режим хранится двумя флагами: старый «sl» (Loop) и новый «so» (Play Once) - старые проекты не меняются,
 * а старая версия приложения откроет Play Once как Hold. Чистые функции - JVM-тесты.
 */
object SymbolPlayback {
    const val HOLD = 0
    const val LOOP = 1
    const val ONCE = 2

    val ALL = listOf(HOLD, LOOP, ONCE)

    /** Режим по флагам экземпляра; оба флага - Play Once (новее и явнее). */
    fun mode(loop: Boolean, once: Boolean): Int = if (once) ONCE else if (loop) LOOP else HOLD

    /** Флаги экземпляра (symLoop, symOnce) для режима [mode]; неизвестный режим - Hold. */
    fun flags(mode: Int): Pair<Boolean, Boolean> = when (mode) { LOOP -> true to false; ONCE -> false to true; else -> false to false }

    /**
     * Внутренний кадр без ключей времени. [start] - кадр сцены, с которого начинается плашка экземпляра (Play Once
     * идёт от неё); [animated] - участники символа действительно анимированы (иначе все кадры одинаковы - кадр 0).
     */
    fun localFrame(mode: Int, global: Int, duration: Int, start: Int, animated: Boolean): Int {
        if (!animated || duration <= 1) return 0
        return when (mode) {
            LOOP -> SymbolMath.localFrame(global, duration)
            ONCE -> (global - start).coerceIn(0, duration - 1)
            else -> 0
        }
    }

    /** Play Once уже закончился: символ держит последний кадр. */
    fun finished(mode: Int, global: Int, duration: Int, start: Int): Boolean = mode == ONCE && global - start >= duration - 1

    /** Кадр экземпляра: ключи времени главнее, без ключей - по режиму. */
    fun instanceFrame(keys: List<TimeKey>, global: Int, duration: Int, animated: Boolean, mode: Int, start: Int): Int =
        SymbolMath.timeAt(keys, global)?.coerceIn(0, maxOf(0, duration - 1)) ?: localFrame(mode, global, duration, start, animated)

    /** Экземпляру нужны все внутренние кадры символа (для прогрева форм и экспорта): цикл или Play Once анимированного символа. */
    fun needsAllFrames(mode: Int, hasKeys: Boolean, animated: Boolean): Boolean = !hasKeys && animated && mode != HOLD

    /** Русский ключ подписи режима (переводится словарём). */
    fun label(mode: Int): String = when (mode) { LOOP -> "Цикл"; ONCE -> "Один раз"; else -> "Hold (первый кадр)" }
}
