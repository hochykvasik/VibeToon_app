package com.vibetoon.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetoon.domain.anim.Curve

private const val Y_MIN = -0.6f
private const val Y_MAX = 1.6f
private const val HIT_DP = 26f

/**
 * Редактор кривой плавности (как в Alight Motion): график «время (x) - значение (y)», на кривой несколько узлов.
 * Узел тянется пальцем; у выбранного узла два усика Безье (In красный, Out синий) тоже тянутся. Двойной тап по
 * линии добавляет узел, долгий тап по узлу удаляет его (первый и последний нельзя). Список пресетов и поля
 * X / Y для выбранного узла. Изменения применяются сразу ко всем ключам на текущем кадре; [onChange] получает
 * кривую и признак «начало жеста» (точка отмены).
 */
@Composable
fun CurveEditorDialog(initial: Curve, onChange: (Curve, Boolean) -> Unit, onDismiss: () -> Unit) {
    var curve by remember { mutableStateOf(initial) }
    var sel by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val curveNow = rememberUpdatedState(curve)
    val selNow = rememberUpdatedState(sel)
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val ink = MaterialTheme.colorScheme.onSurface

    fun push(c: Curve, first: Boolean) { curve = c; onChange(c, first) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        title = { Text("Кривая плавности") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), modifier = Modifier.height(34.dp)) {
                            com.vibetoon.ui.DropText("Пресеты")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            for ((title, make) in Curve.PRESETS) DropdownMenuItem(text = { Text(title) }, onClick = { menu = false; sel = 0; push(make(), true) })
                        }
                    }
                    Text("Узлов: ${curve.nodes.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                Canvas(
                    Modifier.size(width = 330.dp, height = 140.dp)
                        // тап: выбрать узел; двойной тап по линии: новый узел; долгий тап по узлу: удалить
                        .pointerInput(Unit) {
                            val pad = 18.dp.toPx()
                            fun px(x: Float) = pad + x * (size.width - pad * 2)
                            fun py(y: Float) = pad + (1f - (y - Y_MIN) / (Y_MAX - Y_MIN)) * (size.height - pad * 2)
                            fun hit(p: Offset): Int {
                                val c = curveNow.value
                                var best = -1; var bd = HIT_DP.dp.toPx()
                                for ((i, n) in c.nodes.withIndex()) {
                                    val d = (Offset(px(n.x), py(n.y)) - p).getDistance()
                                    if (d < bd) { bd = d; best = i }
                                }
                                return best
                            }
                            detectTapGestures(
                                onTap = { p -> hit(p).let { if (it >= 0) sel = it } },
                                onDoubleTap = { p ->
                                    val x = (p.x - pad) / (size.width - pad * 2)
                                    val c = curveNow.value
                                    val added = c.insertAt(x)
                                    if (added !== c) {
                                        val idx = added.nodes.indexOfFirst { n -> c.nodes.none { it === n } }
                                        sel = if (idx >= 0) idx else sel
                                        push(added, true)
                                    }
                                },
                                onLongPress = { p ->
                                    val i = hit(p)
                                    if (i > 0 && i < curveNow.value.nodes.size - 1) { sel = (i - 1).coerceAtLeast(0); push(curveNow.value.without(i), true) }
                                }
                            )
                        }
                        // перетаскивание узла или усика выбранного узла
                        .pointerInput(Unit) {
                            val pad = 18.dp.toPx()
                            var target = -1   // 0 узел, 1 усик In, 2 усик Out
                            var idx = -1
                            fun px(x: Float) = pad + x * (size.width - pad * 2)
                            fun py(y: Float) = pad + (1f - (y - Y_MIN) / (Y_MAX - Y_MIN)) * (size.height - pad * 2)
                            detectDragGestures(
                                onDragStart = { p ->
                                    val c = curveNow.value
                                    val reach = HIT_DP.dp.toPx()
                                    target = -1; idx = -1
                                    val s = selNow.value.coerceIn(0, c.nodes.size - 1)
                                    val n = c.nodes[s]
                                    // усики выбранного узла проверяются первыми
                                    if (s > 0 && (Offset(px(n.x + n.inX), py(n.y + n.inY)) - p).getDistance() < reach) { target = 1; idx = s }
                                    else if (s < c.nodes.size - 1 && (Offset(px(n.x + n.outX), py(n.y + n.outY)) - p).getDistance() < reach) { target = 2; idx = s }
                                    else {
                                        var bd = reach
                                        for ((i, m) in c.nodes.withIndex()) {
                                            val d = (Offset(px(m.x), py(m.y)) - p).getDistance()
                                            if (d < bd) { bd = d; target = 0; idx = i }
                                        }
                                    }
                                    if (idx >= 0) { sel = idx; onChange(c, true) }
                                },
                                onDragEnd = { target = -1 },
                                onDragCancel = { target = -1 }
                            ) { change, _ ->
                                if (target < 0) return@detectDragGestures
                                change.consume()
                                val c = curveNow.value
                                val n = c.nodes[idx]
                                val x = ((change.position.x - pad) / (size.width - pad * 2)).coerceIn(0f, 1f)
                                val y = (Y_MIN + (1f - (change.position.y - pad) / (size.height - pad * 2)) * (Y_MAX - Y_MIN)).coerceIn(-1f, 2f)
                                val nn = when (target) {
                                    0 -> {
                                        val lo = if (idx > 0) c.nodes[idx - 1].x + 0.01f else 0f
                                        val hi = if (idx < c.nodes.size - 1) c.nodes[idx + 1].x - 0.01f else 1f
                                        val fixedX = idx == 0 || idx == c.nodes.size - 1
                                        n.copy(x = if (fixedX) n.x else x.coerceIn(lo, hi), y = if (fixedX) n.y else y)
                                    }
                                    1 -> n.copy(inX = (x - n.x).coerceIn(-(n.x - (if (idx > 0) c.nodes[idx - 1].x else 0f)), 0f), inY = y - n.y)
                                    else -> n.copy(outX = (x - n.x).coerceIn(0f, (if (idx < c.nodes.size - 1) c.nodes[idx + 1].x else 1f) - n.x), outY = y - n.y)
                                }
                                val updated = c.withNode(idx, nn)
                                curve = updated
                                onChange(updated, false)
                            }
                        }
                ) {
                    val pad = 18.dp.toPx()
                    val w = size.width - pad * 2; val h = size.height - pad * 2
                    fun px(x: Float) = pad + x * w
                    fun py(y: Float) = pad + (1f - (y - Y_MIN) / (Y_MAX - Y_MIN)) * h
                    for (i in 0..8) {
                        val x = px(i / 8f); drawLine(grid, Offset(x, py(Y_MIN)), Offset(x, py(Y_MAX)), 1.dp.toPx())
                    }
                    for (v in listOf(0f, 0.5f, 1f)) drawLine(if (v == 0f || v == 1f) ink.copy(alpha = 0.45f) else grid, Offset(px(0f), py(v)), Offset(px(1f), py(v)), 1.dp.toPx())
                    drawLine(ink, Offset(px(0f), py(Y_MIN)), Offset(px(0f), py(Y_MAX)), 1.5.dp.toPx())
                    drawLine(ink, Offset(px(1f) - 10.dp.toPx(), py(Y_MIN) - 5.dp.toPx()), Offset(px(1f), py(Y_MIN)), 1.5.dp.toPx())
                    drawLine(ink, Offset(px(1f) - 10.dp.toPx(), py(Y_MIN) + 5.dp.toPx()), Offset(px(1f), py(Y_MIN)), 1.5.dp.toPx())
                    drawLine(ink, Offset(px(0f) - 5.dp.toPx(), py(Y_MAX) + 10.dp.toPx()), Offset(px(0f), py(Y_MAX)), 1.5.dp.toPx())
                    drawLine(ink, Offset(px(0f) + 5.dp.toPx(), py(Y_MAX) + 10.dp.toPx()), Offset(px(0f), py(Y_MAX)), 1.5.dp.toPx())
                    val path = Path()
                    for (i in 0..120) {
                        val t = i / 120f
                        val o = Offset(px(t), py(curve.eval(t)))
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, line, style = Stroke(3.dp.toPx()))
                    val s = sel.coerceIn(0, curve.nodes.size - 1)
                    for ((i, n) in curve.nodes.withIndex()) {
                        val c = Offset(px(n.x), py(n.y))
                        if (i == s) {
                            if (i > 0) {
                                val hi = Offset(px(n.x + n.inX), py(n.y + n.inY))
                                drawLine(Color(0xFFE53935), c, hi, 2.dp.toPx())
                                drawCircle(Color.White, 9.dp.toPx(), hi); drawCircle(Color(0xFFE53935), 6.5f.dp.toPx(), hi)
                            }
                            if (i < curve.nodes.size - 1) {
                                val ho = Offset(px(n.x + n.outX), py(n.y + n.outY))
                                drawLine(Color(0xFF1E88E5), c, ho, 2.dp.toPx())
                                drawCircle(Color.White, 9.dp.toPx(), ho); drawCircle(Color(0xFF1E88E5), 6.5f.dp.toPx(), ho)
                            }
                            drawCircle(Color(0xFFFFA726), 11.dp.toPx(), c); drawCircle(Color.White, 5.dp.toPx(), c)
                        } else {
                            drawCircle(Color.White, 8.dp.toPx(), c); drawCircle(line, 5.5f.dp.toPx(), c)
                        }
                    }
                }
                // точные значения выбранного узла
                val s = sel.coerceIn(0, curve.nodes.size - 1)
                val n = curve.nodes[s]
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Узел ${s + 1}", fontSize = 12.sp)
                    val inner = s > 0 && s < curve.nodes.size - 1
                    NumField("X", n.x, inner) { v ->
                        val lo = curve.nodes[s - 1].x + 0.01f; val hi = curve.nodes[s + 1].x - 0.01f
                        push(curve.withNode(s, n.copy(x = v.coerceIn(lo, hi))), true)
                    }
                    NumField("Y", n.y, inner) { v -> push(curve.withNode(s, n.copy(y = v.coerceIn(-1f, 2f))), true) }
                }
                Text(
                    "Двойной тап по линии — новый узел, долгий тап по узлу — удалить. Тяните узел и красный (In) / синий (Out) усики: так делают S-образные кривые, пружины и затухание. Время → вправо, значение ↑ вверх.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
    )
}

@Composable
private fun NumField(label: String, value: Float, enabled: Boolean, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf("%.3f".format(value)) }
    OutlinedTextField(
        value = text, onValueChange = { t -> text = t; t.replace(',', '.').toFloatOrNull()?.let(onValue) },
        label = { Text(label, fontSize = 11.sp) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.width(110.dp),
        textStyle = TextStyle(fontSize = 13.sp)
    )
}
