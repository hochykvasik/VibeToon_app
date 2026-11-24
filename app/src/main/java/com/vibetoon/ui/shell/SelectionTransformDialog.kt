package com.vibetoon.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
import com.vibetoon.domain.raster.TransformBox
import com.vibetoon.ui.i18n.Text

/** Числа для рамки трансформации: применяются поверх текущей рамки относительно опорной точки. */
internal fun parseTransformNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/** Sprint 33: числовой ввод трансформации выделения (масштаб, поворот, наклон, сдвиг, отражение). */
@Composable
fun SelectionTransformDialog(onDismiss: () -> Unit, onApply: (TransformBox.Numeric) -> Unit) {
    var scaleX by remember { mutableStateOf("100") }
    var scaleY by remember { mutableStateOf("100") }
    var rotation by remember { mutableStateOf("0") }
    var skewX by remember { mutableStateOf("0") }
    var skewY by remember { mutableStateOf("0") }
    var dx by remember { mutableStateOf("0") }
    var dy by remember { mutableStateOf("0") }
    var flipX by remember { mutableStateOf(false) }
    var flipY by remember { mutableStateOf(false) }
    val sx = parseTransformNumber(scaleX); val sy = parseTransformNumber(scaleY)
    val r = parseTransformNumber(rotation); val kx = parseTransformNumber(skewX); val ky = parseTransformNumber(skewY)
    val mx = parseTransformNumber(dx); val my = parseTransformNumber(dy)
    val valid = sx != null && sy != null && r != null && kx != null && ky != null && mx != null && my != null &&
        sx in 1.0..800.0 && sy in 1.0..800.0 && kotlin.math.abs(kx) < 85.0 && kotlin.math.abs(ky) < 85.0
    com.vibetoon.ui.motion.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Трансформация числами") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Значения применяются поверх рамки относительно опорной точки", fontSize = 12.sp)
                TransformField("Ширина, %", scaleX) { scaleX = it }
                TransformField("Высота, %", scaleY) { scaleY = it }
                TransformField("Поворот, °", rotation) { rotation = it }
                TransformField("Наклон по X, °", skewX) { skewX = it }
                TransformField("Наклон по Y, °", skewY) { skewY = it }
                TransformField("Сдвиг по X, px", dx) { dx = it }
                TransformField("Сдвиг по Y, px", dy) { dy = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = flipX, onCheckedChange = { flipX = it }); Text("Отразить по горизонтали", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = flipY, onCheckedChange = { flipY = it }); Text("Отразить по вертикали", fontSize = 13.sp)
                }
                if (!valid) Text("Ширина и высота 1–800 %, наклон меньше 85°", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (valid) onApply(TransformBox.Numeric(sx!! / 100.0, sy!! / 100.0, r!!, kx!!, ky!!, mx!!, my!!, flipX, flipY))
            }) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun TransformField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { onChange(it.take(12)) }, singleLine = true, label = { Text(label) },
        isError = parseTransformNumber(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

/**
 * Спринт 36: наклон целого слоя/группы в градусах (X - от вертикали, Y - от горизонтали). Поля заполнены текущим
 * наклоном; «Применить» пишет углы перспективы (ключ на кадре при включённой анимации).
 */
@Composable
fun LayerSkewDialog(current: Pair<Float, Float>, onDismiss: () -> Unit, onApply: (Float, Float) -> Unit) {
    fun fmt(v: Float) = (kotlin.math.round(v * 10f) / 10f).let { if (it == kotlin.math.floor(it)) it.toInt().toString() else it.toString() }
    var skewX by remember { mutableStateOf(fmt(current.first)) }
    var skewY by remember { mutableStateOf(fmt(current.second)) }
    val kx = parseTransformNumber(skewX); val ky = parseTransformNumber(skewY)
    val valid = kx != null && ky != null && kotlin.math.abs(kx) <= 75.0 && kotlin.math.abs(ky) <= 75.0
    com.vibetoon.ui.motion.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Наклон слоя") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Наклон хранится в углах перспективы: искажение углов и ключи анимации сохраняются", fontSize = 12.sp)
                TransformField("Наклон по X, °", skewX) { skewX = it }
                TransformField("Наклон по Y, °", skewY) { skewY = it }
                if (!valid) Text("Наклон от −75° до 75°", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { if (valid) onApply(kx!!.toFloat(), ky!!.toFloat()) }) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
