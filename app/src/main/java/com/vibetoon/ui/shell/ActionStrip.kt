package com.vibetoon.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibetoon.domain.ui.ActionBarLayout

/**
 * Спринт 61: полоса кнопок верхней строки с горизонтальной прокруткой. Кнопки не сжимаются: внутри прокрутки
 * каждая меряется своим полным размером (48 dp), а полоса показывает столько, сколько помещается. У края, за
 * которым есть ещё кнопки, - лёгкое затемнение-подсказка. Режим левши ([mirrored]): кнопки в обратном порядке,
 * полоса прижата к левому краю и открывается с конца, так что первая кнопка - у левого края.
 */
@Composable
fun ActionStrip(
    width: Dp,
    mirrored: Boolean,
    items: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    fade: Color = MaterialTheme.colorScheme.surface
) {
    val scroll = rememberScrollState()
    val ordered = ActionBarLayout.order(items, mirrored)
    Box(modifier.width(width).fillMaxHeight(), contentAlignment = if (mirrored) Alignment.CenterStart else Alignment.CenterEnd) {
        Row(
            Modifier.horizontalScroll(scroll, reverseScrolling = mirrored),
            verticalAlignment = Alignment.CenterVertically
        ) { for (item in ordered) item() }
        // прокрутка с reverseScrolling считается от правого края: подсказки - по видимым краям
        val (a, b) = ActionBarLayout.edgeHints(scroll.value, scroll.maxValue)
        val (left, right) = if (mirrored) b to a else a to b
        if (left) Box(Modifier.align(Alignment.CenterStart).width(14.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(fade, Color.Transparent))))
        if (right) Box(Modifier.align(Alignment.CenterEnd).width(14.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color.Transparent, fade))))
    }
}
