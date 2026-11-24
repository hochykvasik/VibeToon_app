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
import com.vibetoon.ui.motion.AlertDialog
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.vibetoon.domain.paint.ColorPalette
import com.vibetoon.domain.paint.ColorModels
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.rememberUpdatedState
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
    val hsv = remember { ColorModels.hsbOf(initial) }
    var hue by remember { mutableFloatStateOf(hsv.h) }
    var sat by remember { mutableFloatStateOf(hsv.s) }
    var value by remember { mutableFloatStateOf(hsv.v) }
    var hex by remember { mutableStateOf(hexOf(initial)) }
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("vt_colors", android.content.Context.MODE_PRIVATE)
    // Спринт 37: режим «Круг / RGB / HSB» запоминается между открытиями окна
    var mode by remember { mutableStateOf(prefs.getInt("mode", ColorModels.MODE_WHEEL).takeIf { it in ColorModels.MODES } ?: ColorModels.MODE_WHEEL) }
    var palette by remember { mutableStateOf(ColorPalette.decode(prefs.getString("recent", null))) }
    fun saveColor(c: Long) {
        palette = ColorPalette.add(palette, c)
        prefs.edit().putString("recent", ColorPalette.encode(palette)).apply()
    }

    fun current(): Long = ColorModels.argb(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    /** Готовый цвет (палитра, HEX, RGB): оттенок серого/чёрного не сбрасывается в 0. */
    fun setArgb(c: Long) { val h = ColorModels.hsbOf(c, hue, sat); hue = h.h; sat = h.s; value = h.v }
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
                    // Спринт 37: точные ползунки RGB (0-255) и HSB (H 0-360°, S/B 0-100 %) рядом с кругом
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in ColorModels.MODES) FilterChip(selected = mode == m, onClick = { mode = m; prefs.edit().putInt("mode", m).apply() },
                            label = { Text(ColorModels.modeLabel(m), fontSize = 12.sp) })
                    }
                    when (mode) {
                        ColorModels.MODE_RGB -> {
                            val rgb = ColorModels.rgbOf(current())
                            ChannelSlider("R", rgb.r, 255, "", listOf(Color(0, rgb.g, rgb.b), Color(255, rgb.g, rgb.b))) { setArgb(ColorModels.argb(rgb.copy(r = it))); push() }
                            ChannelSlider("G", rgb.g, 255, "", listOf(Color(rgb.r, 0, rgb.b), Color(rgb.r, 255, rgb.b))) { setArgb(ColorModels.argb(rgb.copy(g = it))); push() }
                            ChannelSlider("B", rgb.b, 255, "", listOf(Color(rgb.r, rgb.g, 0), Color(rgb.r, rgb.g, 255))) { setArgb(ColorModels.argb(rgb.copy(b = it))); push() }
                        }
                        ColorModels.MODE_HSB -> {
                            val (hi, si, bi) = ColorModels.hsbInts(ColorModels.Hsb(hue, sat, value))
                            ChannelSlider("H", hi, 360, "°", (0..6).map { Color.hsv((it * 60f) % 360f, 1f, 1f) }) { hue = it.toFloat().coerceIn(0f, 360f); if (value < 0.05f && sat > 0f) value = 1f; push() }
                            ChannelSlider("S", si, 100, "%", listOf(Color.hsv(hue % 360f, 0f, value), Color.hsv(hue % 360f, 1f, value))) { sat = it / 100f; push() }
                            ChannelSlider("B", bi, 100, "%", listOf(Color.Black, Color.hsv(hue % 360f, sat, 1f))) { value = it / 100f; push() }
                        }
                        else -> ScrubSlider("Яркость", value, 0f..1f, "${(value * 100).toInt()}%") { value = it; push() }
                    }
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
                                // 6 знаков или короткая запись #RGB (спринт 37)
                                val argb = if (clean.removePrefix("#").length == 6 || clean.removePrefix("#").length == 3) ColorModels.parseHex(clean) else null
                                if (argb != null) {
                                    setArgb(argb)
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
                                        detectTapGestures { setArgb(c); push() }
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

private fun hexOf(argb: Long) = ColorModels.hex(argb)

/**
 * Спринт 37: ползунок канала цвета - подпись, полоса-градиент (касание и протяжка ставят значение там, где палец) и
 * поле для точного числа 0..[max]. Ввод больше [max] обрезается, пустое поле значение не меняет.
 */
@Composable
private fun ChannelSlider(label: String, value: Int, max: Int, suffix: String, track: List<Color>, onValue: (Int) -> Unit) {
    val out by rememberUpdatedState(onValue)
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(16.dp))
        Canvas(
            Modifier.weight(1f).height(26.dp)
                .pointerInput(max) {
                    detectTapGestures { o -> out(Math.round(o.x / size.width.coerceAtLeast(1) * max).coerceIn(0, max)) }
                }
                .pointerInput(max) {
                    detectDragGestures(onDragStart = { o -> out(Math.round(o.x / size.width.coerceAtLeast(1) * max).coerceIn(0, max)) }) { change, _ ->
                        change.consume(); out(Math.round(change.position.x / size.width.coerceAtLeast(1) * max).coerceIn(0, max))
                    }
                }
        ) {
            val h = size.height
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f)
            drawRoundRect(Brush.horizontalGradient(track), topLeft = Offset(0f, h * 0.2f), size = androidx.compose.ui.geometry.Size(size.width, h * 0.6f), cornerRadius = r)
            val x = (value.toFloat() / max).coerceIn(0f, 1f) * size.width
            drawCircle(Color.White, h * 0.42f, Offset(x, h / 2f), style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black, h * 0.42f + 1.5.dp.toPx(), Offset(x, h / 2f), style = Stroke(1.dp.toPx()))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                val digits = v.filter { it.isDigit() }.take(3)
                text = digits
                ColorModels.parseChannel(digits, max)?.let { out(it) }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.width(72.dp)
        )
        Text(suffix, fontSize = 12.sp, modifier = Modifier.width(14.dp))
    }
}
