package com.vibetoon.ui.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.domain.anim.ClipTiming
import com.vibetoon.domain.anim.StaggerPlan
import com.vibetoon.ui.MainViewModel
import com.vibetoon.ui.i18n.Text
import com.vibetoon.ui.motion.AlertDialog

/**
 * Спринт 51: «Точное время» плашки слоя - начало, конец и длительность в секундах или кадрах (например 2с - 5с).
 * Любое поле можно править: правка начала или длительности держит длительность/начало, правка конца - начало.
 * Конец - граница (не включая): плашка 2с - 5с видна с кадра 2·fps по кадр 5·fps - 1.
 */
@Composable
fun ExactTimeDialog(vm: MainViewModel, id: Int, onDismiss: () -> Unit) {
    val fps = vm.fps()
    val dur = vm.projectDuration()
    val seconds by vm.timeInSeconds.collectAsStateWithLifecycle()
    val c0 = remember(id) { vm.clipOf(id) }
    var start by remember(id) { mutableIntStateOf(c0.start) }
    var end by remember(id) { mutableIntStateOf(c0.end) }
    var tStart by remember(id, seconds) { mutableStateOf(ClipTiming.format(start, fps, seconds)) }
    var tEnd by remember(id, seconds) { mutableStateOf(ClipTiming.format(end, fps, seconds)) }
    var tLen by remember(id, seconds) { mutableStateOf(ClipTiming.format(end - start, fps, seconds)) }
    var bad by remember { mutableStateOf(false) }
    fun apply(changed: Int) {
        val s = ClipTiming.parse(tStart, fps, seconds); val e = ClipTiming.parse(tEnd, fps, seconds); val l = ClipTiming.parse(tLen, fps, seconds)
        if (s == null || e == null || l == null) { bad = true; return }
        bad = false
        val r = ClipTiming.resolve(s, e, l, changed, dur)
        start = r[0]; end = r[1]
        if (changed != 0) tStart = ClipTiming.format(start, fps, seconds)
        if (changed != 1) tEnd = ClipTiming.format(end, fps, seconds)
        if (changed != 2) tLen = ClipTiming.format(end - start, fps, seconds)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Точное время") },
        text = {
            Column(Modifier.widthIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = seconds, onClick = { vm.setTimeInSeconds(true) }, label = { Text("Секунды") })
                    FilterChip(selected = !seconds, onClick = { vm.setTimeInSeconds(false) }, label = { Text("Кадры") })
                }
                val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                OutlinedTextField(tStart, { tStart = it; apply(0) }, label = { Text("Начало") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tEnd, { tEnd = it; apply(1) }, label = { Text("Конец") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tLen, { tLen = it; apply(2) }, label = { Text("Длительность") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.fillMaxWidth())
                Text("Кадры ${start + 1}–$end, длительность ${end - start} кадр. при $fps fps", fontSize = 12.sp)
                if (bad) Text("Введите число: 2.5, 2с, 48к или 1:05", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                Text("Суффиксы: с / s - секунды, к / f - кадры; запятая или точка. Конец - граница: плашка 2–5 с видна до кадра перед 5 с.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
        confirmButton = { TextButton(onClick = { if (!bad) { vm.setClipExact(id, start, end); onDismiss() } }, enabled = !bad) { Text("Применить") } }
    )
}

/** Спринт 51: настройки «Лесенки» (долгое нажатие на кнопку): встык с промежутком или каскад с шагом, направление. */
@Composable
fun StaggerDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val s0 by vm.staggerSettings.collectAsStateWithLifecycle()
    var chain by remember { mutableStateOf(s0.chain) }
    var step by remember { mutableIntStateOf(s0.step) }
    var reverse by remember { mutableStateOf(s0.reverse) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Лесенка") },
        text = {
            Column(Modifier.widthIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = chain, onClick = { chain = true; step = 0 }, label = { Text("Встык") })
                    FilterChip(selected = !chain, onClick = { chain = false; step = maxOf(1, step) }, label = { Text("Каскад со сдвигом") })
                }
                Text(if (chain) "Промежуток между плашками: $step кадр." else "Сдвиг начала каждой следующей: $step кадр.", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val options = if (chain) listOf(-2, -1, 0, 1, 2, 4, 8, 12) else listOf(1, 2, 3, 4, 6, 8, 12, 24)
                    for (o in options) FilterChip(selected = step == o, onClick = { step = o }, label = { Text("$o") })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = reverse, onCheckedChange = { reverse = it })
                    Text(if (reverse) "Направление: снизу вверх (с последнего выбранного)" else "Направление: сверху вниз (по порядку выбора)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
                Text("Встык: следующая плашка начинается там, где кончилась предыдущая (+ промежуток, минус - нахлёст). Каскад: начала сдвигаются на шаг, длины не меняются.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
        confirmButton = {
            TextButton(onClick = {
                val st = StaggerPlan.Settings(step, chain, reverse)
                vm.setStaggerSettings(st); vm.staggerWith(st); onDismiss()
            }) { Text("Применить") }
        }
    )
}

/** Спринт 51: дубликат слоя с ключами - с анимацией (все ключи и деформеры) или чистый слой без ключей. */
@Composable
fun DuplicateAnimationDialog(vm: MainViewModel) {
    val ask by vm.duplicateAsk.collectAsStateWithLifecycle()
    if (ask == null) return
    AlertDialog(
        onDismissRequest = { vm.answerDuplicate(null) },
        title = { Text("Дублировать слой") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("У слоя есть ключи анимации. Как сделать копию?", fontSize = 13.sp)
                Text("С анимацией - копия со всеми ключами (трансформация, деформеры, эффекты). Без анимации - чистый слой без ключей, стоит как на текущем кадре.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        },
        dismissButton = {
            Row {
                TextButton({ vm.answerDuplicate(null) }) { Text("Отмена") }
                TextButton({ vm.answerDuplicate(false) }) { Text("Без анимации") }
            }
        },
        confirmButton = { TextButton({ vm.answerDuplicate(true) }) { Text("С анимацией") } }
    )
}
