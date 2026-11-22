package com.vibetoon.ui.inspector

import androidx.compose.material.icons.filled.Colorize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.vibetoon.domain.paint.ColorPalette
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Выбор цвета (низкий ландшафт): слева круг HSV (угол - оттенок, радиус - насыщенность; яркость
 * затемняет весь круг), справа ползунок яркости, превью [старый | новый], HEX, палитра последних
 * цветов с кнопкой «+» и пипетка. Каждое изменение сразу уходит в [onColor] (цвет виден на холсте).
 */
@Composable
fun ColorPickerDialog(
    initial: Long,
    onColor: (Long) -> Unit,
    onEyedropper: () -> Unit,
    onDismiss: () -> Unit
) {
    val hsv = remember {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toInt(), a)
        a
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var hex by remember { mutableStateOf(hexOf(initial)) }
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("vt_colors", android.content.Context.MODE_PRIVATE)
    var palette by remember { mutableStateOf(ColorPalette.decode(prefs.getString("recent", null))) }
    fun saveColor(c: Long) {
        palette = ColorPalette.add(palette, c)
        prefs.edit().putString("recent", ColorPalette.encode(palette)).apply()
    }

    fun current(): Long = (Color.hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f)).toArgb().toLong() and 0xFFFFFFFFL)
    fun push() {
        val c = current()
        hex = hexOf(c)
        onColor(c)
    }
    fun close() { saveColor(current()); onDismiss() }

    AlertDialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 620.dp),
        text = {
            Row(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(
                    Modifier
                        .size(150.dp)
                        .pointerInput(Unit) {
                            fun pick(o: Offset) {
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val dx = o.x - c.x
                                val dy = o.y - c.y
                                val r = min(size.width, size.height) / 2f
                                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                if (deg < 0) deg += 360f
                                hue = deg
                                if (value < 0.05f) value = 1f   // чёрный нельзя "покрасить" без яркости
                                sat = (sqrt(dx * dx + dy * dy) / r).coerceIn(0f, 1f)
                                push()
                            }
                            detectTapGestures { pick(it) }
                        }
                        .pointerInput(Unit) {
                            fun pick(o: Offset) {
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val dx = o.x - c.x
                                val dy = o.y - c.y
                                val r = min(size.width, size.height) / 2f
                                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                if (deg < 0) deg += 360f
                                hue = deg
                                if (value < 0.05f) value = 1f
                                sat = (sqrt(dx * dx + dy * dy) / r).coerceIn(0f, 1f)
                                push()
                            }
                            detectDragGestures(onDragStart = { pick(it) }) { change, _ -> change.consume(); pick(change.position) }
                        }
                ) {
                    val c = center
                    val r = size.minDimension / 2f
                    val hues = (0..6).map { Color.hsv((it * 60f) % 360f, 1f, 1f) }
                    drawCircle(Brush.sweepGradient(hues, c), r, c)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), c, r), r, c)
                    drawCircle(Color.Black.copy(alpha = 1f - value.coerceIn(0f, 1f)), r, c)   // яркость (V) затемняет весь круг
                    val rad = Math.toRadians(hue.toDouble())
                    val p = Offset(c.x + (cos(rad) * sat * r).toFloat(), c.y + (sin(rad) * sat * r).toFloat())
                    drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.Black, 9.dp.toPx() + 1.5.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScrubSlider("Яркость", value, 0f..1f, "${(value * 100).toInt()}%") { value = it; push() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // превью: слева старый цвет, справа новый
                        Row(Modifier.clip(RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(10.dp))) {
                            Box(Modifier.size(width = 34.dp, height = 40.dp).background(Color(initial.toInt() or 0xFF000000.toInt())))
                            Box(Modifier.size(width = 34.dp, height = 40.dp).background(Color(current().toInt())))
                        }
                        OutlinedTextField(
                            value = hex,
                            onValueChange = { v ->
                                val clean = v.uppercase().filter { it.isDigit() || it in 'A'..'F' || it == '#' }.take(7)
                                hex = clean
                                val digits = clean.removePrefix("#")
                                if (digits.length == 6) {
                                    val argb = 0xFF000000L or digits.toLong(16)
                                    val a = FloatArray(3)
                                    android.graphics.Color.colorToHSV(argb.toInt(), a)
                                    hue = a[0]; sat = a[1]; value = a[2]
                                    onColor(argb)
                                }
                            },
                            singleLine = true,
                            label = { Text("HEX") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { saveColor(current()) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(28.dp)) { Text("+") }
                        for (c in palette) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(Color(c.toInt() or 0xFF000000.toInt()))
                                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
                                    .pointerInput(c) {
                                        detectTapGestures {
                                            val a = FloatArray(3)
                                            android.graphics.Color.colorToHSV(c.toInt(), a)
                                            hue = a[0]; sat = a[1]; value = a[2]; push()
                                        }
                                    }
                            )
                        }
                    }
                    OutlinedButton(onClick = { onEyedropper(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        com.vibetoon.ui.IconText(androidx.compose.material.icons.Icons.Default.Colorize, "Пипетка", 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(::close) { Text("Готово") } }
    )
}

private fun hexOf(argb: Long) = "#%06X".format(argb and 0xFFFFFFL)
