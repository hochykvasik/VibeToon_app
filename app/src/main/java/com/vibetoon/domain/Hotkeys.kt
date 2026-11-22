package com.vibetoon.domain

/** Горячие клавиши физической клавиатуры: чистая раскладка (коды Android KeyEvent), без зависимости от Android. */
enum class HotkeyAction { UNDO, REDO, PLAY_PAUSE, BRUSH, ERASER, SELECT, BRUSH_SMALLER, BRUSH_BIGGER, DELETE }

object Hotkeys {
    const val KEY_Z = 54; const val KEY_Y = 53; const val KEY_SPACE = 62; const val KEY_B = 30; const val KEY_E = 33
    const val KEY_V = 50; const val KEY_LBRACKET = 71; const val KEY_RBRACKET = 72; const val KEY_DEL = 67; const val KEY_FWD_DEL = 112

    /** Клавиши по умолчанию: Undo и Redo работают вместе с Ctrl (Redo - ещё и Ctrl+Shift+клавиша Undo), остальные - сами по себе. */
    val DEFAULTS: Map<HotkeyAction, Int> = mapOf(
        HotkeyAction.UNDO to KEY_Z, HotkeyAction.REDO to KEY_Y, HotkeyAction.PLAY_PAUSE to KEY_SPACE, HotkeyAction.BRUSH to KEY_B,
        HotkeyAction.ERASER to KEY_E, HotkeyAction.SELECT to KEY_V, HotkeyAction.BRUSH_SMALLER to KEY_LBRACKET,
        HotkeyAction.BRUSH_BIGGER to KEY_RBRACKET, HotkeyAction.DELETE to KEY_DEL
    )

    /** Действие по нажатию с учётом пользовательской раскладки [custom] (действие -> код клавиши; чего нет, берётся по умолчанию). */
    fun mapCustom(keyCode: Int, ctrl: Boolean, shift: Boolean, custom: Map<HotkeyAction, Int>): HotkeyAction? {
        val keys = DEFAULTS + custom
        if (ctrl) {
            if (keyCode == keys.getValue(HotkeyAction.UNDO)) return if (shift) HotkeyAction.REDO else HotkeyAction.UNDO
            if (keyCode == keys.getValue(HotkeyAction.REDO)) return HotkeyAction.REDO
            return null
        }
        for (a in HotkeyAction.values()) {
            if (a == HotkeyAction.UNDO || a == HotkeyAction.REDO) continue
            if (keyCode == keys.getValue(a)) return a
        }
        if (keyCode == KEY_FWD_DEL && keys.getValue(HotkeyAction.DELETE) == KEY_DEL) return HotkeyAction.DELETE
        return null
    }

    fun map(keyCode: Int, ctrl: Boolean, shift: Boolean): HotkeyAction? = when {
        ctrl && keyCode == KEY_Z -> if (shift) HotkeyAction.REDO else HotkeyAction.UNDO
        ctrl && keyCode == KEY_Y -> HotkeyAction.REDO
        ctrl -> null
        keyCode == KEY_SPACE -> HotkeyAction.PLAY_PAUSE
        keyCode == KEY_B -> HotkeyAction.BRUSH
        keyCode == KEY_E -> HotkeyAction.ERASER
        keyCode == KEY_V -> HotkeyAction.SELECT
        keyCode == KEY_LBRACKET -> HotkeyAction.BRUSH_SMALLER
        keyCode == KEY_RBRACKET -> HotkeyAction.BRUSH_BIGGER
        keyCode == KEY_DEL || keyCode == KEY_FWD_DEL -> HotkeyAction.DELETE
        else -> null
    }

    /** Размер кисти шагом в ~15% (размер логарифмический), в пределах 1..1500. */
    fun resize(size: Float, bigger: Boolean): Float = (if (bigger) size * 1.15f + 0.5f else size / 1.15f - 0.5f).coerceIn(1f, 1500f)
}
