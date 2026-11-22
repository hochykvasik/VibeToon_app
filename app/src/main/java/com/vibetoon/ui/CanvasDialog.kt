package com.vibetoon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetoon.domain.project.CanvasMap
import com.vibetoon.ui.i18n.Text

/** Resize (новый размер холста) и Crop (рамка x, y, ширина, высота) уже созданного проекта. */
@Composable
fun CanvasDialog(vm: MainViewModel, crop: Boolean, onDismiss: () -> Unit) {
    val (cw, ch) = vm.canvasSizeNow()
    var w by remember { mutableStateOf(cw.toString()) }
    var h by remember { mutableStateOf(ch.toString()) }
    var x by remember { mutableStateOf("0") }
    var y by remember { mutableStateOf("0") }
    var keep by remember { mutableStateOf(true) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (crop) "Обрезать холст (Crop)" else "Изменить размер холста (Resize)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сейчас: $cw × $ch px", fontSize = 12.sp)
                if (crop) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(x, { x = it.filter(Char::isDigit) }, label = { Text("X") }, singleLine = true, keyboardOptions = num, modifier = Modifier.width(110.dp))
                    OutlinedTextField(y, { y = it.filter(Char::isDigit) }, label = { Text("Y") }, singleLine = true, keyboardOptions = num, modifier = Modifier.width(110.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { v -> w = v.filter(Char::isDigit); if (!crop && keep) w.toIntOrNull()?.let { h = CanvasMap.keepRatio(cw, ch, it).toString() } }, label = { Text("Ширина") }, singleLine = true, keyboardOptions = num, modifier = Modifier.width(110.dp))
                    OutlinedTextField(h, { v -> h = v.filter(Char::isDigit); if (!crop && keep) h.toIntOrNull()?.let { w = Math.round(it.toFloat() * cw / ch).toString() } }, label = { Text("Высота") }, singleLine = true, keyboardOptions = num, modifier = Modifier.width(110.dp))
                }
                if (!crop) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = keep, onCheckedChange = { keep = it })
                    Text("Сохранять пропорции", fontSize = 13.sp)
                }
                Text("Допустимо от 64 до 4096 px. Действие нельзя отменить через Undo.", fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton({
                val nw = w.toIntOrNull() ?: cw; val nh = h.toIntOrNull() ?: ch
                if (crop) vm.cropCanvas(x.toIntOrNull() ?: 0, y.toIntOrNull() ?: 0, nw, nh) else vm.resizeCanvas(nw, nh)
                onDismiss()
            }) { Text("Применить") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } }
    )
}
