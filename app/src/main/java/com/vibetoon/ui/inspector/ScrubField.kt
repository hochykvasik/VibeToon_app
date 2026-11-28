package com.vibetoon.ui.inspector

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var start by remember { mutableFloatStateOf(value) }
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            // спринт 62: вертикальная прокрутка списка, начатая на строке, значение больше не сдвигает
            .scrubGesture(com.vibetoon.domain.ui.ScrubGesture.Mode.DIRECT, range, onHold = { on -> if (on) start = cur }, onTap = { _, _ -> },
                onDrag = { total, w -> out((start + total / w.coerceAtLeast(1f) * span).coerceIn(range.start, range.endInclusive)) })
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
 * Спринт 62: общий жест полей «потяни». Решение по [ScrubGesture.decide]: вертикальный сдвиг - прокрутка списка, на
 * телефоне ([ScrubGesture.Mode.HOLD]) быстрый свайп вбок - прокрутка строки инструментов, регулировка - после
 * удержания; короткое касание - [onTap] с координатой. Пока идёт регулировка, события поглощаются (строка не едет);
 * [onHold] - начало/конец регулировки (кружок размера кисти на холсте), конец приходит и при отмене жеста.
 */
internal fun Modifier.scrubGesture(
    mode: com.vibetoon.domain.ui.ScrubGesture.Mode,
    key: Any?,
    onHold: (Boolean) -> Unit,
    onTap: (x: Float, width: Float) -> Unit,
    onDrag: (total: Float, width: Float) -> Unit
): Modifier = pointerInput(mode, key) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val t0 = down.uptimeMillis
        var decision = com.vibetoon.domain.ui.ScrubGesture.Decision.PENDING
        var last = down.position
        var lastTime = t0
        while (decision == com.vibetoon.domain.ui.ScrubGesture.Decision.PENDING) {
            val wait = (com.vibetoon.domain.ui.ScrubGesture.HOLD_MS - (lastTime - t0)).coerceAtLeast(1L)
            val ev = if (mode == com.vibetoon.domain.ui.ScrubGesture.Mode.HOLD) withTimeoutOrNull(wait) { awaitPointerEvent() } else awaitPointerEvent()
            if (ev == null) {
                lastTime = t0 + com.vibetoon.domain.ui.ScrubGesture.HOLD_MS
                decision = com.vibetoon.domain.ui.ScrubGesture.decide(mode, last.x - down.position.x, last.y - down.position.y, lastTime - t0, false, slop)
                continue
            }
            val c = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            // другой обработчик (прокрутка, второй палец) уже забрал жест - поле в стороне
            if (c.isConsumed || ev.changes.count { it.pressed } > 1) return@awaitEachGesture
            last = c.position; lastTime = c.uptimeMillis
            decision = com.vibetoon.domain.ui.ScrubGesture.decide(mode, last.x - down.position.x, last.y - down.position.y, lastTime - t0, !c.pressed, slop)
        }
        when (decision) {
            com.vibetoon.domain.ui.ScrubGesture.Decision.TAP -> onTap(down.position.x, size.width.toFloat())
            com.vibetoon.domain.ui.ScrubGesture.Decision.SCRUB -> {
                onHold(true)
                try {
                    var total = 0f
                    var prev = last
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) { c.consume(); break }
                        total += c.position.x - prev.x
                        prev = c.position
                        c.consume()
                        onDrag(total, size.width.toFloat())
                    }
                } finally { onHold(false) }
            }
            else -> Unit   // прокрутка: события не трогаем, их берёт строка или список
        }
    }
}

/**
 * Компактное поле «потяни»: подпись и число, горизонтальный свайп по нему меняет значение.
 * [log] = true - множитель (для радиусов: одинаково удобно от 30 до 2000), иначе линейно на [perPx] за пиксель.
 */
@Composable
fun ScrubBox(label: String, value: Float, min: Float, max: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier, log: Boolean = false, perPx: Float = 1f,
             onHold: ((Boolean) -> Unit)? = null, onStep: (() -> Unit)? = null) {
    val cur by rememberUpdatedState(value)
    val out by rememberUpdatedState(onValue)
    val hold by rememberUpdatedState(onHold)
    val step by rememberUpdatedState(onStep)
    // спринт 62: на телефоне строка инструментов прокручивается - свайп по полю листает её, регулировка - после удержания
    val mode = com.vibetoon.domain.ui.ScrubGesture.modeFor(com.vibetoon.ui.shell.LocalPhone.current)
    var holding by remember { mutableStateOf(false) }
    var start by remember { mutableFloatStateOf(value) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (holding) accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(androidx.compose.foundation.BorderStroke(if (holding) 2.dp else 1.dp, if (holding) accent else MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp))
            .scrubGesture(
                mode, listOf(min, max, log, perPx),
                onHold = { on ->
                    holding = on
                    if (on) { start = cur; if (mode == com.vibetoon.domain.ui.ScrubGesture.Mode.HOLD) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
                    hold?.invoke(on)
                },
                onTap = { x, w ->
                    val dir = com.vibetoon.domain.ui.ScrubGesture.tapStep(x, w)
                    if (dir != 0) {
                        val v = com.vibetoon.domain.ui.ScrubGesture.stepValue(cur, dir, min, max, log)
                        if (v != cur) out(v)
                        step?.invoke()
                    }
                },
                onDrag = { total, _ -> out(com.vibetoon.domain.ui.ScrubGesture.dragValue(start, total, min, max, log, perPx)) }
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.ChevronLeft, com.vibetoon.ui.i18n.L("Меньше"), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text("${com.vibetoon.ui.i18n.L(label)} ${value.toInt()}", fontSize = 12.sp, maxLines = 1, softWrap = false)
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.ChevronRight, com.vibetoon.ui.i18n.L("Больше"), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}
