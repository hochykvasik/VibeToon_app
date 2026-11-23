package com.vibetoon.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetoon.domain.anim.SceneRange
import com.vibetoon.domain.anim.TimeFormat
import com.vibetoon.ui.MainViewModel
import com.vibetoon.ui.i18n.L
import com.vibetoon.ui.i18n.Text

/** Имя сцены для показа: стандартное «Сцена N» переводится, пользовательское - как есть. */
@Composable
fun sceneDisplayName(name: String): String =
    Regex("^Сцена ([1-9][0-9]*)$").matchEntire(name)?.let { L("Сцена") + " " + it.groupValues[1] } ?: name

private val SCENE_COLORS = listOf(Color(0xFF7E57C2), Color(0xFF26A69A))

/**
 * Полоса сцен над линейкой (пакет 9): цветные отрезки с именами; тап по отрезку - переход в сцену.
 * Листается вместе с линейкой ([hScroll]), рисует только видимую часть.
 */
@Composable
fun SceneStrip(
    vm: MainViewModel, scenes: List<SceneRange>, current: SceneRange?, frameWPx: Float, contentW: Dp, nameW: Dp,
    hScroll: ScrollState, viewportPx: Int, onOpen: () -> Unit
) {
    val measurer = rememberTextMeasurer()
    val labels = scenes.map { sceneDisplayName(it.name) }
    val textColor = MaterialTheme.colorScheme.onSurface
    Row {
        Row(
            Modifier.width(nameW).height(18.dp).padding(start = 6.dp).pointerInput(Unit) { detectTapGestures { onOpen() } },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Movie, null, Modifier.size(13.dp), tint = SCENE_COLORS[0])
            Text(current?.let { sceneDisplayName(it.name) } ?: L("Вне сцен"), modifier = Modifier.padding(start = 3.dp), fontSize = 11.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.horizontalScroll(hScroll, enabled = false)) {
            Canvas(
                Modifier.width(contentW).height(18.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .pointerInput(scenes, frameWPx) {
                        detectTapGestures { pt ->
                            val f = (pt.x / frameWPx).toInt()
                            scenes.firstOrNull { f in it }?.let { vm.selectScene(it.id) }
                        }
                    }
            ) {
                val left = hScroll.value.toFloat()
                val right = left + viewportPx
                scenes.forEachIndexed { i, s ->
                    val x0 = s.start * frameWPx
                    val x1 = s.end * frameWPx
                    if (x1 < left || x0 > right) return@forEachIndexed
                    val col = SCENE_COLORS[i % SCENE_COLORS.size]
                    drawRect(col.copy(alpha = if (s == current) 0.55f else 0.25f), Offset(x0, 0f), Size(x1 - x0, size.height))
                    drawLine(col, Offset(x0, 0f), Offset(x0, size.height), strokeWidth = 2f)
                    // подпись в видимой части отрезка: имя не уезжает за край при прокрутке
                    val tx = maxOf(x0, left) + 4f
                    if (x1 - tx > 24f) {
                        val layout = measurer.measure(labels[i], TextStyle(fontSize = 10.sp, color = textColor), maxLines = 1,
                            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (x1 - tx - 4f).toInt().coerceAtLeast(1)))
                        drawText(layout, topLeft = Offset(tx, (size.height - layout.size.height) / 2f))
                    }
                }
            }
        }
    }
}

/** Окно «Сцены»: список, переход, переименование, удаление, новая сцена, разрез, Play/экспорт только сцены. */
@Composable
fun ScenesDialog(vm: MainViewModel, scenes: List<SceneRange>, current: SceneRange?, fps: Int, playOnly: Boolean, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) }
    var nameText by remember { mutableStateOf("") }
    var exportOnly by remember { mutableStateOf(vm.exportSceneOnly) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сцены") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сцены делят общий таймлайн на части: переход, Play и экспорт по сцене. Удаление сцены не удаляет кадры и ключи.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                if (scenes.isEmpty()) Text("Сцен пока нет: «+ Новая сцена» сделает текущий таймлайн первой сценой.", fontSize = 13.sp)
                for (s in scenes) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = s == current, onClick = { vm.selectScene(s.id) })
                        if (editing == s.id) {
                            OutlinedTextField(
                                value = nameText, onValueChange = { nameText = it.take(com.vibetoon.domain.anim.SceneMath.MAX_NAME) },
                                singleLine = true, modifier = Modifier.weight(1f), label = { Text("Имя сцены") }
                            )
                            TextButton(onClick = { vm.renameScene(s.id, nameText); editing = null }) { Text("OK") }
                        } else {
                            Column(Modifier.weight(1f)) {
                                Text(sceneDisplayName(s.name), fontSize = 14.sp, fontWeight = if (s == current) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                Text("${TimeFormat.format(s.start, fps)} – ${TimeFormat.format(s.end, fps)} · ${s.length} кадров", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
                            }
                            IconButton(onClick = { editing = s.id; nameText = s.name }) { Icon(Icons.Default.Edit, L("Переименовать сцену")) }
                            IconButton(onClick = { vm.removeScene(s.id) }) { Icon(Icons.Default.Delete, L("Удалить сцену")) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(onClick = vm::addScene) { Text("+ Новая сцена", maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = vm::splitSceneAtPlayhead) { Text("Разрезать на плейхеде", maxLines = 1, softWrap = false) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.stepScene(-1) }, enabled = scenes.isNotEmpty()) { Text("◀ Предыдущая", maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.stepScene(1) }, enabled = scenes.isNotEmpty()) { Text("Следующая ▶", maxLines = 1, softWrap = false) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = playOnly, onCheckedChange = vm::setScenePlayOnly)
                    Text("Play только в текущей сцене", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = exportOnly, onCheckedChange = { exportOnly = it; vm.setExportSceneOnly(it) })
                    Text("Экспорт только текущей сцены", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}
