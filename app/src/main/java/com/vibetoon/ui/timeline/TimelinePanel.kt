@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vibetoon.ui.timeline

import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import com.vibetoon.ui.shell.LocalStub
import com.vibetoon.ui.thumbnailChecker
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.vibetoon.ui.motion.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.vibetoon.ui.shell.coachTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import com.vibetoon.ui.dragEdgeAutoScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.TrackGroups
import com.vibetoon.domain.anim.TimeFormat
import com.vibetoon.domain.project.Clip
import com.vibetoon.ui.MainViewModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val ROW_H = 28.dp
private val RULER_H = 26.dp
private val MARKER_H = 26.dp
private val NAME_W = 156.dp
private const val DEFAULT_FRAME_DP = 10f   // ширина одного кадра на линейке (меняется щипком)
private const val MIN_FRAME_DP = 1.5f
private const val MAX_FRAME_DP = 48f
private val PLAYHEAD_GRAB_W = 26.dp  // ширина зоны захвата вокруг красной линии
private val HANDLE_GRAB_W = 22.dp   // зона захвата края клипа
private const val INDENT_DP = 12     // отступ на каждый уровень вложенности

/**
 * Нижняя панель: Play/Pause, поле кадра, Add/Delete Key, сброс трансформации, цепочка пропорций,
 * линейка с playhead и дерево слоёв (группы сворачиваются) с ромбами ключей.
 */
@Composable
fun TimelinePanel(
    vm: MainViewModel, modifier: Modifier = Modifier, visibleRows: Int = 5,
    /** true - строки слоёв занимают всю оставшуюся высоту панели (ландшафтная раскладка). */
    fill: Boolean = false, collapsed: Boolean = false, onToggleCollapse: () -> Unit = {}
) {
    // Кадр читается только в draw/лямбдах: при скраббинге перерисовываются линейка и плейхед, а не вся панель со 100 строками
    val frameS = vm.frame.collectAsStateWithLifecycle()
    val frame by frameS
    val playing by vm.playing.collectAsStateWithLifecycle()
    val animation by vm.animation.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val hasKeyClip by vm.hasKeyClip.collectAsStateWithLifecycle()
    val audio by vm.audioTracks.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val clips by vm.clips.collectAsStateWithLifecycle()
    val cellMap by vm.cellMap.collectAsStateWithLifecycle()
    val symMarkers by vm.symMarkers.collectAsStateWithLifecycle()
    val waves by vm.waveforms.collectAsStateWithLifecycle()
    // пакет 11: плёнка кадров и волна звука на полосах видео-клипов
    val stripVer by vm.filmstripVersion.collectAsStateWithLifecycle()
    val videoIds = remember(rows) { vm.videoIds() }
    val timeKeyMap by vm.timeKeys.collectAsStateWithLifecycle()
    val multi by vm.multiSelect.collectAsStateWithLifecycle()
    val keySel by vm.keySelection.collectAsStateWithLifecycle()
    val bones by vm.bones.collectAsStateWithLifecycle()
    val boneMode by vm.boneMode.collectAsStateWithLifecycle()
    // строки костей (пакет 7): в режиме «Кости» - все кости, иначе только кости с ключами; ключи - ромбы ◆
    val boneRows = remember(bones, animation, boneMode) {
        val byId = bones.associateBy { it.id }
        com.vibetoon.domain.anim.BoneKeys.rows(bones.map { it.id }, animation.tracks, boneMode).mapNotNull { byId[it] }
    }
    val timeEaseDialog by vm.timeEaseDialog.collectAsStateWithLifecycle()
    if (timeEaseDialog) AlertDialog(
        onDismissRequest = vm::closeTimeEaseDialog,
        title = { Text("Общая кривая: ключи времени символа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Переход от каждого выделенного ключа к следующему. Между разными формами символ всегда меняет форму резко (Hold) - промежуточные формы не показываются.", fontSize = 12.sp)
                for (e in listOf(com.vibetoon.domain.project.TimeEase.HOLD, com.vibetoon.domain.project.TimeEase.LINEAR, com.vibetoon.domain.project.TimeEase.SMOOTH,
                        com.vibetoon.domain.project.TimeEase.EASE_IN, com.vibetoon.domain.project.TimeEase.EASE_OUT))
                    TextButton(onClick = { vm.applyTimeEaseToSelection(e) }) { Text(vm.timeEaseName(e)) }
            }
        },
        confirmButton = { TextButton(vm::closeTimeEaseDialog) { Text("Отмена") } }
    )
    val markerSel by vm.markerSelection.collectAsStateWithLifecycle()
    val hasMarkerClip by vm.hasMarkerClip.collectAsStateWithLifecycle()
    val editingSym by vm.editingSymbol.collectAsStateWithLifecycle()
    val instMarkers by vm.instanceMarkers.collectAsStateWithLifecycle()
    // короткие подписи форм считаются один раз на изменение маркеров, а не в каждом кадре отрисовки строки
    val instShort = remember(instMarkers) { instMarkers.mapValues { (_, ms) -> com.vibetoon.domain.project.SymbolForms.compactLabels(ms.map { it.name }) } }
    val haptic = LocalHapticFeedback.current
    val keyScope = rememberCoroutineScope()
    var keyDragDelta by remember { mutableIntStateOf(0) }
    // Порядок строк = порядок слоёв; выделение его не меняет (иначе ломается «Лесенка»)
    var showSettings by remember { mutableStateOf(false) }
    // сцены (пакет 9)
    val scenes by vm.scenes.collectAsStateWithLifecycle()
    val scenePlayOnly by vm.scenePlayOnly.collectAsStateWithLifecycle()
    var showScenes by remember { mutableStateOf(false) }
    // текущая сцена меняется только на границах сцен: панель не перекомпонуется на каждом кадре Play
    val sceneNow by remember(scenes) { derivedStateOf { scenes.firstOrNull { frameS.value in it } } }
    // пакет 10: открытая сцена (внутри символа - его собственные кадры, сцены не действуют)
    val activeSceneId by vm.activeScene.collectAsStateWithLifecycle()
    val activeSc = if (editingSym == null) scenes.firstOrNull { it.id == activeSceneId } else null
    var sceneMenu by remember { mutableStateOf(false) }
    var showHierarchy by remember { mutableStateOf(false) }
    var showKeyList by remember { mutableStateOf(false) }   // пакет 11: список ключей выбранного слоя/кости
    // пакет 10: «Режим ключей» - только слои с ключами (и их группы)
    val keyMode by vm.keyMode.collectAsStateWithLifecycle()
    val keyedIds = remember(rows, animation, timeKeyMap, keyMode) {
        if (!keyMode) emptySet() else com.vibetoon.domain.anim.KeyMode.keyed(rows.map { it.layer.id }, animation.tracks, timeKeyMap)
    }
    val shownRows = remember(rows, keyedIds, keyMode) { if (keyMode) com.vibetoon.domain.anim.KeyMode.rows(rows, keyedIds) else rows }
    val duration = animation.settings.durationFrames
    val fps = animation.settings.fps

    // Обычный тап: выбрать слой (группа - свернуть/развернуть); в режиме мультивыделения - добавить/убрать
    fun tapRow(id: Int, isGroup: Boolean) {
        when {
            multi -> vm.toggleSelect(id)
            isGroup -> vm.toggleGroup(id)
            else -> vm.selectLayer(id)
        }
    }
    // Долгое нажатие: войти в мультивыделение
    fun longRow(id: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.toggleSelect(id)
    }
    val markers by vm.markers.collectAsStateWithLifecycle()
    // Масштаб таймлайна: dp на кадр; меняется щипком двумя пальцами
    var frameDp by remember { mutableFloatStateOf(DEFAULT_FRAME_DP) }
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val frameWPx = with(density) { frameDp.dp.toPx() }
    val frameW = frameDp.dp
    val frameNow = frameS
    var anchorX by remember { mutableFloatStateOf(0f) }
    val hasKey by remember { derivedStateOf { selected?.let { animation.hasKey(it, frameS.value) } ?: false } }
    val easing by remember { derivedStateOf { selected?.let { animation.easingAt(it, frameS.value) } } }
    val markerHere by remember { derivedStateOf { frameS.value in markers } }
    var easingMenu by remember { mutableStateOf(false) }
    val compact = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            // Щипок двумя пальцами в любом месте панели: масштаб таймлайна вокруг playhead
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var last = 0f
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val down = ev.changes.filter { it.pressed }
                        if (down.isEmpty()) break
                        if (down.size >= 2) {
                            val d = (down[0].position - down[1].position).getDistance()
                            if (last > 0f && d > 0f) {
                                val next = (frameDp * d / last).coerceIn(MIN_FRAME_DP, MAX_FRAME_DP)
                                if (next != frameDp) {
                                    anchorX = frameNow.value * frameDp.dp.toPx() - hScroll.value
                                    frameDp = next
                                }
                            }
                            last = d
                            ev.changes.forEach { it.consume() }
                        } else last = 0f
                    }
                }
            }
    ) {
        val stub = LocalStub.current
        val fx by vm.fx.collectAsStateWithLifecycle()
        val onion by vm.onion.collectAsStateWithLifecycle()
        val onionDepth by vm.onionDepth.collectAsStateWithLifecycle()
        val onionAlpha by vm.onionAlpha.collectAsStateWithLifecycle()
        var onionDialog by remember { mutableStateOf(false) }
        val loop by vm.loop.collectAsStateWithLifecycle()
        val magnet by vm.magnet.collectAsStateWithLifecycle()
        var selectMode by remember { mutableStateOf(false) }
        val phone = com.vibetoon.ui.shell.LocalPhone.current
        var toolsOpen by remember { mutableStateOf(false) }
        if (onionDialog) com.vibetoon.ui.motion.AlertDialog(
            onDismissRequest = { onionDialog = false },
            title = { Text("Калька (Onion Skin)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Глубина: сколько рисунков назад и вперёд", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (d in 1..3) androidx.compose.material3.FilterChip(selected = onionDepth == d, onClick = { vm.setOnionDepth(d) }, label = { Text("$d") })
                    }
                    Text("Прозрачность: ${(onionAlpha * 100).toInt()}%", fontSize = 12.sp)
                    androidx.compose.material3.Slider(value = onionAlpha, onValueChange = { vm.setOnionAlpha(it) }, valueRange = 0.1f..0.9f)
                    Text("Красный - предыдущий рисунок, зелёный - следующий. Работает на слое-картинке с кадрами (кнопка «Добавить пустой кадр»).", fontSize = 11.sp)
                }
            },
            confirmButton = { androidx.compose.material3.TextButton({ onionDialog = false }) { Text("Готово") } }
        )
        val stepByFrames = com.vibetoon.ui.theme.LocalAppSettings.current?.stepFrames ?: true
        // ---- Панель воспроизведения ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            TlIcon(Icons.Default.FirstPage, "В начало") { vm.goStart() }
            TlIcon(Icons.Default.ChevronLeft, "Предыдущий кадр") { vm.stepTimeline(-1, stepByFrames) }
            FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(40.dp).coachTarget("Играть")) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, com.vibetoon.ui.i18n.L(if (playing) "Пауза" else "Играть"))
            }
            TlIcon(Icons.Default.ChevronRight, "Следующий кадр") { vm.stepTimeline(1, stepByFrames) }
            TlIcon(Icons.Default.LastPage, "В конец") { vm.goEnd() }
            // в открытой сцене время считается от её начала, справа - длина сцены
            FrameFieldHost(frameS, fps, activeSc?.start ?: 0, (activeSc?.length ?: duration) - 1, vm::scrubTo)
            Text("/ ${TimeFormat.format(activeSc?.length ?: duration, fps)}", fontSize = 12.sp, maxLines = 1, softWrap = false)
            TlIcon(Icons.Default.AutoAwesome, "Эффекты (FX)", active = fx) { vm.toggleFx() }
            TlIcon(Icons.Default.Layers, "Калька (Onion Skin)", active = onion, onLong = { onionDialog = true }) { vm.toggleOnion() }
            TlIcon(Icons.Default.Repeat, "Зацикливание", active = loop) { vm.toggleLoop() }
            // переключение сцен (пакет 10): список сцен, «Весь таймлайн», новая сцена, разрез, управление
            Box {
                AssistChip(onClick = { sceneMenu = true }, label = {
                    com.vibetoon.ui.DropText(activeSc?.let { sceneDisplayName(it.name) } ?: com.vibetoon.ui.i18n.L(if (scenes.isEmpty()) "Сцены" else "Весь таймлайн"))
                })
                SceneMenu(vm, sceneMenu, scenes, activeSc, playing) { open -> sceneMenu = false; if (open) showScenes = true }
            }
            OutlinedButton(
                onClick = vm::toggleKey,
                enabled = selected != null && !playing,
                contentPadding = compact
            ) { Text(if (hasKey) "◇ Delete Key" else "◆ Add Key", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            TlIcon(Icons.Default.HighlightAlt, "Выделение ключей рамкой", active = selectMode) { selectMode = !selectMode; if (!selectMode) vm.clearKeySelection() }
            if (phone) TlIcon(Icons.Default.MoreVert, "Ещё", active = toolsOpen) { toolsOpen = !toolsOpen }
            TlIcon(Icons.Default.Eject, if (collapsed) "Развернуть таймлайн" else "Свернуть таймлайн", active = collapsed) { onToggleCollapse() }
        }
        // ---- Выделение ключей: копировать / вырезать / вставить / удалить пачкой ----
        if (!collapsed && (selectMode || keySel.isNotEmpty() || hasKeyClip)) Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            Text(
                if (keySel.isEmpty()) (if (selectMode) "Рамкой по ключам" else "Ключи") else "Выбрано: ${keySel.size}",
                fontSize = 12.sp, maxLines = 1, softWrap = false
            )
            // пакет 8: снять выделение одним нажатием (раньше - только повторным долгим нажатием по каждому ключу)
            if (keySel.isNotEmpty()) TextButton(onClick = vm::clearKeySelection, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                Text("✕ Снять выделение", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            val keyBtn = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            OutlinedButton(onClick = vm::copySelectedKeys, enabled = keySel.isNotEmpty() && !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text("Копировать", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::cutSelectedKeys, enabled = keySel.isNotEmpty() && !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text("Вырезать", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::pasteKeys, enabled = hasKeyClip && !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text("Вставить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { vm.stretchSelectedKeys(2f) }, enabled = keySel.size >= 2 && !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text("Растянуть ×2", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { vm.stretchSelectedKeys(0.5f) }, enabled = keySel.size >= 2 && !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text("Сжать ÷2", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::openSelectedCurveEditor, enabled = keySel.isNotEmpty() && !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text("Общая кривая", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::reverseSelectedKeys, enabled = keySel.size >= 2 && !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text("Реверс", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::duplicateSelectedKeys, enabled = keySel.isNotEmpty() && !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text("Дублировать", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::deleteSelectedKeys, enabled = keySel.isNotEmpty() && !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text("Удалить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            // переход ключей времени символа (если они есть в выделении): резкая смена формы или плавное проигрывание кадров
            val tkInst = keySel.firstOrNull { (id, f) -> timeKeyMap[id]?.any { it.frame == f } == true }?.first
            if (tkInst != null) {
                Text("Переход:", fontSize = 12.sp, maxLines = 1, softWrap = false)
                for ((e, t) in listOf(com.vibetoon.domain.project.TimeEase.HOLD to "Hold", com.vibetoon.domain.project.TimeEase.SMOOTH to "Плавно", com.vibetoon.domain.project.TimeEase.LINEAR to "Линейно"))
                    OutlinedButton(onClick = { vm.setTimeKeyEase(tkInst, e) }, enabled = !playing, contentPadding = keyBtn, modifier = Modifier.height(32.dp)) { Text(t, fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
        // ---- Маркеры кадров открытого символа: те же операции, что у ключей ----
        if (!collapsed && editingSym != null && (markerSel.isNotEmpty() || hasMarkerClip)) Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            val mb = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            Text(if (markerSel.isEmpty()) "Маркеры" else "Маркеров: ${markerSel.size}", fontSize = 12.sp, maxLines = 1, softWrap = false)
            if (markerSel.isNotEmpty()) TextButton(onClick = vm::clearMarkerSelection, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                Text("✕ Снять выделение", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = vm::copySelectedMarkers, enabled = markerSel.isNotEmpty() && !playing, contentPadding = mb, modifier = Modifier.height(32.dp)) { Text("Копировать", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::cutSelectedMarkers, enabled = markerSel.isNotEmpty() && !playing, contentPadding = mb, modifier = Modifier.height(32.dp)) { Text("Вырезать", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::pasteMarkers, enabled = hasMarkerClip && !playing, contentPadding = mb, modifier = Modifier.height(32.dp)) { Text("Вставить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::duplicateSelectedMarkers, enabled = markerSel.isNotEmpty() && !playing, contentPadding = mb, modifier = Modifier.height(32.dp)) { Text("Дублировать", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::mirrorSelectedMarkers, enabled = markerSel.size >= 2 && !playing, contentPadding = mb, modifier = Modifier.height(32.dp)) { Text("Зеркало", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::openSelectedCurveEditor, enabled = markerSel.isNotEmpty() && !playing, contentPadding = mb, modifier = Modifier.height(32.dp)) { Text("Общая кривая", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::deleteSelectedMarkers, enabled = markerSel.isNotEmpty() && !playing, contentPadding = mb, modifier = Modifier.height(32.dp)) { Text("Удалить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        // ---- Режим ключей (пакет 10): переход и выделение по ключам всех слоёв ----
        if (!collapsed && keyMode) Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            val kb = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            Text("Слоёв с ключами: ${keyedIds.size}", fontSize = 12.sp, maxLines = 1, softWrap = false)
            OutlinedButton(onClick = { vm.jumpAnyKey(false) }, enabled = !playing, contentPadding = kb, modifier = Modifier.height(32.dp)) { Text("◀ Ключ", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { vm.jumpAnyKey(true) }, enabled = !playing, contentPadding = kb, modifier = Modifier.height(32.dp)) { Text("Ключ ▶", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::selectKeysAtFrame, enabled = !playing, contentPadding = kb, modifier = Modifier.height(32.dp)) { Text("Ключи кадра", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::selectAllKeys, enabled = !playing, contentPadding = kb, modifier = Modifier.height(32.dp)) { Text("Все ключи", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            TextButton(onClick = vm::toggleKeyMode, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) { Text("Все слои", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        // ---- Панель инструментов таймлайна ----
        if (!collapsed && (!phone || toolsOpen)) Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            TlIcon(Icons.Default.Adjust, "Магнит", active = magnet) { vm.toggleMagnet() }
            TlIcon(Icons.Default.AccountTree, "Иерархия трансформаций") { showHierarchy = true }
            TlIcon(Icons.Default.Timeline, "Режим ключей", active = keyMode) { vm.toggleKeyMode() }
            TlIcon(Icons.Default.FormatListBulleted, "Список ключей", enabled = selected != null) { showKeyList = true }
            // f(x): плавность сегмента от ключа выделенного слоя на текущем кадре
            Box {
                TlIcon(Icons.Default.ShowChart, "Кривые (Easing)", enabled = !playing) { vm.openCurveEditor(true) }
                DropdownMenu(expanded = easingMenu, onDismissRequest = { easingMenu = false }) {
                    val items = listOf(
                        EasingType.LINEAR to "Линейно",
                        EasingType.EASE_IN to "Плавное начало",
                        EasingType.EASE_OUT to "Плавный конец",
                        EasingType.EASE_IN_OUT to "Плавно и там и там",
                        EasingType.STEP to "Ступенька (Hold)"
                    )
                    for ((type, title) in items) {
                        DropdownMenuItem(
                            text = { Text(title) },
                            leadingIcon = { if (type == easing) Icon(Icons.Default.Check, null) },
                            onClick = { vm.setEasing(type); easingMenu = false }
                        )
                    }
                }
            }
            TlIcon(Icons.Default.Brush, "Добавить пустой кадр") { vm.addEmptyFrame() }
            TlIcon(Icons.Default.ContentCopy, "Дублировать кадр") { vm.duplicateFrame() }
            TlIcon(Icons.Default.DeleteSweep, "Удалить кадр") { vm.deleteFrame() }
            TlIcon(Icons.Default.OpenInFull, "Расширить клип", enabled = selected != null && !playing) { vm.extendSelectedClips() }
            // Ножницы: разрезать клип выделенного слоя на плейхеде на две независимые части
            TlIcon(Icons.Default.ContentCut, "Разрезать клип", enabled = selected != null && !playing) { vm.splitSelected() }
            TlIcon(Icons.Default.ContentCopy, "Дублировать слой", enabled = selected != null && !playing) { vm.duplicateSelected() }
            // Лесенка: клипы выделенных слоёв встают со сдвигом на 1 кадр
            TlIcon(Icons.Default.Stairs, "Лесенка", enabled = selection.size >= 2 && !playing) { vm.staggerSelected() }
            // Маркеры кадров: перейти к предыдущему, поставить/убрать, перейти к следующему
            TlIcon(Icons.Default.SkipPrevious, "Предыдущий маркер", enabled = markers.isNotEmpty()) { vm.jumpMarker(false) }
            TlIcon(Icons.Default.Flag, "Маркер кадра", active = markerHere) { vm.toggleMarker() }
            TlIcon(Icons.Default.SkipNext, "Следующий маркер", enabled = markers.isNotEmpty()) { vm.jumpMarker(true) }
            TlIcon(Icons.Default.RestartAlt, "Сбросить трансформацию", enabled = selected != null && !playing) { vm.resetLayerTransform() }
            // Сцены (пакет 9): создание, переход, переименование, удаление; Play и экспорт по сцене
            TlIcon(Icons.Default.Movie, "Сцены", active = scenes.isNotEmpty()) { showScenes = true }
            // Шестерёнка: длительность проекта и FPS
            TlIcon(Icons.Default.Settings, "Длительность и FPS") { showSettings = true }
        }

        if (!collapsed) {
        // ---- Линейка и дерево слоёв ----
        val vScroll = rememberScrollState()
        var vViewportPx by remember { mutableIntStateOf(0) }
        val rowPxScroll = with(LocalDensity.current) { ROW_H.toPx() }
        LaunchedEffect(selected) {
            val i = shownRows.indexOfFirst { it.layer.id == selected }
            if (i >= 0) vScroll.animateScrollTo((i * rowPxScroll).toInt().coerceAtMost(vScroll.maxValue))
        }
        val contentW = frameW * duration
        val measurer = rememberTextMeasurer()
        val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        val textColor = MaterialTheme.colorScheme.onSurface
        val accent = MaterialTheme.colorScheme.primary
        val playheadColor = Color(0xFFE53935)
        val markerColor = Color(0xFFFB8C00)
        val cellColor = Color(0xFF26C6DA)
        var viewportPx by remember { mutableIntStateOf(0) }
        val frameState = frameS
        val frameWPxNow = rememberUpdatedState(frameWPx)

        // После щипка playhead остаётся на том же месте экрана
        // открытие сцены: масштаб «сцена во всё окно» и прокрутка к её началу (пакет 10)
        LaunchedEffect(activeSc?.id, activeSc?.length, viewportPx) {
            val sc = activeSc ?: return@LaunchedEffect
            if (viewportPx <= 0) return@LaunchedEffect
            val dp = (viewportPx / density.density / sc.length.coerceAtLeast(1)).coerceIn(MIN_FRAME_DP, MAX_FRAME_DP)
            anchorX = 0f
            frameDp = dp
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            hScroll.scrollTo((sc.start * with(density) { dp.dp.toPx() }).toInt().coerceIn(0, hScroll.maxValue))
        }
        LaunchedEffect(frameDp) {
            if (anchorX != 0f || hScroll.value != 0) hScroll.scrollTo((frame * frameWPx - anchorX).toInt().coerceAtLeast(0))
        }

        // Автопрокрутка: playhead всегда в видимой части (при Play, перемотке и вводе кадра)
        LaunchedEffect(viewportPx) {
            snapshotFlow { frameS.value }.collect { f ->
                val w = frameWPxNow.value
                TimelineMath.autoScrollTarget(f * w, hScroll.value, viewportPx, (w * 3).toInt())
                    ?.let { hScroll.scrollTo(it) }
            }
        }

        if (scenes.isNotEmpty() && editingSym == null) SceneStrip(vm, scenes, activeSc ?: sceneNow, activeSc != null, frameWPx, contentW, NAME_W, hScroll, viewportPx) { showScenes = true }
        Row {
            Box(Modifier.width(NAME_W).height(RULER_H))
            // Линейка не листается сама (enabled=false): тап и перетаскивание по ней - перемотка.
            // Листает таймлайн жест по полосам слоёв ниже.
            Box(
                Modifier
                    // размер измеряется ДО горизонтальной прокрутки: это ширина видимого окна, а не всего содержимого
                    .onSizeChanged { viewportPx = it.width }
                    .horizontalScroll(hScroll, enabled = false)
            ) {
                Canvas(
                    Modifier
                        .width(contentW)
                        .height(RULER_H)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(frameWPx, duration, markers) {
                            detectTapGestures { pt ->
                                // тап рядом с маркером прыгает точно на него
                                val snap = markers.minByOrNull { abs(it * frameWPx - pt.x) }?.takeIf { abs(it * frameWPx - pt.x) < 14.dp.toPx() }
                                vm.scrubTo(snap ?: vm.snapFrame((pt.x / frameWPx).roundToInt(), (10.dp.toPx() / frameWPx).roundToInt().coerceAtLeast(1)))
                            }
                        }
                        .pointerInput(frameWPx, duration) {
                            // палец у края линейки: таймлайн сам прокручивается в эту сторону, пока палец удерживается
                            kotlinx.coroutines.coroutineScope {
                                val sc = this
                                var lastX = 0f
                                var job: Job? = null
                                fun scrub() = vm.scrubTo(vm.snapFrame((lastX / frameWPx).roundToInt(), (10.dp.toPx() / frameWPx).roundToInt().coerceAtLeast(1)))
                                detectHorizontalDragGestures(
                                    onDragStart = { p ->
                                        lastX = p.x
                                        job = sc.edgeScroll(hScroll, { viewportPx }, 56.dp.toPx(), 22.dp.toPx(), { lastX }) { used -> lastX += used; scrub() }
                                    },
                                    onDragEnd = { job?.cancel() }, onDragCancel = { job?.cancel() }
                                ) { change, _ ->
                                    lastX = change.position.x
                                    scrub()
                                    change.consume()
                                }
                            }
                        }
                ) {
                    // рисуем только видимую часть линейки (длинные проекты - тысячи кадров)
                    val first = ((hScroll.value / frameWPx).toInt() - 2).coerceAtLeast(0)
                    val last = (first + (viewportPx / frameWPx).toInt() + 5).coerceAtMost(duration - 1)
                    // подписи секунд не чаще, чем раз в ~70dp; мелкие деления кадров - только при крупном масштабе
                    val labelEvery = max(1, ceil(70.dp.toPx() / (fps * frameWPx)).toInt())
                    for (f in first..last) {
                        val x = f * frameWPx
                        val major = f % fps == 0
                        if (!major && frameDp < 5f) continue
                        drawLine(lineColor, Offset(x, size.height * if (major) 0.4f else 0.75f), Offset(x, size.height))
                        if (major && (f / fps) % labelEvery == 0) {
                            drawText(
                                measurer, TimeFormat.formatSeconds(f, fps),
                                topLeft = Offset(x + 3f, 0f),
                                style = TextStyle(fontSize = 10.sp, color = textColor)
                            )
                        }
                    }
                    // Плейхед рисуется ПОД маркерами: раньше красная линия закрывала линию маркера, и был виден только
                    // флажок справа - казалось, что маркер встал правее текущего кадра. Теперь метка симметрична.
                    // вне открытой сцены линейка притушена (пакет 10)
                    activeSc?.let { sc ->
                        val shade = Color.Black.copy(alpha = 0.35f)
                        if (sc.start > 0) drawRect(shade, Offset.Zero, androidx.compose.ui.geometry.Size(sc.start * frameWPx, size.height))
                        if (sc.end < duration) drawRect(shade, Offset(sc.end * frameWPx, 0f), androidx.compose.ui.geometry.Size((duration - sc.end) * frameWPx, size.height))
                    }
                    drawPlayhead(frame * frameWPx, playheadColor)
                    for (m in markers) drawMarkerTag(m * frameWPx, markerColor, 5.dp.toPx(), 10.dp.toPx())
                }
            }
        }

        // ---- Полоса маркеров открытого символа: флажки с именами, диапазоны форм, выделение и перенос как у ключей ----
        if (editingSym != null) Row {
            Row(Modifier.width(NAME_W).height(MARKER_H).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Flag, null, Modifier.size(14.dp), tint = Color(0xFFAB47BC))
                Text("Маркеры форм", modifier = Modifier.padding(start = 3.dp), fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
            val markersNow = rememberUpdatedState(symMarkers)
            val mSelNow = rememberUpdatedState(markerSel)
            val multiNow = rememberUpdatedState(multi)
            var mDrag by remember { mutableIntStateOf(0) }
            Box(Modifier.horizontalScroll(hScroll, enabled = false)) {
                Canvas(
                    Modifier
                        .width(contentW)
                        .height(MARKER_H)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .pointerInput(frameWPx) {
                            val grab = 14.dp.toPx()
                            fun hitAt(x: Float): Int? = markersNow.value.minByOrNull { abs(it.frame * frameWPx - x) }
                                ?.takeIf { abs(it.frame * frameWPx - x) <= grab }?.frame
                            detectTapGestures { pt ->
                                val hit = hitAt(pt.x)
                                when {
                                    hit == null -> { vm.clearMarkerSelection(); vm.scrubTo((pt.x / frameWPx).roundToInt().coerceIn(0, duration - 1)) }
                                    // пакет 8: повторный тап по выделенному маркеру снимает с него выделение
                                    hit in mSelNow.value -> com.vibetoon.domain.anim.QuickDeselect.afterTap(mSelNow.value, hit)?.let { vm.selectMarkers(it) }
                                    multiNow.value -> vm.toggleMarkerSelected(hit)
                                    else -> { vm.selectMarkers(setOf(hit)); vm.scrubTo(hit) }
                                }
                            }
                        }
                        .pointerInput(frameWPx, duration) {
                            val grab = 14.dp.toPx()
                            var startX = 0f
                            var acc = 0f
                            var active = false
                            detectDragGesturesAfterLongPress(
                                onDragStart = { p ->
                                    val hit = markersNow.value.minByOrNull { abs(it.frame * frameWPx - p.x) }?.takeIf { abs(it.frame * frameWPx - p.x) <= grab }?.frame
                                    active = hit != null
                                    if (hit != null) {
                                        if (hit !in mSelNow.value) vm.selectMarkers(mSelNow.value + hit)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    startX = p.x; acc = 0f; mDrag = 0
                                },
                                onDragEnd = { if (active && mDrag != 0) vm.moveSelectedMarkers(mDrag); mDrag = 0; active = false },
                                onDragCancel = { mDrag = 0; active = false }
                            ) { change, d ->
                                if (!active) return@detectDragGesturesAfterLongPress
                                change.consume()
                                acc += d.x
                                val sel = mSelNow.value
                                if (sel.isNotEmpty()) mDrag = (acc / frameWPx).roundToInt().coerceIn(-sel.min(), duration - 1 - sel.max())
                            }
                        }
                ) {
                    val purple = Color(0xFFAB47BC)
                    val sorted = symMarkers.sortedBy { it.frame }
                    val real = sorted.filter { !it.isAlias }
                    // диапазон формы: от маркера до следующего (последний - до конца); чередование оттенков разделяет соседние формы.
                    // Маркеры-ссылки (копии) своей формы не имеют - диапазон рисуется только у настоящих маркеров.
                    real.forEachIndexed { i, mk ->
                        val x0 = mk.frame * frameWPx
                        val x1 = (real.getOrNull(i + 1)?.frame ?: duration) * frameWPx
                        drawRect(purple.copy(alpha = if (i % 2 == 0) 0.16f else 0.08f), Offset(x0, size.height * 0.55f), androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), size.height * 0.4f))
                    }
                    drawPlayhead(frame * frameWPx, playheadColor)
                    val shortM = com.vibetoon.domain.project.SymbolForms.compactLabels(sorted.map { it.name })
                    for ((mi, mk) in sorted.withIndex()) {
                        val picked = mk.frame in markerSel
                        val x = (mk.frame + if (picked) mDrag else 0) * frameWPx
                        val c = if (picked) Color(0xFFFB8C00) else if (mk.isAlias) purple.copy(alpha = 0.55f) else purple
                        if (picked) drawCircle(c.copy(alpha = 0.35f), size.height * 0.45f, Offset(x, size.height / 2f))
                        drawMarkerTag(x, c, 5.dp.toPx(), 9.dp.toPx())
                        val nm = shortM[mk.name] ?: mk.name
                        // подпись не длиннее расстояния до следующего маркера (с многоточием), иначе текст наезжал на соседей
                        val nextX = sorted.getOrNull(mi + 1)?.let { it.frame * frameWPx } ?: size.width
                        val room = nextX - x - 6.dp.toPx()
                        if (room > 10.dp.toPx()) drawText(measurer, if (mk.isAlias) "↪$nm" else nm, topLeft = Offset(x + 4.dp.toPx(), size.height * 0.45f),
                            style = TextStyle(fontSize = 9.sp, color = textColor), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            size = androidx.compose.ui.geometry.Size(room, size.height * 0.55f))
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .then(if (fill) Modifier.weight(1f) else Modifier.height(ROW_H * visibleRows))
                .onSizeChanged { vViewportPx = it.height }
                .verticalScroll(vScroll)
        ) {
            // Дерево слоёв: отступы по вложенности, группы сворачиваются тапом, длинные имена обрезаются
            Column(Modifier.width(NAME_W)) {
                // Drag-and-drop порядка слоёв: тянем за ручку ≡ в конце строки, бросок на слой ставит на его место, на группу - внутрь
                var dragId by remember { mutableStateOf<Int?>(null) }
                var dragDy by remember { mutableFloatStateOf(0f) }
                val rowsForDrag = rememberUpdatedState(shownRows)
                val rowPx = with(density) { ROW_H.toPx() }
                for (r in shownRows) {
                    val l = r.layer
                    Row(
                        Modifier
                            .width(NAME_W)
                            .height(ROW_H)
                            .graphicsLayer { translationY = if (dragId == l.id) dragDy else 0f; alpha = if (dragId == l.id) 0.6f else 1f }
                            .background(if (l.id in selection) accent.copy(alpha = 0.25f) else Color.Transparent)
                            .combinedClickable(onClick = { tapRow(l.id, r.isGroup) }, onLongClick = { longRow(l.id) })
                            .padding(start = (4 + INDENT_DP * r.depth).dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (r.isGroup) Icon(if (r.expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                        else {
                            // миниатюра слоя слева от имени: сразу видно, чей это клип
                            val thumb = vm.thumbs[l.id]
                            LaunchedEffect(l.id, thumb) {
                                var tries = 0
                                while (vm.thumbs[l.id] == null && tries < 40) { vm.requestThumb(l.id); kotlinx.coroutines.delay(800); tries++ }
                            }
                            Box(
                                Modifier.padding(end = 4.dp).size(22.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                if (thumb != null) androidx.compose.foundation.Image(
                                    thumb, null, modifier = Modifier.fillMaxSize().thumbnailChecker(com.vibetoon.ui.theme.LocalAppSettings.current?.checkerThumbnails == true), contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                )
                            }
                        }
                        Text(
                            l.name,
                            fontSize = 12.sp,
                            fontWeight = if (r.isGroup) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.width(36.dp).fillMaxHeight().pointerInput(l.id) {
                                // нажатие на ручку забирается сразу: контекстное меню строки не открывается, порядок слоёв тянется с первого касания
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    dragId = l.id; dragDy = 0f
                                    val list = rowsForDrag.value
                                    val start = list.indexOfFirst { it.layer.id == l.id }
                                    val downY = down.position.y
                                    // Пакет 8: палец у верхнего/нижнего края окна дорожек - список прокручивается сам
                                    val auto = keyScope.dragEdgeAutoScroll(vScroll, { vViewportPx }, 40.dp.toPx(), 16.dp.toPx(),
                                        { com.vibetoon.domain.input.EdgeAutoScroll.contentPos(start, rowPx, downY, dragDy) - vScroll.value }) { used -> dragDy += used }
                                    val lifted = try { drag(down.id) { ch -> dragDy += ch.positionChange().y; ch.consume() } } finally { auto.cancel() }
                                    if (lifted && start >= 0) {
                                        val t = list.getOrNull(com.vibetoon.domain.input.EdgeAutoScroll.targetIndex(start, dragDy, rowPx, list.size))
                                        if (t != null && t.layer.id != l.id) vm.dropLayer(l.id, t.layer.id)
                                    }
                                    dragId = null; dragDy = 0f
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) { Text("≡", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) }
                    }
                }
                // Кости с ключами (в режиме «Кости» - все): имя кости и значок ◆
                for (b in boneRows) {
                    Row(
                        Modifier
                            .width(NAME_W).height(ROW_H)
                            .background(if (b.id in selection) BONE_KEY_COLOR.copy(alpha = 0.25f) else Color.Transparent)
                            .combinedClickable(onClick = { vm.selectBoneRow(b.id) })
                            .padding(start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("◆", fontSize = 12.sp, color = BONE_KEY_COLOR)
                        Text(com.vibetoon.ui.i18n.boneDisplayName(b.name), fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
                // Аудио: невизуальные дорожки с именем файла (звук играет при Play)
                for (a in audio) {
                    Row(
                        Modifier
                            .width(NAME_W).height(ROW_H)
                            .background(if (a.id in selection) accent.copy(alpha = 0.25f) else Color.Transparent)
                            .combinedClickable(onClick = { if (multi) vm.toggleSelect(a.id) else vm.selectLayer(a.id) }, onLongClick = { longRow(a.id) })
                            .padding(start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MusicNote, null, Modifier.size(14.dp))
                        Text(a.name, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
            // Полосы слоёв с ромбами ключей; жест по ним листает таймлайн по горизонтали
            val marquee = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
            val animNow = rememberUpdatedState(animation)
            val keySelNow = rememberUpdatedState(keySel)
            val timeKeysNow = rememberUpdatedState(timeKeyMap)
            val layerRowIds = remember(shownRows) { shownRows.map { it.layer.id } }
            val layerRowIdsNow = rememberUpdatedState(layerRowIds)
            val boneRowIds = remember(boneRows) { boneRows.map { it.id } }
            val boneRowIdsNow = rememberUpdatedState(boneRowIds)
            // строка ключей по индексу: сначала слои и группы, затем кости
            fun keyRowId(index: Int): Int? = com.vibetoon.domain.anim.BoneKeys.rowIdAt(index, layerRowIdsNow.value, boneRowIdsNow.value)
            fun keyRowCount() = layerRowIdsNow.value.size + boneRowIdsNow.value.size
            // ключи слоя + ключи времени экземпляра символа: оба вида выделяются, тянутся и копируются одинаково
            fun rowKeyFrames(id: Int): Collection<Int> {
                if (id >= com.vibetoon.domain.project.BONE_ID_BASE && id in boneRowIdsNow.value)
                    return com.vibetoon.domain.anim.BoneKeys.frames(id, animNow.value.tracks)
                val tk = timeKeysNow.value[id] ?: return TrackGroups.frames(id, animNow.value.tracks)
                return TrackGroups.frames(id, animNow.value.tracks).toHashSet().apply { tk.forEach { add(it.frame) } }
            }
            Box(Modifier.horizontalScroll(hScroll)) {
                Column(
                    Modifier.then(
                        if (selectMode || playing) Modifier else Modifier.pointerInput(frameWPx, duration) {
                            val rowH = ROW_H.toPx()
                            val grab = 14.dp.toPx()
                            val edge = 56.dp.toPx()
                            val maxStep = 22.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val rowId = keyRowId((down.position.y / rowH).toInt())
                                val frameHit = rowId?.let { rowKeyFrames(it)
                                    .minByOrNull { f -> abs(f * frameWPx - down.position.x) }
                                    ?.takeIf { f -> abs(f * frameWPx - down.position.x) <= grab } }
                                // пакет 8: короткий тап по пустому месту дорожек снимает выделение ключей, по выделенному ключу -
                                // снимает выделение с него; долгое нажатие, как раньше, добавляет ключ и тянет группу
                                if ((rowId == null || frameHit == null) && keySelNow.value.isEmpty()) return@awaitEachGesture
                                var tapUp: androidx.compose.ui.input.pointer.PointerInputChange? = null
                                val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val c = ev.changes.firstOrNull { it.id == down.id }
                                        if (c != null && !c.pressed && ev.changes.none { it.pressed }) { tapUp = c; return@withTimeoutOrNull TAP_UP }
                                        if (c == null || !c.pressed || ev.changes.count { it.pressed } != 1 ||
                                            (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull TAP_CANCEL
                                    }
                                    @Suppress("UNREACHABLE_CODE") TAP_CANCEL
                                } ?: TAP_LONG
                                if (rowId == null || frameHit == null) {
                                    // пустое место: только тап снимает выделение, обычное действие тапа (выбор слоя) остаётся
                                    if (outcome == TAP_UP) com.vibetoon.domain.anim.QuickDeselect.afterTap(keySelNow.value, null)?.let { vm.selectKeys(it) }
                                    return@awaitEachGesture
                                }
                                if (outcome == TAP_UP) {
                                    val next = com.vibetoon.domain.anim.QuickDeselect.afterTap(keySelNow.value, rowId to frameHit)
                                    if (next != null && (rowId to frameHit) in keySelNow.value) {
                                        tapUp?.consume()   // строка не получает тап: ключ просто снят с выделения
                                        vm.selectKeys(next)
                                    }
                                    return@awaitEachGesture
                                }
                                if (outcome != TAP_LONG) return@awaitEachGesture
                                val hit = rowId to frameHit
                                val group = keySelNow.value + hit
                                vm.selectKeysFocused(group)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val low = group.minOf { it.second }
                                val high = group.maxOf { it.second }
                                var lastX = down.position.x
                                var dx = 0
                                var cancelledDrag = false
                                fun updateDelta() {
                                    dx = ((lastX - down.position.x) / frameWPx).roundToInt().coerceIn(-low, duration - 1 - high)
                                    keyDragDelta = dx
                                }
                                val job = keyScope.edgeScroll(hScroll, { viewportPx }, edge, maxStep, { lastX }) { used -> lastX += used; updateDelta() }
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val c = ev.changes.firstOrNull { it.id == down.id }
                                        if (c == null || ev.changes.any { it.id != down.id && it.pressed }) { cancelledDrag = true; break }
                                        lastX = c.position.x
                                        updateDelta()
                                        c.consume()
                                        if (!c.pressed) break
                                    }
                                } finally { job.cancel(); keyDragDelta = 0 }
                                if (!cancelledDrag && dx != 0) vm.moveSelectedKeys(dx, singleGesture = true)
                            }
                        }
                    ).then(
                        if (!selectMode) Modifier else Modifier.pointerInput(frameWPx) {
                            val rowH = ROW_H.toPx()
                            val grab = 14.dp.toPx()
                            fun keysIn(r: androidx.compose.ui.geometry.Rect): Set<Pair<Int, Int>> {
                                val out = HashSet<Pair<Int, Int>>()
                                for (i in 0 until keyRowCount()) {
                                    val cy = (i + 0.5f) * rowH
                                    val id = keyRowId(i)
                                    if (id == null || cy < r.top || cy > r.bottom) continue
                                    rowKeyFrames(id).forEach { f ->
                                        val kx = f * frameWPx
                                        if (kx >= r.left && kx <= r.right) out += id to f
                                    }
                                }
                                return out
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val rowIdx = (down.position.y / rowH).toInt()
                                val hit = keyRowId(rowIdx)?.let { rid ->
                                    rowKeyFrames(rid)
                                        .minByOrNull { abs(it * frameWPx - down.position.x) }
                                        ?.takeIf { abs(it * frameWPx - down.position.x) <= grab }
                                        ?.let { rid to it }
                                }
                                val moving = hit != null
                                val wasPicked = hit != null && hit in keySelNow.value
                                if (hit != null && hit !in keySelNow.value) vm.selectKeysFocused(setOf(hit))
                                var applied = 0
                                var dragged = false
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    val d = c.position - down.position
                                    if (!dragged && d.getDistance() > viewConfiguration.touchSlop) dragged = true
                                    if (dragged) {
                                        c.consume()
                                        if (moving) {
                                            val target = (d.x / frameWPx).roundToInt()
                                            val group = if (hit != null && hit !in keySelNow.value) setOf(hit) else keySelNow.value
                                            if (group.isNotEmpty()) {
                                                applied = target.coerceIn(-group.minOf { it.second }, duration - 1 - group.maxOf { it.second })
                                                keyDragDelta = applied
                                            }
                                        } else {
                                            marquee.value = androidx.compose.ui.geometry.Rect(
                                                minOf(down.position.x, c.position.x), minOf(down.position.y, c.position.y),
                                                maxOf(down.position.x, c.position.x), maxOf(down.position.y, c.position.y)
                                            )
                                        }
                                    }
                                }
                                keyDragDelta = 0
                                if (moving && dragged && applied != 0) vm.moveSelectedKeys(applied, singleGesture = true)
                                // пакет 8: повторный тап по выделенному ключу снимает с него выделение
                                if (moving && !dragged && wasPicked && hit != null) com.vibetoon.domain.anim.QuickDeselect.afterTap(keySelNow.value, hit)?.let { vm.selectKeys(it) }
                                val m = marquee.value
                                if (!moving) {
                                    if (dragged && m != null) vm.selectKeysFocused(keysIn(m)) else vm.clearKeySelection()
                                }
                                marquee.value = null
                            }
                        }
                    )
                ) {
                    for (r in shownRows) {
                        val l = r.layer
                        // у группы тоже видны её собственные ключи (трансформация и Вращение/Качание/Ось растяжения группы)
                        val keys = animation.tracks[l.id]?.keys.orEmpty()
                        // ключи остальных дорожек слоя (Warp, вершины Mesh, пины, Perspective, Turbulent, эффекты): бледные ромбы
                        // пакет 8: считается при изменении анимации, а не при каждой перекомпоновке (60+ строк)
                        val warpFrames = remember(animation, l.id) { TrackGroups.frames(l.id, animation.tracks) - keys.mapTo(HashSet()) { it.frame } }
                        val isSel = l.id in selection
                        val clip = clips[l.id] ?: vm.clipOf(l.id)
                        val clipNow = rememberUpdatedState(clip)
                        val isSelNow = rememberUpdatedState(isSel)
                        Canvas(
                            Modifier
                                .width(contentW)
                                .height(ROW_H)
                                // Края полоски тянутся пальцем (начало/конец клипа), у выбранного слоя полоска целиком
                                // переносится за середину: меняется время начала, длительность остаётся
                                .clipGestures(l.id, frameWPx, clipNow, isSelNow, vm)
                                .background(
                                    when {
                                        isSel -> accent.copy(alpha = 0.12f)
                                        r.isGroup -> lineColor.copy(alpha = 0.08f)
                                        else -> Color.Transparent
                                    }
                                )
                                .combinedClickable(onClick = { tapRow(l.id, r.isGroup) }, onLongClick = { longRow(l.id) })
                        ) {
                            drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height))
                            for (m in markers) drawLine(markerColor.copy(alpha = 0.5f), Offset(m * frameWPx, 0f), Offset(m * frameWPx, size.height), strokeWidth = 1.5f)
                            for (sm in symMarkers) drawLine(Color(0xFFAB47BC).copy(alpha = 0.6f), Offset(sm.frame * frameWPx, 0f), Offset(sm.frame * frameWPx, size.height), strokeWidth = 1.5f)   // маркеры кадров открытого символа
                            // цветная полоска клипа с ручками по краям
                            val bx0 = clip.start * frameWPx
                            val bx1 = clip.end * frameWPx
                            drawRoundRect(
                                color = accent.copy(alpha = if (isSel) 0.38f else 0.2f),
                                topLeft = Offset(bx0, size.height * 0.12f),
                                size = androidx.compose.ui.geometry.Size((bx1 - bx0).coerceAtLeast(2f), size.height * 0.76f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                            )
                            // пакет 11: видео - плёнка кадров источника (декодируются в фоне) и волна его звука внизу полосы
                            if (l.id in videoIds) {
                                val top = size.height * 0.12f
                                val bh = size.height * 0.76f
                                val v0 = hScroll.value.toFloat()
                                val v1 = v0 + viewportPx
                                if (stripVer >= 0) clipRect(bx0, top, bx1, top + bh) {
                                    val tileW = (bh * vm.videoAspect(l.id)).coerceIn(12.dp.toPx(), 140.dp.toPx())
                                    for (t in vm.filmstripTiles(l.id, clip, frameWPx, tileW, v0, v1)) {
                                        val img = vm.filmstripFrame(l.id, t.sourceMs) ?: continue
                                        drawImage(img, dstOffset = IntOffset(t.x.toInt(), top.toInt()),
                                            dstSize = IntSize(kotlin.math.ceil(tileW).toInt().coerceAtLeast(1), bh.toInt().coerceAtLeast(1)), alpha = if (isSel) 0.95f else 0.75f)
                                    }
                                    waves[l.id]?.let { pk ->
                                        val step = 2.dp.toPx()
                                        val base = top + bh
                                        val hMax = bh * 0.35f
                                        var x = max(bx0, v0)
                                        val xe = kotlin.math.min(bx1, v1)
                                        while (x < xe) {
                                            val amp = com.vibetoon.domain.project.WaveformMath.amplitude(pk, clip, fps, x / frameWPx, (x + step) / frameWPx)
                                            if (amp > 0f) drawLine(Color(0xFF26C6DA), Offset(x, base), Offset(x, base - max(1f, amp * hMax)), strokeWidth = step * 0.75f)
                                            x += step
                                        }
                                    }
                                }
                            }
                            val hw = 5.dp.toPx()
                            drawRoundRect(accent, Offset(bx0, size.height * 0.12f), androidx.compose.ui.geometry.Size(hw, size.height * 0.76f), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                            drawRoundRect(accent, Offset(bx1 - hw, size.height * 0.12f), androidx.compose.ui.geometry.Size(hw, size.height * 0.76f), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                            // засечки рисунков покадрового слоя: квадрат на каждом кадре, где начинается рисунок, и полоска экспозиции до следующего
                            val cs = cellMap[l.id]
                            if (cs != null) {
                                val sorted = cs.sorted()
                                val cy = size.height * 0.3f
                                val ch = size.height * 0.4f
                                sorted.forEachIndexed { i, s ->
                                    val end = (sorted.getOrNull(i + 1) ?: bx1.div(frameWPx).toInt()).toFloat()
                                    val x0 = s * frameWPx
                                    val xe = (end * frameWPx).coerceAtLeast(x0 + frameWPx)
                                    drawRect(cellColor.copy(alpha = 0.25f), Offset(x0, cy), androidx.compose.ui.geometry.Size(xe - x0, ch))
                                    drawRect(cellColor, Offset(x0, cy), androidx.compose.ui.geometry.Size(frameWPx.coerceAtLeast(6f), ch))
                                    drawLine(cellColor, Offset(xe, cy), Offset(xe, cy + ch), strokeWidth = 1.5f)
                                }
                            }
                            for (wf in warpFrames) {
                                val pickedW = (l.id to wf) in keySel
                                val x = (wf + if (pickedW) keyDragDelta else 0) * frameWPx
                                if (pickedW) drawCircle(Color(0xFFFB8C00).copy(alpha = 0.45f), size.height * 0.42f, Offset(x, size.height / 2f))
                                drawDiamond(x, size.height / 2f, size.height * 0.2f, if (pickedW) Color(0xFFFB8C00) else Color(0xFFFFA726))
                            }
                            // ключ с плавностью рисуется кружком, линейный - ромбом
                            for (k in keys) {
                                val picked = (l.id to k.frame) in keySel
                                val kc = if (picked) Color(0xFFFB8C00) else accent
                                val x = (k.frame + if (picked) keyDragDelta else 0) * frameWPx
                                if (picked) drawCircle(kc.copy(alpha = 0.45f), size.height * 0.42f, Offset(x, size.height / 2f))
                                if (k.easing == EasingType.LINEAR) drawDiamond(x, size.height / 2f, size.height * 0.28f, kc)
                                else drawCircle(kc, size.height * 0.26f, Offset(x, size.height / 2f))
                            }
                            // ключи времени символа: ровно на своём кадре (по центру, как ромбы ключей и плейхед).
                            // Раньше засечка рисовалась со сдвигом +3dp вправо и выглядела «сбоку» от красной линии.
                            timeKeyMap[l.id]?.let { tks ->
                                val purple = Color(0xFFAB47BC)
                                // отрезки одинаковой формы: сплошная полупрозрачная полоса + подпись; переход - клин к следующей форме
                                val names = instMarkers[l.id].orEmpty()
                                val short = instShort[l.id].orEmpty()
                                for (run in com.vibetoon.domain.project.SymbolMath.formRuns(tks, duration)) {
                                    val x0 = run.start * frameWPx
                                    val x1 = run.end * frameWPx
                                    val top = size.height * 0.62f
                                    val h = size.height * 0.26f
                                    if (run.hold) {
                                        drawRoundRect(purple.copy(alpha = 0.22f), Offset(x0, top), androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(2f), h), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                                        drawLine(purple.copy(alpha = 0.7f), Offset(x0, top + h / 2f), Offset(x1, top + h / 2f), strokeWidth = 1.5f)
                                    } else {
                                        val wedge = Path().apply { moveTo(x0, top + h); lineTo(x1, top); lineTo(x1, top + h); close() }
                                        drawPath(wedge, purple.copy(alpha = 0.3f))
                                    }
                                    // подпись: короткое имя формы («30» вместо «Обычный слой 30»), не шире своего отрезка -
                                    // соседние подписи больше не наезжают друг на друга
                                    val full = com.vibetoon.domain.project.SymbolForms.labelFor(names, run.value)?.name
                                    val label = full?.let { short[it] ?: it } ?: "${run.value + 1}"
                                    val room = (x1 - x0) - 8.dp.toPx()
                                    if (room > 12.dp.toPx()) drawText(measurer, label, topLeft = Offset(x0 + 5.dp.toPx(), top - 1.dp.toPx()),
                                        style = TextStyle(fontSize = 8.sp, color = textColor.copy(alpha = 0.85f)), maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        size = androidx.compose.ui.geometry.Size(room, size.height * 0.4f))
                                }
                                for (tk in tks) {
                                    val picked = (l.id to tk.frame) in keySel
                                    val x = (tk.frame + if (picked) keyDragDelta else 0) * frameWPx
                                    val c = if (picked) Color(0xFFFB8C00) else purple
                                    if (picked) drawCircle(c.copy(alpha = 0.45f), size.height * 0.42f, Offset(x, size.height / 2f))
                                    val w = 6.dp.toPx()
                                    // песочные часы = ключ времени; Hold - сплошные, плавный переход - контур
                                    val glass = Path().apply {
                                        moveTo(x - w / 2f, size.height * 0.14f); lineTo(x + w / 2f, size.height * 0.14f); lineTo(x, size.height * 0.5f)
                                        lineTo(x + w / 2f, size.height * 0.86f); lineTo(x - w / 2f, size.height * 0.86f); lineTo(x, size.height * 0.5f); close()
                                    }
                                    if (tk.ease == com.vibetoon.domain.project.TimeEase.HOLD) drawPath(glass, c)
                                    else drawPath(glass, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                                }
                            }
                            drawPlayhead(frame * frameWPx, playheadColor)
                        }
                    }
                    // Строки костей: ключи позы - ромбы ◆ с белой обводкой (отличаются от ключей слоёв); тап по ромбу
                    // выбирает кость и ключ и переносит плейхед на кадр ключа, тап мимо - выбирает кость.
                    for (b in boneRows) {
                        val bFrames = remember(animation, b.id) { com.vibetoon.domain.anim.BoneKeys.frames(b.id, animation.tracks) }
                        val bFramesNow = rememberUpdatedState(bFrames)
                        val bSel = b.id in selection
                        Canvas(
                            Modifier
                                .width(contentW).height(ROW_H)
                                .background(if (bSel) BONE_KEY_COLOR.copy(alpha = 0.12f) else BONE_KEY_COLOR.copy(alpha = 0.04f))
                                .pointerInput(b.id, frameWPx) {
                                    detectTapGestures { pos ->
                                        val hitF = com.vibetoon.domain.anim.BoneKeys.hit(bFramesNow.value, pos.x, frameWPx, 14.dp.toPx())
                                        if (hitF != null) vm.selectBoneKey(b.id, hitF) else vm.selectBoneRow(b.id)
                                    }
                                }
                        ) {
                            drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height))
                            drawLine(BONE_KEY_COLOR.copy(alpha = 0.35f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 1f)
                            for (m in markers) drawLine(markerColor.copy(alpha = 0.5f), Offset(m * frameWPx, 0f), Offset(m * frameWPx, size.height), strokeWidth = 1.5f)
                            for (f in bFrames) {
                                val picked = (b.id to f) in keySel
                                val x = (f + if (picked) keyDragDelta else 0) * frameWPx
                                if (picked) drawCircle(Color(0xFFFB8C00).copy(alpha = 0.45f), size.height * 0.42f, Offset(x, size.height / 2f))
                                drawBoneKey(x, size.height / 2f, size.height * 0.3f, if (picked) Color(0xFFFB8C00) else BONE_KEY_COLOR)
                            }
                            drawPlayhead(frame * frameWPx, playheadColor)
                        }
                    }
                    // Полосы аудио: клип звука (по умолчанию длина файла), края и середина тянутся как у слоёв
                    for (a in audio) {
                        val aclip = clips[a.id] ?: vm.clipOf(a.id)
                        val aclipNow = rememberUpdatedState(aclip)
                        val aSelNow = rememberUpdatedState(a.id in selection)
                        Canvas(
                            Modifier
                                .width(contentW).height(ROW_H)
                                .clipGestures(a.id, frameWPx, aclipNow, aSelNow, vm)
                                .background(if (a.id in selection) accent.copy(alpha = 0.12f) else Color.Transparent)
                                .combinedClickable(onClick = { if (multi) vm.toggleSelect(a.id) else vm.selectLayer(a.id) }, onLongClick = { longRow(a.id) })
                        ) {
                            val x0 = aclip.start * frameWPx
                            val x1 = aclip.end * frameWPx
                            drawRoundRect(
                                color = accent.copy(alpha = 0.35f),
                                topLeft = Offset(x0, size.height * 0.2f),
                                size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(2f), size.height * 0.6f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                            )
                            // волновая форма: симметричная гистограмма пиков громкости, строго по времени источника (зум, скорость, реверс)
                            waves[a.id]?.let { pk ->
                                val step = 2.dp.toPx()
                                val vis0 = max(x0, hScroll.value.toFloat())
                                val vis1 = kotlin.math.min(x1, (hScroll.value + viewportPx).toFloat())
                                val mid = size.height / 2f
                                val half = size.height * 0.3f
                                var x = vis0
                                while (x < vis1) {
                                    val amp = com.vibetoon.domain.project.WaveformMath.amplitude(pk, aclip, fps, x / frameWPx, (x + step) / frameWPx)
                                    val h = max(1f, amp * half)
                                    drawLine(Color(0xFF26C6DA), Offset(x, mid - h), Offset(x, mid + h), strokeWidth = step * 0.75f)
                                    x += step
                                }
                                if (vis1 > vis0 && pk.isNotEmpty()) vm.waveformDrawn(a.id, pk)
                            }
                            val hw = 5.dp.toPx()
                            drawRoundRect(accent, Offset(x0, size.height * 0.2f), androidx.compose.ui.geometry.Size(hw, size.height * 0.6f), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                            drawRoundRect(accent, Offset(x1 - hw, size.height * 0.2f), androidx.compose.ui.geometry.Size(hw, size.height * 0.6f), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                            drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height))
                            drawPlayhead(frame * frameWPx, playheadColor)
                        }
                    }
                }
                // Зона захвата playhead: широкая полоса вдоль ВСЕЙ красной линии на всю высоту,
                // перетаскивать можно в любом её месте, а не только за маркер.
                Box(Modifier.matchParentSize()) {
                    marquee.value?.let { m ->
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(Color(0xFFFB8C00).copy(alpha = 0.18f), m.topLeft, m.size)
                            drawRect(Color(0xFFFB8C00), m.topLeft, m.size, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                        }
                    }
                    Box(
                        Modifier
                            .offset { IntOffset((frameWPx * frameS.value - PLAYHEAD_GRAB_W.toPx() / 2f).roundToInt(), 0) }
                            .width(PLAYHEAD_GRAB_W)
                            .fillMaxHeight()
                            .pointerInput(frameWPx, duration) {
                                // красная линия у правого (или левого) края: холст таймлайна плавно прокручивается, пока палец удерживается
                                kotlinx.coroutines.coroutineScope {
                                    val sc = this
                                    var posPx = 0f
                                    var job: Job? = null
                                    fun scrub() = vm.scrubTo(vm.snapFrame((posPx / frameWPx).roundToInt(), (10.dp.toPx() / frameWPx).roundToInt().coerceAtLeast(1)))
                                    detectHorizontalDragGestures(
                                        onDragStart = {
                                            posPx = frameState.value * frameWPx
                                            job = sc.edgeScroll(hScroll, { viewportPx }, 56.dp.toPx(), 22.dp.toPx(), { posPx }) { used -> posPx += used; scrub() }
                                        },
                                        onDragEnd = { job?.cancel() }, onDragCancel = { job?.cancel() },
                                        onHorizontalDrag = { change, dragX ->
                                            change.consume()
                                            posPx += dragX
                                            scrub()
                                        }
                                    )
                                }
                            }
                    )
                }
            }
        }
        }
    }
    if (showSettings) TimelineSettingsDialog(duration, fps, vm::setTimeline) { showSettings = false }
    if (showScenes) ScenesDialog(vm, scenes, activeSc ?: sceneNow, activeSc != null, fps, scenePlayOnly) { showScenes = false }
    if (showHierarchy) TransformHierarchyDialog(vm, selected) { showHierarchy = false }
    selected?.let { sid -> if (showKeyList) KeyListDialog(vm, sid) { showKeyList = false } }
}

/**
 * Автопрокрутка у края: пока позиция пальца [pos] (в координатах содержимого таймлайна) ближе [edgePx] к краю окна,
 * таймлайн прокручивается в эту сторону со скоростью до [maxStepPx] за тик; [onMoved] получает реально пройденный путь.
 */
// исход ожидания долгого нажатия по дорожкам ключей
private const val TAP_UP = 0
private const val TAP_CANCEL = 1
private const val TAP_LONG = 2

private fun CoroutineScope.edgeScroll(
    hScroll: ScrollState, viewportPx: () -> Int, edgePx: Float, maxStepPx: Float, pos: () -> Float, onMoved: (Float) -> Unit
): Job = launch {
    while (true) {
        delay(16)
        val sx = pos() - hScroll.value
        val vp = viewportPx()
        val v = when {
            vp > 0 && sx > vp - edgePx -> ((sx - (vp - edgePx)) / edgePx).coerceIn(0f, 1f) * maxStepPx
            sx < edgePx && hScroll.value > 0 -> -((edgePx - sx) / edgePx).coerceIn(0f, 1f) * maxStepPx
            else -> 0f
        }
        if (v != 0f) {
            val used = hScroll.scrollBy(v)
            if (used != 0f) onMoved(used)
        }
    }
}

/**
 * Поле времени в формате ММ:СС:КК. Ввод как на калькуляторе: цифры выравниваются вправо
 * ("106" -> 00:01:06), на кадр значение переводится по FPS проекта.
 */
/** Поле времени читает кадр сам: пересоздаётся оно одно, а не вся панель. */
@Composable
private fun FrameFieldHost(frame: State<Int>, fps: Int, offset: Int, max: Int, onFrame: (Int) -> Unit) =
    FrameField((frame.value - offset).coerceAtLeast(0), fps, max) { onFrame(it + offset) }

@Composable
private fun FrameField(frame: Int, fps: Int, max: Int, onFrame: (Int) -> Unit) {
    var text by remember(frame, fps) { mutableStateOf(TimeFormat.format(frame, fps)) }
    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = { v ->
            val f = TimeFormat.parseDigits(v.filter { it.isDigit() }, fps).coerceIn(0, max)
            text = TimeFormat.format(f, fps)
            onFrame(f)
        },
        singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(92.dp)
            .height(32.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 7.dp)
    )
}

/** Настройки таймлайна: общая длительность (кадры) и FPS. */
@Composable
private fun TimelineSettingsDialog(duration: Int, fps: Int, onApply: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var frames by remember { mutableStateOf(duration.toString()) }
    var rate by remember { mutableStateOf(fps.toString()) }
    val d = frames.toIntOrNull()?.coerceIn(1, TimeFormat.MAX_DURATION_FRAMES)
    val f = rate.toIntOrNull()?.coerceIn(1, TimeFormat.MAX_FPS)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки таймлайна") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = frames, onValueChange = { frames = it.filter(Char::isDigit).take(5) },
                    label = { Text("Всего кадров (до ${TimeFormat.MAX_DURATION_FRAMES})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = rate, onValueChange = { rate = it.filter(Char::isDigit).take(3) },
                    label = { Text("FPS (1-${TimeFormat.MAX_FPS})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in listOf(12, 24, 30, 60)) {
                        FilterChip(selected = rate == o.toString(), onClick = { rate = o.toString() }, label = { Text("$o") })
                    }
                }
                Text(
                    if (d != null && f != null) "Длительность: ${TimeFormat.format(d, f)} (мин:сек:кадры)" else "Введите числа",
                    fontSize = 12.sp
                )
                Text("Ключи остаются на своих номерах кадров.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        confirmButton = { TextButton(onClick = { if (d != null && f != null) { onApply(d, f); onDismiss() } }, enabled = d != null && f != null) { Text("Применить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/** Метка маркера: треугольник остриём вниз, центр ровно на кадре [x], и тонкая линия кадра поверх плейхеда. */
private fun DrawScope.drawMarkerTag(x: Float, color: Color, halfW: Float, h: Float) {
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
    val tag = Path().apply { moveTo(x - halfW, 0f); lineTo(x + halfW, 0f); lineTo(x, h); close() }
    drawPath(tag, color)
}

private fun DrawScope.drawPlayhead(x: Float, color: Color) {
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
}

/** Цвет ключей костей на таймлайне (голубой, как суставы костей на холсте). */
private val BONE_KEY_COLOR = Color(0xFF26C6DA)

/** Ключ кости: ромб ◆ с белой обводкой и тёмной сердцевиной - не путается с ромбами/кружками ключей слоёв. */
private fun DrawScope.drawBoneKey(cx: Float, cy: Float, r: Float, color: Color) {
    drawDiamond(cx, cy, r + 2.5f, Color.White)
    drawDiamond(cx, cy, r, color)
    drawDiamond(cx, cy, r * 0.38f, Color(0xFF102027))
}

private fun DrawScope.drawDiamond(cx: Float, cy: Float, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
    }
    drawPath(p, color)
}

/**
 * Жесты по полоске клипа в строке таймлайна. Край (зона HANDLE_GRAB_W) меняет начало или конец клипа.
 * Если слой выбран, перетаскивание за середину полоски сдвигает клип целиком; у невыбранного слоя
 * жест остаётся прокруткой таймлайна, а тап - выбором слоя.
 */
private fun Modifier.clipGestures(id: Int, frameWPx: Float, clip: State<Clip>, selected: State<Boolean>, vm: MainViewModel): Modifier =
    pointerInput(id, frameWPx) {
        val grab = HANDLE_GRAB_W.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val c0 = clip.value
            val sx = c0.start * frameWPx
            val ex = c0.end * frameWPx
            val dl = abs(down.position.x - sx)
            val dr = abs(down.position.x - ex)
            val mode = when {
                dl <= grab && dl <= dr -> 1                                              // левый край
                dr <= grab -> 2                                                          // правый край
                selected.value && down.position.x in sx..ex -> 3                         // середина выбранного клипа
                else -> 0
            }
            if (mode == 0) return@awaitEachGesture
            if (mode != 3) down.consume()
            var pos = if (mode == 2) ex else sx
            var moved = 0f
            var dragging = mode != 3
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed || c.isConsumed) break
                val dx = c.positionChange().x
                if (!dragging) {
                    moved += dx
                    if (abs(moved) > viewConfiguration.touchSlop) dragging = true else continue
                    pos += moved
                } else {
                    pos += dx
                }
                c.consume()
                val f = (pos / frameWPx).roundToInt()
                when (mode) {
                    1 -> vm.setClip(id, f, clip.value.end)
                    2 -> vm.setClip(id, clip.value.start, f)
                    else -> vm.moveClipTo(id, f)
                }
            }
        }
    }

/** Компактная иконка-кнопка панелей таймлайна; [active] подсвечивает включённый режим. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean = true, active: Boolean = false,
    onLong: (() -> Unit)? = null, onClick: () -> Unit
) {
    Box(
        Modifier.size(36.dp).coachTarget(desc).clip(androidx.compose.foundation.shape.CircleShape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLong),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, com.vibetoon.ui.i18n.L(desc), modifier = Modifier.size(20.dp),
            tint = (if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current).copy(alpha = if (enabled) 1f else 0.38f)
        )
    }
}
