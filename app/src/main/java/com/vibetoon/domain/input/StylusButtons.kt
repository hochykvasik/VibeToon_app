package com.vibetoon.domain.input

/**
 * Спринт 44: кнопка на корпусе пера (barrel button) и начало штриха пером. Чистые функции - Sprint44Test.
 *
 * Android сообщает кнопку пера в MotionEvent.getButtonState(): BUTTON_STYLUS_PRIMARY / BUTTON_STYLUS_SECONDARY;
 * часть планшетов (Wacom, некоторые драйверы) присылает для пера BUTTON_SECONDARY / BUTTON_TERTIARY, как у мыши.
 * Для пальца и мыши кнопки не считаются кнопками пера (правая кнопка мыши не превращает кисть в ластик).
 *
 * Действие кнопки (настройка `stylus_button`):
 *  - [Action.OFF] - кнопка не влияет, штрих рисуется как обычно;
 *  - [Action.ERASER] - штрих с зажатой кнопкой стирает (как обратный конец пера), после штриха снова кисть;
 *  - [Action.EYEDROPPER] - касание с зажатой кнопкой берёт цвет с холста в кисть, штрих не рисуется.
 * Решение принимается в начале штриха: нажатие кнопки посреди линии её не обрывает и не меняет инструмент.
 */
object StylusButtons {
    const val BUTTON_SECONDARY = 0x02
    const val BUTTON_TERTIARY = 0x04
    const val BUTTON_STYLUS_PRIMARY = 0x20
    const val BUTTON_STYLUS_SECONDARY = 0x40
    const val PREF = "stylus_button"

    enum class Action(val id: Int, val ru: String) {
        OFF(0, "Не использовать"), ERASER(1, "Ластик, пока зажата"), EYEDROPPER(2, "Пипетка");
        companion object { fun of(id: Int): Action = entries.firstOrNull { it.id == id } ?: DEFAULT }
    }

    /** По умолчанию кнопка пера - ластик, пока она зажата (как в большинстве программ рисования). */
    val DEFAULT = Action.ERASER

    /** Зажата ли кнопка пера в [buttonState]; [penPointer] - указатель TOOL_TYPE_STYLUS / TOOL_TYPE_ERASER. */
    fun pressed(buttonState: Int, penPointer: Boolean): Boolean =
        penPointer && (buttonState and (BUTTON_STYLUS_PRIMARY or BUTTON_STYLUS_SECONDARY or BUTTON_SECONDARY or BUTTON_TERTIARY)) != 0

    /** Что делает касание пером. */
    enum class Start { DRAW, ERASE, PICK_COLOR }

    /**
     * Начало штриха пером: обратный конец ([eraserEnd], если включён [eraserEndOn]) стирает; зажатая кнопка [barrel]
     * делает то, что выбрано в [action]. Обратный конец важнее кнопки: им всегда стирают.
     */
    fun start(eraserEnd: Boolean, eraserEndOn: Boolean, barrel: Boolean, action: Action): Start = when {
        eraserEnd && eraserEndOn -> Start.ERASE
        barrel && action == Action.ERASER -> Start.ERASE
        barrel && action == Action.EYEDROPPER -> Start.PICK_COLOR
        else -> Start.DRAW
    }
}
