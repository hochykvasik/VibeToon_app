package com.vibetoon.ui.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import com.vibetoon.domain.input.KeyboardLift
import kotlin.math.roundToInt

/**
 * Спринт 49: безопасная зона редактора БЕЗ клавиатуры - системные панели и вырез камеры. Корень редактора отступает
 * только от них, поэтому открытие/закрытие клавиатуры не меняет размер холста, таймлайна и панелей.
 */
val WindowInsets.Companion.editorSafe: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * Спринт 49: панель поднимается над клавиатурой (поверх холста), пока фокус ввода внутри неё; раскладка не меняется -
 * холст остаётся на месте. Положение панели меряется до сдвига (без обратной связи).
 */
fun Modifier.liftAboveKeyboard(): Modifier = composed {
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    val view = LocalView.current
    var focused by remember { mutableStateOf(false) }
    var bottom by remember { mutableIntStateOf(0) }
    val lift = KeyboardLift.lift(focused, ime, view.height - bottom)
    this
        .onFocusChanged { focused = it.hasFocus }
        .onGloballyPositioned { c -> bottom = (c.positionInWindow().y + c.size.height).roundToInt() }
        .offset { IntOffset(0, -lift) }
}

/**
 * Спринт 49: панель во всю высоту (инспектор) получает отступ снизу на высоту клавиатуры над её низом: поля
 * прокручиваются над клавиатурой, а холст рядом не сжимается.
 */
fun Modifier.keyboardInsetPanel(): Modifier = composed {
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val view = LocalView.current
    var bottom by remember { mutableIntStateOf(0) }
    val inset = KeyboardLift.panelInset(ime, view.height - bottom)
    this
        .onGloballyPositioned { c -> bottom = (c.positionInWindow().y + c.size.height).roundToInt() }
        .padding(bottom = with(density) { inset.toDp() })
}
