package com.vibetoon.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VisibilityOff
import com.vibetoon.ui.motion.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.domain.project.PHONEME_MARKERS
import kotlin.math.roundToInt

/** Маркеры кадров открытого символа: имя ставится на текущий кадр; готовый набор фонем для липсинга. */
@Composable
fun MarkerEditDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val markers by vm.symMarkers.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Маркеры кадров (кадр ${frame + 1})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Имя маркера") })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in PHONEME_MARKERS) AssistChip(onClick = { vm.addFrameMarker(p) }, label = { Text(p) })
                }
                OutlinedButton(onClick = { vm.addFrameMarker(name); name = "" }, enabled = name.isNotBlank()) { Text("Поставить на текущий кадр") }
                if (markers.isEmpty()) Text("Маркеров пока нет. Перейдите на нужный кадр символа и выберите имя.", fontSize = 11.sp)
                for (m in markers) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (m.isAlias) "${m.frame + 1}: ${m.name} → ${m.form + 1}" else "${m.frame + 1}: ${m.name}", Modifier.width(140.dp), fontSize = 13.sp)
                    TextButton(onClick = { vm.setFrame(m.frame) }) { Text("Перейти") }
                    TextButton(onClick = { vm.removeFrameMarker(m.frame) }) { Text("Удалить") }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Готово") } }
    )
}

/**
 * Плавающая панель «Выбрать маркер»: сетка 3 колонки (миниатюра + имя), активный маркер (тот, что сейчас задаёт время)
 * подсвечен. Тап по карточке ставит ключ времени Hold на текущем кадре сцены. Панель двигается за шапку, прячется к краю
 * (остаётся полоска, чтобы вытащить обратно) и закрывается крестиком.
 */
@Composable
fun MarkerPanel(vm: MainViewModel, instId: Int, onClose: () -> Unit) {
    val thumbs by vm.markerThumbs.collectAsStateWithLifecycle()
    val keys by vm.timeKeys.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val markers = vm.markersOfInstance(instId)
    val defId = vm.symbolRefOf(instId)
    // Подсветка - форма, которую экземпляр РЕАЛЬНО показывает на этом кадре (та же функция, что у холста),
    // а не сырое значение ключа: при Play/скраббинге рамка и картинка больше не расходятся.
    val active = remember(keys, frame, markers) { vm.shownMarkerAt(instId, frame)?.frame }
    val short = remember(markers) { com.vibetoon.domain.project.SymbolForms.compactLabels(markers.map { it.name }) }
    val twins = remember(keys, instId) { vm.overlappingInstances(instId) }
    LaunchedEffect(instId) { vm.loadMarkerThumbs(instId) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelW = 276.dp
        val strip = 30.dp
        val maxXpx = with(density) { (maxWidth - panelW).toPx() }.coerceAtLeast(0f)
        val maxYpx = with(density) { (maxHeight - 120.dp).toPx() }.coerceAtLeast(0f)
        var pos by remember { mutableStateOf(Offset(maxXpx, with(density) { 44.dp.toPx() })) }
        var hidden by remember { mutableStateOf(false) }
        val nearRight = pos.x > maxXpx / 2f
        val shownX = pos.x.coerceIn(0f, maxXpx)
        val hiddenX = if (nearRight) with(density) { (maxWidth - strip).toPx() } else with(density) { (strip - panelW).toPx() }
        val x = if (hidden) hiddenX else shownX
        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), pos.y.coerceIn(0f, maxYpx).roundToInt()) }
                .width(panelW)
                .then(if (hidden) Modifier.clickable { hidden = false } else Modifier),
            shape = RoundedCornerShape(12.dp), shadowElevation = 10.dp, tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectDragGestures { c, d -> c.consume(); pos = Offset(pos.x + d.x, pos.y + d.y); hidden = false }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DragHandle, com.vibetoon.ui.i18n.L("Двигать"), Modifier.size(20.dp))
                    Text("  Маркеры  кадр ${frame + 1}", fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton({ hidden = true }, Modifier.size(32.dp)) { Icon(Icons.Default.VisibilityOff, com.vibetoon.ui.i18n.L("Спрятать к краю"), Modifier.size(18.dp)) }
                    IconButton(onClose, Modifier.size(32.dp)) { Icon(Icons.Default.Close, com.vibetoon.ui.i18n.L("Закрыть"), Modifier.size(18.dp)) }
                }
                Column(Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (markers.isEmpty()) Text("У символа нет маркеров", fontSize = 12.sp)
                    if (twins > 0) Text("На том же месте ещё экземпляров этого символа: $twins. Каждый показывает свою форму - лишний удалите или скройте, иначе видны две формы сразу.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    for (rowItems in markers.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in rowItems) {
                            val isActive = active == m.target
                            Column(
                                Modifier.width(84.dp).clip(RoundedCornerShape(8.dp))
                                    .border(if (isActive) 3.dp else 1.dp, if (isActive) Color(0xFF7E57C2) else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                    .clickable { vm.pickMarker(instId, m) }.padding(3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val img = thumbs["$defId:${m.frame}"]
                                Box(Modifier.size(72.dp).background(Color(0xFFEFEFEF)).thumbnailChecker(com.vibetoon.ui.theme.LocalAppSettings.current?.checkerThumbnails == true), contentAlignment = Alignment.Center) {
                                    if (img != null) Image(img, com.vibetoon.ui.i18n.L(m.name), Modifier.size(72.dp)) else Text("…", fontSize = 12.sp)
                                }
                                Text(short[m.name] ?: m.name, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ vm.addTimeKeyHere(instId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 32.dp)) {
                        Icon(Icons.Default.Key, null, Modifier.size(16.dp)); Text(" Ключ", fontSize = 12.sp)
                    }
                    TextButton({ vm.removeTimeKey(instId) }) { Text("Убрать", fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    Text("Hold", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** Вкладка «Время» инспектора экземпляра символа: текущий маркер, выбор маркера, ручной ключ, Hold. */
@Composable
fun SymbolTimeTab(vm: MainViewModel, instId: Int) {
    val frame by vm.frame.collectAsStateWithLifecycle()
    val keys by vm.timeKeys.collectAsStateWithLifecycle()
    val markers = vm.markersOfInstance(instId)
    val cur = if (keys.containsKey(instId)) vm.currentMarkerName(instId, frame) else null
    var playMode by remember(instId) { mutableStateOf(vm.symbolPlayback(instId)) }
    val ease = if (keys.containsKey(instId)) vm.timeKeyEaseAt(instId, frame) else null
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Символ: ${vm.symbolName(vm.symbolRefOf(instId))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Время:", fontSize = 13.sp)
            Text(cur ?: when (playMode) { com.vibetoon.domain.project.SymbolPlayback.LOOP -> "по кругу (цикл)"; com.vibetoon.domain.project.SymbolPlayback.ONCE -> "один раз, затем последний кадр"; else -> "кадр 1 (Hold)" },
                fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ vm.openMarkerPanel(instId) }, enabled = markers.isNotEmpty()) { Text("Выбрать маркер", fontSize = 12.sp) }
            OutlinedButton({ vm.addTimeKeyHere(instId) }) { Icon(Icons.Default.Key, com.vibetoon.ui.i18n.L("Добавить ключ времени"), Modifier.size(16.dp)) }
        }
        if (markers.isEmpty()) Text("У символа нет маркеров. Двойной тап по символу открывает его: кнопка «Маркеры» ставит имя на кадр.", fontSize = 11.sp)
        Text("Ключей: ${keys[instId]?.size ?: 0}. Новый ключ из маркера - Hold: символ мгновенно переключается на форму и держит её до следующего ключа. Без ключей символ стоит на первом кадре.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        if (ease != null) {
            Text("Переход к следующему ключу: ${vm.timeEaseName(ease)}", fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in listOf(com.vibetoon.domain.project.TimeEase.HOLD, com.vibetoon.domain.project.TimeEase.SMOOTH, com.vibetoon.domain.project.TimeEase.LINEAR,
                        com.vibetoon.domain.project.TimeEase.EASE_IN, com.vibetoon.domain.project.TimeEase.EASE_OUT))
                    AssistChip(onClick = { vm.setTimeKeyEase(instId, e) }, label = { Text(vm.timeEaseName(e), fontSize = 11.sp) },
                        border = if (e == ease) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF7E57C2)) else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline))
            }
            Text("Hold - резкая смена формы. Плавно/Линейно - кадры символа между двумя ключами проигрываются по кривой.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        // Спринт 37: режим без ключей времени - Hold / Цикл / Один раз (Play Once)
        Text("Без ключей времени:", fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in com.vibetoon.domain.project.SymbolPlayback.ALL)
                AssistChip(onClick = { playMode = mode; vm.setSymbolPlayback(instId, mode) },
                    label = { Text(com.vibetoon.domain.project.SymbolPlayback.label(mode), fontSize = 11.sp) },
                    border = if (mode == playMode) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF7E57C2)) else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline))
        }
        Text(when (playMode) {
            com.vibetoon.domain.project.SymbolPlayback.LOOP -> "Цикл: внутренняя анимация символа повторяется по кругу."
            com.vibetoon.domain.project.SymbolPlayback.ONCE -> "Один раз: анимация символа идёт с начала его плашки на таймлайне и замирает на последнем кадре - на холсте, при Play и в экспорте."
            else -> "Hold: символ стоит на первом кадре."
        }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ vm.removeTimeKey(instId) }) { Text("Убрать ключ", fontSize = 12.sp) }
            TextButton({ vm.clearTimeKeys(instId) }) { Text("Очистить", fontSize = 12.sp) }
        }
    }
}
