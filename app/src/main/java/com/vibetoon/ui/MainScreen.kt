package com.vibetoon.ui

import androidx.compose.material.icons.filled.Colorize
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import com.vibetoon.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.render.RenderHost
import com.vibetoon.render.VtGLSurfaceView
import com.vibetoon.ui.inspector.InspectorPanel
import com.vibetoon.ui.timeline.TimelinePanel
import kotlin.math.abs

/** Пальцы ближе этого расстояния по оси не дают надёжного отношения: масштаб по оси не меняем. */
private const val MIN_SPREAD_PX = 60f

/** Ширина, начиная с которой интерфейс переключается на планшетную раскладку. */
private val TABLET_BREAKPOINT = 840.dp
private val INSPECTOR_SIDE_WIDTH = 340.dp

/**
 * Отношения расстояний между двумя пальцами по осям экрана (горизонталь -> ScaleX,
 * вертикаль -> ScaleY) за один шаг жеста.
 */
private fun axisRatios(a: PointerInputChange, b: PointerInputChange): Pair<Float, Float> {
    fun ratio(prev: Float, cur: Float) = if (prev > MIN_SPREAD_PX) (cur / prev).coerceIn(0.5f, 2f) else 1f
    return ratio(abs(a.previousPosition.x - b.previousPosition.x), abs(a.position.x - b.position.x)) to
        ratio(abs(a.previousPosition.y - b.previousPosition.y), abs(a.position.y - b.position.y))
}

@Composable
fun MainScreen(vm: MainViewModel, projectId: String, onBack: () -> Unit) {
    // Открываем проект при входе на экран; системная кнопка "назад" возвращает к списку проектов
    LaunchedEffect(projectId) { vm.openProject(projectId) }
    // системная «Назад» требует двойного нажатия, чтобы случайно не выйти из редактора (стрелка в шапке выходит сразу)
    var backAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - backAt < 2500) onBack() else { backAt = now; vm.showMessage("Нажмите «Назад» ещё раз, чтобы выйти в список проектов") }
    }
    // Автосохранение при сворачивании приложения
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.flushSave() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Системный диалог выбора файла: PSD из памяти телефона
    val openPsd = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadUserPsd(uri)
    }
    var showTextDialog by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    // Меню "+": картинка, видео, аудио - тот же системный диалог, разные типы файлов
    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMedia(MediaKind.IMAGE, uri)
    }
    val openVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMedia(MediaKind.VIDEO, uri)
    }
    val openAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMedia(MediaKind.AUDIO, uri)
    }
    val addActions = AddActions(
        psd = { openPsd.launch(arrayOf("*/*")) },
        image = { openImage.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/*")) },
        video = { openVideo.launch(arrayOf("video/mp4", "video/*")) },
        audio = { openAudio.launch(arrayOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/*")) },
        text = { showTextDialog = true }
    )

    com.vibetoon.ui.shell.EditorShell(vm, onBack, addActions) { showExport = true }
    com.vibetoon.ui.shell.CoachOverlay()
    val curveOpen by vm.curveEditor.collectAsStateWithLifecycle()
    if (curveOpen) com.vibetoon.ui.timeline.CurveEditorDialog(vm.currentCurve(), onChange = { c, first -> vm.setCurve(c, first) }) { vm.openCurveEditor(false) }

    ExportDialog(vm, showExport) { showExport = false }

    // Новый текстовый слой: поле ввода с экранной клавиатурой
    if (showTextDialog) {
        var input by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Новый текст") },
            text = {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, minLines = 2, maxLines = 5,
                    label = { Text("Введите текст") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
                // клавиатура открывается сразу; на части устройств фокус ещё не готов - не роняем приложение
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(150)
                    runCatching { focus.requestFocus() }
                }
            },
            confirmButton = { TextButton({ vm.addText(input); showTextDialog = false }) { Text("Добавить") } },
            dismissButton = { TextButton({ showTextDialog = false }) { Text("Отмена") } }
        )
    }
}

/** Что запускает меню "+" (системные диалоги выбора файла). */
class AddActions(
    val psd: () -> Unit, val image: () -> Unit, val video: () -> Unit, val audio: () -> Unit,
    val text: () -> Unit
)

@Composable
fun StatusLine(vm: MainViewModel) {
    val picking by vm.eyedropper.collectAsStateWithLifecycle()
    if (picking) {
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(6.dp)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.vibetoon.ui.IconText(androidx.compose.material.icons.Icons.Default.Colorize, "Коснитесь холста, чтобы взять цвет", 13.sp)
                OutlinedButton(onClick = vm::cancelEyedropper) { Text("Отмена") }
            }
        }
        return
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val psdName by vm.psdName.collectAsStateWithLifecycle()
    // временные сообщения гаснут сами через несколько секунд, чтобы не закрывать кадр
    androidx.compose.runtime.LaunchedEffect(state) {
        if (state is UiState.Message) { kotlinx.coroutines.delay(4000); vm.clearMessage(state) }
    }
    val text = when (val s = state) {
        UiState.Idle -> if (!vm.hasLayers) "Нажмите «＋ Добавить», чтобы импортировать PSD, изображение, видео или аудио" else ""
        is UiState.Message -> s.text
        UiState.Loading -> "Загрузка…"
        is UiState.Rendering -> "Загрузка слоёв: ${s.done}/${s.total}"
        is UiState.Success -> "$psdName: текстур ${s.textures} (%.0f МБ). Выделено: ${selected ?: "—"}".format(s.mb)
        is UiState.Error -> "Ошибка: ${s.message}"
    }
    // на очень низком холсте (поднятый таймлайн) плашка только мешает
    if (text.isEmpty() || (vm.canvasHeightPx < 300f && state !is UiState.Error)) return
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), shape = RoundedCornerShape(6.dp)) {
        Box(Modifier.widthIn(max = 460.dp).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(text, maxLines = 2, fontSize = 13.sp)
        }
    }
}

/** Выделение (полупрозрачная маска с границей), рамка/лассо в процессе и точки пера - поверх холста. */
@Composable
private fun RasterOverlayLayer(vm: MainViewModel) {
    val so by vm.selOverlay.collectAsStateWithLifecycle()
    val drag by vm.toolDrag.collectAsStateWithLifecycle()
    val bmp = so.bmp
    if (bmp == null && drag == null) return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        if (bmp != null && so.corners.size >= 6) {
            val c = so.corners
            val ex = (c[2] - c[0]) / bmp.width; val ey = (c[3] - c[1]) / bmp.width
            val fx = (c[4] - c[0]) / bmp.height; val fy = (c[5] - c[1]) / bmp.height
            val m = androidx.compose.ui.graphics.Matrix(floatArrayOf(ex, ey, 0f, 0f, fx, fy, 0f, 0f, 0f, 0f, 1f, 0f, c[0], c[1], 0f, 1f))
            withTransform({ transform(m) }) { drawImage(bmp) }
        }
        drag?.let { d ->
            val p = d.pts
            val white = Color.White; val dark = Color.Black.copy(alpha = 0.7f)
            val st = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())
            when (d.kind) {
                0, 1 -> if (p.size >= 4) {
                    val tl = Offset(minOf(p[0], p[2]), minOf(p[1], p[3])); val sz = androidx.compose.ui.geometry.Size(kotlin.math.abs(p[2] - p[0]), kotlin.math.abs(p[3] - p[1]))
                    if (d.kind == 0) { drawRect(dark, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx())); drawRect(white, tl, sz, style = st) }
                    else { drawOval(dark, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx())); drawOval(white, tl, sz, style = st) }
                }
                4 -> for (i in 0 until p.size / 2) {
                    val on = i == d.sel
                    drawCircle(dark, (if (on) 9 else 6).dp.toPx(), Offset(p[i * 2], p[i * 2 + 1]))
                    drawCircle(if (on) Color(0xFFFFB300) else white, (if (on) 7 else 4).dp.toPx(), Offset(p[i * 2], p[i * 2 + 1]))
                }
                2, 3 -> {
                    for (i in 0 until p.size / 2 - 1) {
                        val a = Offset(p[i * 2], p[i * 2 + 1]); val b = Offset(p[i * 2 + 2], p[i * 2 + 3])
                        drawLine(dark, a, b, 4.dp.toPx()); drawLine(white, a, b, 2.dp.toPx())
                    }
                    if (d.kind == 3) for (i in 0 until p.size / 2) drawCircle(if (i == 0) Color(0xFF26C6DA) else white, if (i == 0) 8.dp.toPx() else 5.dp.toPx(), Offset(p[i * 2], p[i * 2 + 1]))
                }
            }
        }
    }
}

/** Холст OpenGL с прозрачным слоем жестов поверх него. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun EditorCanvas(vm: MainViewModel, modifier: Modifier) {
    val slop = LocalViewConfiguration.current.touchSlop
    Box(modifier) {
        AndroidView(
            factory = { ctx -> VtGLSurfaceView(ctx).also { RenderHost.view = it } },
            onRelease = { it.releaseNative(); if (RenderHost.view === it) RenderHost.view = null },
            modifier = Modifier.matchParentSize()
        )
        BoneOverlayLayer(vm)
        WarpOverlayLayer(vm)
        MeshOverlayLayer(vm)
        PinOverlayLayer(vm)
        BrushCircleLayer(vm)
        PaintCursorLayer(vm)
        PerspOverlayLayer(vm)
        RasterOverlayLayer(vm)
        val gs = com.vibetoon.ui.theme.LocalAppSettings.current
        Box(
            modifier = Modifier.matchParentSize().onSizeChanged { vm.onViewSize(it.width.toFloat(), it.height.toFloat()) }
            // касание 2 пальцами (Undo) и 3 пальцами (Redo): только наблюдение за событиями (Initial, ничего не потребляется), решение при отпускании
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val t0 = first.uptimeMillis
                    var maxN = 1
                    val start = HashMap<androidx.compose.ui.input.pointer.PointerId, Offset>()
                    start[first.id] = first.position
                    var maxMove = 0f
                    var lastUp = t0
                    while (true) {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        for (c in ev.changes) {
                            if (c.pressed && c.id !in start) start[c.id] = c.position
                            start[c.id]?.let { s -> maxMove = maxOf(maxMove, (c.position - s).getDistance()) }
                        }
                        maxN = maxOf(maxN, ev.changes.count { it.pressed })
                        lastUp = ev.changes.maxOf { it.uptimeMillis }
                        if (ev.changes.none { it.pressed }) break
                    }
                    val n = com.vibetoon.domain.MultiTap.decide(maxN, lastUp - t0, maxMove, slop)
                    if (n == 2 && gs?.tap2Undo == true) vm.undo() else if (n == 3 && gs?.tap3Redo == true) vm.redo()
                }
            }
            .pointerInput(Unit) {
                vm.onViewSize(size.width.toFloat(), size.height.toFloat())
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multi = false
                    // Рисование (Кисть / Ластик): штрих идёт сразу, без порога сдвига. Перо: давление и исторические точки;
                    // касания пальцем рядом с пером игнорируются (ладонь). Второй палец при рисовании пальцем отменяет штрих
                    // и превращается в жест камеры (щипок/сдвиг).
                    if (vm.rasterToolActive()) {
                        if (vm.rasterDown(down.position.x, down.position.y)) {
                            down.consume()
                            var cancelled = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ev.changes.any { it.id != down.id && it.pressed }) { vm.rasterCancel(); cancelled = true; break }
                                if (!c.pressed) break
                                for (h in c.historical) vm.rasterMove(h.position.x, h.position.y)
                                vm.rasterMove(c.position.x, c.position.y)
                                ev.changes.forEach { it.consume() }
                            }
                            if (!cancelled) { vm.rasterUp(); return@awaitEachGesture }
                            multi = true
                        }
                    }
                    if (vm.paintToolActive()) {
                        val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                        if (!stylus && vm.paintPalmBlocked()) {
                            down.consume()
                            do {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        if (stylus) vm.paintStylus(true)
                        val firstP = if (stylus) down.pressure.coerceIn(0.05f, 1f) else 1f
                        if (vm.paintDown(down.position.x, down.position.y, firstP)) {
                            down.consume()
                            var cancelled = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!stylus && ev.changes.any { it.id != down.id && it.pressed }) { vm.paintCancel(); cancelled = true; break }
                                if (!c.pressed) break
                                val pm = if (stylus) c.pressure.coerceIn(0.05f, 1f) else 1f
                                for (h in c.historical) vm.paintMove(h.position.x, h.position.y, pm)
                                vm.paintMove(c.position.x, c.position.y, pm)
                                ev.changes.forEach { it.consume() }
                            }
                            if (stylus) vm.paintStylus(false)
                            if (!cancelled) { vm.paintUp(); return@awaitEachGesture }
                            multi = true
                        } else if (stylus) vm.paintStylus(false)
                    }
                    var capture = if (multi) false else vm.boneDown(down.position.x, down.position.y)   // режим костей: жест забирает кость
                    var total = Offset.Zero
                    var moved = false
                    do {
                        val event: PointerEvent = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            multi = true
                            if (capture) { vm.boneCancel(); capture = false }
                            val pan = event.calculatePan()
                            if (vm.hasSelection() && !vm.boneMode.value && !vm.warpTool.value && !vm.meshMode.value && !vm.perspTool.value && !vm.liquifyTool.value && vm.paintTool.value == null) {
                                // Выделен слой или группа: два пальца вращают, масштабируют и двигают ЕГО.
                                // Цепочка включена: пропорционально; выключена: ScaleX по горизонтали,
                                // ScaleY по вертикали.
                                val zoom = event.calculateZoom()
                                val (zx, zy) = if (vm.uniformScale.value) zoom to zoom
                                else axisRatios(pressed[0], pressed[1])
                                vm.onTransformLayer(zx, zy, event.calculateRotation(), pan.x, pan.y)
                            } else {
                                // Ничего не выделено: двигаем камеру
                                val c = event.calculateCentroid()
                                vm.onTransform(c.x, c.y, event.calculateZoom(), pan.x, pan.y)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && !multi) {
                            val d = pressed[0].positionChange()
                            total += d
                            if (!moved && total.getDistance() > slop) moved = true
                            if (moved && capture) {
                                vm.boneMove(pressed[0].position.x, pressed[0].position.y)
                                pressed[0].consume()
                            } else if (moved) {
                                // Выделено -> тащим слой или группу, иначе двигаем камеру
                                if (vm.hasSelection() && !vm.boneMode.value && !vm.warpTool.value && !vm.meshMode.value && !vm.perspTool.value && !vm.liquifyTool.value && vm.paintTool.value == null) vm.onDragLayer(d.x, d.y) else vm.onPan(d.x, d.y)
                                pressed[0].consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (capture) vm.boneUp()
                    if (!moved && !multi) vm.onTap(down.position.x, down.position.y)
                }
            }
        ) {}
    }
}

/** Скелет поверх холста: ромбик в суставе, линия, кружок на конце; выбранная кость оранжевая. */
@Composable
private fun BoneOverlayLayer(vm: MainViewModel) {
    val overlay by vm.boneOverlay.collectAsStateWithLifecycle()
    if (overlay.bones.isEmpty() && overlay.draft == null && overlay.lasso.isEmpty()) return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val line = 2.dp.toPx()
        val r = 6.dp.toPx()
        fun draw(hx: Float, hy: Float, tx: Float, ty: Float, color: Color) {
            drawLine(Color.Black.copy(alpha = 0.6f), Offset(hx, hy), Offset(tx, ty), strokeWidth = line + 2.dp.toPx())
            drawLine(color, Offset(hx, hy), Offset(tx, ty), strokeWidth = line)
            // ромбик в начале
            val d = Path().apply {
                moveTo(hx, hy - r * 1.3f); lineTo(hx + r * 1.3f, hy); lineTo(hx, hy + r * 1.3f); lineTo(hx - r * 1.3f, hy); close()
            }
            drawPath(d, color)
            drawPath(d, Color.Black.copy(alpha = 0.7f), style = Stroke(1.dp.toPx()))
            // кружок на конце
            drawCircle(color, r, Offset(tx, ty))
            drawCircle(Color.Black.copy(alpha = 0.7f), r, Offset(tx, ty), style = Stroke(1.dp.toPx()))
        }
        for (b in overlay.bones) {
            draw(b.hx, b.hy, b.tx, b.ty, if (b.selected) Color(0xFFFFA726) else Color(0xFF26C6DA))
        }
        overlay.draft?.let { draw(it[0], it[1], it[2], it[3], Color.White) }
        // лассо: ломаная контура и замыкающая линия
        val l = overlay.lasso
        if (l.size >= 4) {
            for (i in 0 until l.size / 2 - 1) drawLine(Color(0xFF42A5F5), Offset(l[i * 2], l[i * 2 + 1]), Offset(l[i * 2 + 2], l[i * 2 + 3]), strokeWidth = 3.dp.toPx())
            drawLine(Color(0xFF42A5F5).copy(alpha = 0.5f), Offset(l[l.size - 2], l[l.size - 1]), Offset(l[0], l[1]), strokeWidth = 1.5.dp.toPx())
        }
    }
}

/** Контрольные точки Warp: решётка 4x4 линиями и круглые ручки, перетаскиваются пальцем. */
@Composable
private fun WarpOverlayLayer(vm: MainViewModel) {
    val overlay by vm.warpOverlay.collectAsStateWithLifecycle()
    val p = overlay.points
    if (p.size < 32) return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val line = Color(0xFFFFA726)
        fun pt(k: Int) = Offset(p[k * 2], p[k * 2 + 1])
        for (j in 0 until 4) for (i in 0 until 4) {
            val k = j * 4 + i
            if (i < 3) drawLine(Color.Black.copy(alpha = 0.5f), pt(k), pt(k + 1), strokeWidth = 4.dp.toPx())
            if (j < 3) drawLine(Color.Black.copy(alpha = 0.5f), pt(k), pt(k + 4), strokeWidth = 4.dp.toPx())
        }
        for (j in 0 until 4) for (i in 0 until 4) {
            val k = j * 4 + i
            if (i < 3) drawLine(line, pt(k), pt(k + 1), strokeWidth = 1.5.dp.toPx())
            if (j < 3) drawLine(line, pt(k), pt(k + 4), strokeWidth = 1.5.dp.toPx())
        }
        for (k in 0 until 16) {
            drawCircle(Color.White, 8.dp.toPx(), pt(k))
            drawCircle(line, 6.dp.toPx(), pt(k))
            drawCircle(Color.Black.copy(alpha = 0.7f), 8.dp.toPx(), pt(k), style = Stroke(1.dp.toPx()))
        }
    }
}

/** Сетка Mesh: рёбра треугольников и ручки вершин; выбранная вершина подсвечена. */
@Composable
private fun MeshOverlayLayer(vm: MainViewModel) {
    val ov by vm.meshOverlay.collectAsStateWithLifecycle()
    val p = ov.points
    if (p.isEmpty()) return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        // как в Clip Studio: тонкие тёмные рёбра с белой подложкой (видны на любом фоне), серые контрольные точки,
        // активная точка красная с кольцом
        val e = ov.edges
        for (i in 0 until e.size / 2) {
            val a = Offset(p[e[i * 2] * 2], p[e[i * 2] * 2 + 1])
            val b = Offset(p[e[i * 2 + 1] * 2], p[e[i * 2 + 1] * 2 + 1])
            drawLine(Color.White.copy(alpha = 0.55f), a, b, strokeWidth = 2.6.dp.toPx())
        }
        for (i in 0 until e.size / 2) {
            val a = Offset(p[e[i * 2] * 2], p[e[i * 2] * 2 + 1])
            val b = Offset(p[e[i * 2 + 1] * 2], p[e[i * 2 + 1] * 2 + 1])
            drawLine(Color(0xFF1A1A1A).copy(alpha = 0.9f), a, b, strokeWidth = 1.dp.toPx())
        }
        // точки вершин не рисуются: сеткой управляют пины (Puppet Mesh), на экране только тонкие рёбра
    }
}

/** Пины Puppet Mesh: зелёные точки (жёсткий - квадрат); выбранный окружён радиусом влияния. */
@Composable
private fun PinOverlayLayer(vm: MainViewModel) {
    val ov by vm.pinOverlay.collectAsStateWithLifecycle()
    val p = ov.points
    if (p.isEmpty()) return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val green = Color(0xFF2ECC71)
        // с 3 и более пинов прядь гнётся вдоль линии (Deform Path): рисуем сплайн через пины
        if (p.size / 2 >= com.vibetoon.domain.anim.PuppetMath.PATH_MIN_PINS) {
            com.vibetoon.domain.anim.PathMath.build(p, 12)?.let { c ->
                val path = androidx.compose.ui.graphics.Path()
                for (k in 0 until c.size) if (k == 0) path.moveTo(c.x[k], c.y[k]) else path.lineTo(c.x[k], c.y[k])
                drawPath(path, Color.Black.copy(alpha = 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
                drawPath(path, green, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
        for (i in 0 until p.size / 2) {
            val c = Offset(p[i * 2], p[i * 2 + 1])
            val sel = i == ov.selected
            if (sel) {
                drawCircle(green.copy(alpha = 0.10f), ov.radius[i], c)
                drawCircle(green.copy(alpha = 0.7f), ov.radius[i], c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
            val r = (if (sel) 11f else 8f).dp.toPx()
            if (ov.stiff[i]) {
                drawRect(Color.White, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2))
                drawRect(green, Offset(c.x - r * 0.72f, c.y - r * 0.72f), androidx.compose.ui.geometry.Size(r * 1.44f, r * 1.44f))
            } else {
                drawCircle(Color.White, r, c)
                drawCircle(green, r * 0.72f, c)
            }
            if (sel) drawCircle(Color(0xFFFFA726), r * 1.5f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}

/** Круг кисти рисования под пальцем или пером: радиус в пикселях экрана (учитывает масштаб холста и слоя). */
@Composable
private fun PaintCursorLayer(vm: MainViewModel) {
    val c by vm.paintCursor.collectAsStateWithLifecycle()
    val b = c ?: return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        drawCircle(Color.Black.copy(alpha = 0.5f), b[2], Offset(b[0], b[1]), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
        drawCircle(Color.White, b[2], Offset(b[0], b[1]), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

/** Круг кисти по вершинам: рисуется под пальцем, радиус в экранных пикселях (уже учитывает зум холста). */
@Composable
private fun BrushCircleLayer(vm: MainViewModel) {
    val c by vm.brushCircle.collectAsStateWithLifecycle()
    val b = c ?: return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        drawCircle(Color.Black.copy(alpha = 0.5f), b[2], Offset(b[0], b[1]), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        drawCircle(Color.White, b[2], Offset(b[0], b[1]), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}

/** Perspective: четырёхугольник из 4 угловых ручек, ручки перетаскиваются пальцем. */
@Composable
private fun PerspOverlayLayer(vm: MainViewModel) {
    val p by vm.perspOverlay.collectAsStateWithLifecycle()
    if (p.size < 8) return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val line = Color(0xFFAB47BC)
        for (k in 0 until 4) {
            val a = Offset(p[k * 2], p[k * 2 + 1])
            val n = (k + 1) % 4
            val b = Offset(p[n * 2], p[n * 2 + 1])
            drawLine(Color.Black.copy(alpha = 0.5f), a, b, strokeWidth = 4.dp.toPx())
            drawLine(line, a, b, strokeWidth = 1.5.dp.toPx())
        }
        for (k in 0 until 4) {
            val c = Offset(p[k * 2], p[k * 2 + 1])
            drawCircle(Color.White, 10.dp.toPx(), c)
            drawCircle(line, 7.dp.toPx(), c)
        }
    }
}
