package com.vibetoon.ui.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.domain.project.OnionSettings
import com.vibetoon.domain.project.OnionSkin
import com.vibetoon.ui.MainViewModel
import com.vibetoon.ui.i18n.Text
import com.vibetoon.ui.motion.AlertDialog

/**
 * Спринт 37: настройки кальки (долгое нажатие на кнопку «Калька» в таймлайне). Рисунки ДО и ПОСЛЕ текущего - свои
 * глубина (0-4), непрозрачность и цвет тонировки; общие флажки «Затухание», «Тонировать цветом» и «Out of Pegs».
 * Спринт 51: шаг (каждый N-й рисунок), калька во время Play и по соседним слоям-рисункам.
 */
@Composable
fun OnionSkinDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val s by vm.onionSettings.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Калька (Onion Skin)") },
        text = {
            Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OnionSide("Рисунки до (предыдущие)", s.before, s.beforeAlpha, s.beforeColor, s.tint,
                    onDepth = { vm.setOnionSettings(s.copy(before = it)) },
                    onAlpha = { vm.setOnionSettings(s.copy(beforeAlpha = it)) },
                    onColor = { vm.setOnionSettings(s.copy(beforeColor = it)) })
                OnionSide("Рисунки после (следующие)", s.after, s.afterAlpha, s.afterColor, s.tint,
                    onDepth = { vm.setOnionSettings(s.copy(after = it)) },
                    onAlpha = { vm.setOnionSettings(s.copy(afterAlpha = it)) },
                    onColor = { vm.setOnionSettings(s.copy(afterColor = it)) })
                // спринт 51: шаг, калька при Play и по отдельным слоям-рисункам
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Шаг:", fontSize = 12.sp)
                    for (st in 1..OnionSkin.MAX_STEP) FilterChip(selected = s.step == st, onClick = { vm.setOnionSettings(s.copy(step = st)) }, label = { Text(if (st == 1) "каждый" else "через ${st - 1}") })
                }
                OnionSwitch("Калька во время воспроизведения", s.playback) { vm.setOnionSettings(s.copy(playback = it)) }
                OnionSwitch("Соседние слои-рисунки (Рисунок 1, Рисунок 2…) тоже", s.layers) { vm.setOnionSettings(s.copy(layers = it)) }
                OnionSwitch("Затухание: дальние рисунки бледнее", s.falloff) { vm.setOnionSettings(s.copy(falloff = it)) }
                OnionSwitch("Тонировать цветом (выкл. - исходные цвета рисунков)", s.tint) { vm.setOnionSettings(s.copy(tint = it)) }
                OnionSwitch("Out of Pegs: призраки на своих местах", s.outOfPegs) { vm.setOnionSettings(s.copy(outOfPegs = it)) }
                Text("Out of Pegs: соседние рисунки стоят там, где слой был на их кадрах (с ключами трансформации), и не едут вместе с текущим рисунком, пока его двигают или трансформируют. Выкл. - призраки повторяют текущее положение слоя.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Text("Работает на слое с кадрами (кнопка «+ Кадр») и на рисунках-слоях лесенкой: призраки - соседние слои той же группы со своими плашками. Настройки сохраняются в настройках приложения и в резервной копии настроек.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        },
        dismissButton = { TextButton({ vm.resetOnionSettings() }) { Text("Сбросить") } },
        confirmButton = { TextButton(onDismiss) { Text("Готово") } }
    )
}

@Composable
private fun OnionSide(title: String, depth: Int, alpha: Float, color: Int, tint: Boolean, onDepth: (Int) -> Unit, onAlpha: (Float) -> Unit, onColor: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Глубина:", fontSize = 12.sp)
            for (d in 0..OnionSkin.MAX_DEPTH) FilterChip(selected = depth == d, onClick = { onDepth(d) }, label = { Text("$d") })
        }
        Text("Прозрачность: ${Math.round(alpha * 100)}%", fontSize = 12.sp)
        Slider(value = alpha, onValueChange = onAlpha, valueRange = OnionSkin.MIN_ALPHA..OnionSkin.MAX_ALPHA, enabled = depth > 0)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Цвет:", fontSize = 12.sp)
            for (c in OnionSkin.PALETTE) {
                val picked = (c and 0xFFFFFF) == (color and 0xFFFFFF)
                Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c).copy(alpha = if (tint) 1f else 0.4f))
                    .border(if (picked) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
                    .clickable { onColor(c) })
            }
        }
    }
}

@Composable
private fun OnionSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = on, onCheckedChange = onChange)
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}
