package com.vibetoon.domain.input

/**
 * Спринт 54: ручка ≡ перетаскивания слоёв на таймлайне. Раньше нажатие на ручку забиралось сразу (зона 36 dp на всю
 * высоту строки), и обычная прокрутка таймлайна, начатая с ручки, переставляла слои. Теперь перетаскивание начинается
 * только после короткого удержания пальца на месте ([HOLD_MS]); сдвиг дальше порога касания до этого - прокрутка
 * (жест отдаётся списку), отпускание раньше - ничего. Чистая функция - unit-тест.
 */
object HandleDragGate {
    const val HOLD_MS = 220L
    /** Ширина зоны ручки и отступ от имени/миниатюры (dp). */
    const val HANDLE_DP = 28
    const val GAP_DP = 12

    enum class Decision { WAIT, DRAG, SCROLL, RELEASED }

    /**
     * [elapsedMs] - с момента касания, [moved] - смещение пальца (px), [slop] - порог касания (px), [pressed] - палец
     * ещё на экране, [consumed] - событие уже забрал кто-то другой (прокрутка).
     */
    fun decide(elapsedMs: Long, moved: Float, slop: Float, pressed: Boolean = true, consumed: Boolean = false): Decision = when {
        consumed -> Decision.SCROLL
        !pressed -> Decision.RELEASED
        moved > slop -> Decision.SCROLL
        elapsedMs >= HOLD_MS -> Decision.DRAG
        else -> Decision.WAIT
    }
}
