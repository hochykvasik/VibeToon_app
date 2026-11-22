package com.vibetoon.ui.inspector

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.vibetoon.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp

/**
 * Регулятор «потяни по строке» (как в Alight Motion): палец кладётся в ЛЮБОЕ место строки и ведётся влево/вправо,
 * значение меняется относительно того, что было в момент касания. Ширина строки соответствует всему диапазону,
 * поэтому не нужно точно попадать в ползунок; тонкая полоса показывает положение значения.
 */
@Composable
fun ScrubSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onValue: (Float) -> Unit) {
    val cur by rememberUpdatedState(value)
    val out by rememberUpdatedState(onValue)
    val span = range.endInclusive - range.start
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(range) {
                var start = 0f
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start = cur; total = 0f },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        total += dx
                        out((start + total / size.width * span).coerceIn(range.start, range.endInclusive))
                    }
                )
            }
            .padding(vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.ChevronLeft, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/**
 * Компактное поле «потяни»: подпись и число, горизонтальный свайп по нему меняет значение.
 * [log] = true - множитель (для радиусов: одинаково удобно от 30 до 2000), иначе линейно на [perPx] за пиксель.
 */
@Composable
fun ScrubBox(label: String, value: Float, min: Float, max: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier, log: Boolean = false, perPx: Float = 1f) {
    val cur by rememberUpdatedState(value)
    val out by rememberUpdatedState(onValue)
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp))
            .pointerInput(min, max, log, perPx) {
                var start = 0f
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start = cur; total = 0f },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        total += dx
                        val v = if (log) start * exp(total * 0.008f) else start + total * perPx
                        out(v.coerceIn(min, max))
                    }
                )
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.ChevronLeft, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text("${com.vibetoon.ui.i18n.L(label)} ${value.toInt()}", fontSize = 12.sp, maxLines = 1, softWrap = false)
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.ChevronRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}
