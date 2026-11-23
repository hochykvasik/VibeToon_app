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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
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
fun MainScreen(vm: MainViewModel, projectId: String, pendingPsd: android.net.Uri? = null, onBack: () -> Unit) {
    // Открываем проект при входе на экран; системная кнопка "назад" возвращает к списку проектов
    // (пакет 13: PSD, выбранный в «Импорте» на экране проектов, загружается сразу после открытия нового проекта)
    LaunchedEffect(projectId) { vm.openProject(projectId, pendingPsd) }
    // системная «Назад» требует двойного нажатия, чтобы случайно не выйти из редактора (стрелка в шапке выходит сразу)
    var backAt by remember { mutableLongStateOf(0L) }
    val selectionExtractInProgress by vm.selectionExtractBusy.collectAsStateWithLifecycle()
    BackHandler {
        if (selectionExtractInProgress) return@BackHandler
        val now = System.currentTimeMillis()
        if (now - backAt < 2500) onBack() else { backAt = now; vm.showMessage("Нажмите «Назад» ещё раз, чтобы выйти в список проектов") }
    }
    // Автосохранение при сворачивании приложения
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> vm.onAppPaused()
                Lifecycle.Event.ON_RESUME -> vm.onAppResumed()
                Lifecycle.Event.ON_STOP -> vm.flushSave()
                else -> Unit
            }
        }
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
    val importSettings = com.vibetoon.ui.theme.LocalAppSettings.current
    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // «Import Images as Symbols» (пакет 10): картинка сразу становится символом
        if (uri != null) { if (importSettings?.importImagesAsSymbols == true) vm.importMediaAsSymbol(uri) else vm.importMedia(MediaKind.IMAGE, uri) }
    }
    val openImageAsSymbol = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMediaAsSymbol(uri)
    }
    // пакет 11: секвенция картинок - покадровый слой или символ
    var sequenceAsSymbol by remember { mutableStateOf(false) }
    val openSequence = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importImageSequence(uris, sequenceAsSymbol)
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
        text = { showTextDialog = true },
        imageAsSymbol = { openImageAsSymbol.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/*")) },
        sequence = { asSymbol -> sequenceAsSymbol = asSymbol; openSequence.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/*")) }
    )

    com.vibetoon.ui.shell.EditorShell(vm, onBack, addActions) { showExport = true }
    com.vibetoon.ui.shell.CoachOverlay()
    val extracting by vm.selectionExtractBusy.collectAsStateWithLifecycle()
    if (extracting) AlertDialog(
        onDismissRequest = {},
        title = { Text("Подготовка выделения") },
        text = { Text("Создаём слой, пожалуйста, подождите") },
        confirmButton = {}
    )
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
    val text: () -> Unit,
    val imageAsSymbol: () -> Unit = {},
    /** Пакет 11: секвенция картинок (true - как символ). */
    val sequence: (Boolean) -> Unit = {}
)

@Composable
fun StatusLine(vm: MainViewModel) {
    val picking by vm.eyedropper.collectAsStateWithLifecycle()
    val audioReadiness by vm.audioReadiness.collectAsStateWithLifecycle()
    if (picking && audioReadiness !is com.vibetoon.domain.project.AudioReadiness.Preparing) {
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
    val errorTemplate = com.vibetoon.ui.i18n.L("Ошибка: {}")
    val errorMessage = (state as? UiState.Error)?.message?.let { com.vibetoon.ui.i18n.localizeDiagnostic(it) }
    val text = if (audioReadiness is com.vibetoon.domain.project.AudioReadiness.Preparing) "Подготовка аудио…" else when (val s = state) {
        UiState.Idle -> if (!vm.hasLayers) "Нажмите «＋ Добавить», чтобы импортировать PSD, изображение, видео или аудио" else ""
        is UiState.Message -> com.vibetoon.ui.i18n.boneStatusMessage(s.text)
        UiState.Loading -> "Загрузка…"
        is UiState.Rendering -> "Загрузка слоёв: ${s.done}/${s.total}"
        is UiState.Success -> "$psdName: текстур ${s.textures} (%.0f МБ). Выделено: ${selected ?: "—"}".format(s.mb)
        is UiState.Error -> errorTemplate.replace("{}", errorMessage ?: s.message)
    }
    // на очень низком холсте (поднятый таймлайн) плашка только мешает
    if (text.isEmpty() || (vm.canvasHeightPx < 300f && state !is UiState.Error && state != UiState.Loading && audioReadiness !is com.vibetoon.domain.project.AudioReadiness.Preparing)) return
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), shape = RoundedCornerShape(6.dp)) {
        Box(Modifier.widthIn(max = 460.dp).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(text, maxLines = 2, fontSize = 13.sp)
        }
    }
}

/** Пакет 19-20: ручки Безье (линии от узла и квадратики) - выбранный контур и контур пера в работе. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBezierHandles(d: com.vibetoon.ui.ToolDrag, dark: Color) {
    d.handles?.let { h ->
        val hl = 5.dp.toPx()
        for (k in 0 until h.size / 6) {
            val n = Offset(h[k * 6], h[k * 6 + 1])
            for (w in 0..1) {
                val e = Offset(h[k * 6 + 2 + w * 2], h[k * 6 + 3 + w * 2])
                if (e == n) continue
                // пакет 21: независимые ручки - сиреневые
                val tint = if (d.freeHandles?.getOrNull(k) == true) Color(0xFFCE93D8) else Color(0xFF26C6DA)
                drawLine(dark, n, e, 3.dp.toPx()); drawLine(tint, n, e, 1.5f.dp.toPx())
                val on = k * 2 + w == d.selHandle
                drawRect(dark, Offset(e.x - hl - 1.5f, e.y - hl - 1.5f), androidx.compose.ui.geometry.Size(hl * 2 + 3f, hl * 2 + 3f))
                drawRect(if (on) Color(0xFFFFB300) else tint, Offset(e.x - hl, e.y - hl), androidx.compose.ui.geometry.Size(hl * 2, hl * 2))
            }
        }
    }
}

/** Выделение (полупрозрачная маска с границей), рамка/лассо в процессе и точки пера - поверх холста. */
@Composable
private fun RasterOverlayLayer(vm: MainViewModel) {
    val so by vm.selOverlay.collectAsStateWithLifecycle()
    val drag by vm.toolDrag.collectAsStateWithLifecycle()
    val bmp = if (com.vibetoon.ui.theme.LocalAppSettings.current?.selectionMaskVisible != false) so.bmp else null
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
                4 -> {
                    drawBezierHandles(d, dark)
                    for (i in 0 until p.size / 2) {
                        val on = i == d.sel
                        drawCircle(dark, (if (on) 9 else 6).dp.toPx(), Offset(p[i * 2], p[i * 2 + 1]))
                        drawCircle(if (on) Color(0xFFFFB300) else white, (if (on) 7 else 4).dp.toPx(), Offset(p[i * 2], p[i * 2 + 1]))
                    }
                }
                2, 3, 5 -> {
                    val line = d.curve ?: p   // пакет 20: контур пера с ручками - кривая
                    for (i in 0 until line.size / 2 - 1) {
                        val a = Offset(line[i * 2], line[i * 2 + 1]); val b = Offset(line[i * 2 + 2], line[i * 2 + 3])
                        drawLine(dark, a, b, 4.dp.toPx()); drawLine(white, a, b, 2.dp.toPx())
                    }
                    if (d.kind == 3) drawBezierHandles(d, dark)
                    if (d.kind == 5 && p.size >= 2) {
                        drawCircle(Color(0xFF26C6DA).copy(alpha = .3f), 24.dp.toPx(), Offset(p[0], p[1]))
                        drawCircle(Color(0xFF26C6DA), 5.dp.toPx(), Offset(p[0], p[1]))
                        if (p.size >= 4) drawCircle(Color(0xFFFFB300), 6.dp.toPx(), Offset(p[p.size - 2], p[p.size - 1]))
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
    val config = LocalViewConfiguration.current
    val slop = config.touchSlop
    val longPressMillis = config.longPressTimeoutMillis
    Box(modifier) {
        AndroidView(
            factory = { ctx -> VtGLSurfaceView(ctx).also { it.onContextRecreated = { vm.onGlContextRecreated() }; RenderHost.view = it } },
            onRelease = { it.releaseNative(); if (RenderHost.view === it) RenderHost.view = null },
            modifier = Modifier.matchParentSize()
        )
        if (com.vibetoon.ui.theme.LocalAppSettings.current?.showGrid == true) CanvasGridLayer(vm)
        MotionPathLayer(vm)
        BoneOverlayLayer(vm)
        WarpOverlayLayer(vm)
        MeshOverlayLayer(vm)
        PinOverlayLayer(vm)
        BrushCircleLayer(vm)
        CameraAngleBadge(vm)
        PerfHudLayer(vm)
        PivotOverlay(vm)
        RulerGuideLayer(vm)
        PaintCursorLayer(vm)
        PerspOverlayLayer(vm)
        RasterOverlayLayer(vm)
        MarqueeOverlay(vm)
        val gs = com.vibetoon.ui.theme.LocalAppSettings.current
        val hoverEnabled = gs?.cursorHoverPreview == true && gs.cursorEnabled
        val longPressEnabled = gs?.longPressPicker == true
        val touchFilter = remember { com.vibetoon.domain.input.StylusTouchFilter() }
        LaunchedEffect(hoverEnabled) { if (!hoverEnabled) vm.clearPaintHover() }
        Box(
            modifier = Modifier.matchParentSize().onSizeChanged { vm.onViewSize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(hoverEnabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (event.type == PointerEventType.Exit) vm.clearPaintHover()
                        else if (hoverEnabled && event.type == PointerEventType.Move) {
                            val pen = event.changes.firstOrNull { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
                            if (pen != null && !pen.pressed) vm.paintHover(pen.position.x, pen.position.y)
                        }
                    }
                }
            }
            // Начало/конец контакта пера; hover не запускает защитный интервал.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val pens = event.changes.filter { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
                        if (pens.isNotEmpty()) touchFilter.observePen(pens.any { it.pressed }, pens.maxOf { it.uptimeMillis })
                    }
                }
            }
            // Наблюдаем только Initial, ничего не потребляем. Движение, ещё один палец или отпускание отменяют удержание.
            .pointerInput(longPressEnabled, longPressMillis, slop) {
                if (longPressEnabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (touchFilter.blockTouch(gs?.touchDetection == true, down.type == PointerType.Touch, down.uptimeMillis) ||
                        !vm.canPickOnLongPress(down.position.x, down.position.y)) return@awaitEachGesture
                    val cancelled = withTimeoutOrNull(longPressMillis) {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val point = event.changes.firstOrNull { it.id == down.id }
                            val distance = point?.let { (it.position - down.position).getDistance() } ?: Float.POSITIVE_INFINITY
                            if (com.vibetoon.domain.input.LongPressPicker.cancelled(point?.pressed == true,
                                    event.changes.count { it.pressed }, point?.isConsumed == true, distance, slop)) return@withTimeoutOrNull true
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    } ?: false
                    if (!cancelled && gs?.longPressPicker == true &&
                        !touchFilter.blockTouch(gs.touchDetection, down.type == PointerType.Touch, android.os.SystemClock.uptimeMillis())) vm.pickBrushOnLongPress(down.position.x, down.position.y)
                }
            }
            // касание 2 пальцами (Undo) и 3 пальцами (Redo): только наблюдение за событиями (Initial, ничего не потребляется), решение при отпускании
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val t0 = first.uptimeMillis
                    var maxN = 1
                    var validTouch = !touchFilter.blockTouch(gs?.touchDetection == true, first.type == PointerType.Touch, first.uptimeMillis) &&
                        (gs?.touchDetection != true || first.type == PointerType.Touch)
                    val start = HashMap<androidx.compose.ui.input.pointer.PointerId, Offset>()
                    start[first.id] = first.position
                    var maxMove = 0f
                    var lastUp = t0
                    while (true) {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (gs?.touchDetection == true && ev.changes.any { (it.type == PointerType.Stylus || it.type == PointerType.Eraser) && (it.pressed || it.previousPressed) }) validTouch = false
                        for (c in ev.changes) {
                            if (c.pressed && c.id !in start) start[c.id] = c.position
                            start[c.id]?.let { s -> maxMove = maxOf(maxMove, (c.position - s).getDistance()) }
                        }
                        maxN = maxOf(maxN, ev.changes.count { it.pressed })
                        lastUp = ev.changes.maxOf { it.uptimeMillis }
                        if (ev.changes.none { it.pressed }) break
                    }
                    val n = com.vibetoon.domain.MultiTap.decide(maxN, lastUp - t0, maxMove, slop)
                    if (validTouch && n == 2 && gs?.tap2Undo == true) vm.undo() else if (validTouch && n == 3 && gs?.tap3Redo == true) vm.redo()
                }
            }
            .pointerInput(Unit) {
                vm.onViewSize(size.width.toFloat(), size.height.toFloat())
                var tapUp = -1L
                var tapPoint = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    vm.beginCanvasGesture()
                    if (touchFilter.blockTouch(gs?.touchDetection == true, down.type == PointerType.Touch, down.uptimeMillis)) {
                        tapUp = -1L
                        down.consume()
                        do { val ev = awaitPointerEvent(); ev.changes.filter { it.type == PointerType.Touch }.forEach { it.consume() } } while (ev.changes.any { it.pressed })
                        vm.finishCanvasGesture()
                        return@awaitEachGesture
                    }
                    val maySelect = gs?.doubleTapMultiSelect == true && down.type == PointerType.Touch && vm.canMarquee(gs.selectLayersOnCanvas)
                    val second = maySelect && com.vibetoon.domain.input.MarqueePolicy.secondTap(tapUp, down.uptimeMillis,
                        tapPoint.x, tapPoint.y, down.position.x, down.position.y, slop)
                    var interruptedMulti = false
                    if (second) {
                        tapUp = -1L
                        var dragging = false
                        var interrupted = false
                        var latest = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            if (c == null || ev.changes.any { it.id != down.id && it.pressed } || vm.isCanvasLongPressClaimed()) {
                                interruptedMulti = ev.changes.any { it.id != down.id && it.pressed }
                                interrupted = true; break
                            }
                            latest = c.position
                            if (!dragging && (latest - down.position).getDistance() > slop) dragging = true
                            if (dragging) { vm.updateMarquee(down.position.x, down.position.y, latest.x, latest.y); c.consume() }
                            if (!c.pressed) break
                        }
                        if (interrupted) vm.cancelMarquee()
                        else if (dragging) vm.finishMarquee(gs?.selectLayersOnCanvas != false)
                        else if (!vm.finishCanvasGesture()) vm.onTap(down.position.x, down.position.y, gs?.selectLayersOnCanvas != false)
                        if (!interrupted) { vm.finishCanvasGesture(); return@awaitEachGesture }
                        vm.finishCanvasGesture()
                        if (!interruptedMulti) return@awaitEachGesture
                        // Второй палец переходит в обычный обработчик камеры и мультитапов.
                    }
                    var multi = interruptedMulti
                    var multiTransformStarted = false
                    val multiStart = HashMap<androidx.compose.ui.input.pointer.PointerId, Offset>()
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
                                val secondFinger = ev.changes.firstOrNull { it.id != down.id && it.pressed && !(gs?.touchDetection == true && (down.type == PointerType.Stylus || down.type == PointerType.Eraser) && it.type == PointerType.Touch) }
                                if (secondFinger != null) {
                                    if (c.pressed && vm.beginSelectionGesture(c.position.x, c.position.y, secondFinger.position.x, secondFinger.position.y)) {
                                        val otherId = secondFinger.id
                                        var lastA = c.position; var lastB = secondFinger.position; var invalid = false; var remaining = true
                                        ev.changes.forEach { it.consume() }
                                        while (true) {
                                            val next = awaitPointerEvent()
                                            val a = next.changes.firstOrNull { it.id == down.id }
                                            val b = next.changes.firstOrNull { it.id == otherId }
                                            remaining = next.changes.any { it.pressed }
                                            if (a == null || b == null || next.changes.any { it.id != down.id && it.id != otherId && it.pressed }) { invalid = true; break }
                                            lastA = a.position; lastB = b.position
                                            next.changes.forEach { it.consume() }
                                            if (!a.pressed || !b.pressed) break
                                        }
                                        vm.endSelectionGesture(lastA.x, lastA.y, lastB.x, lastB.y, invalid)
                                        while (remaining) { val rest = awaitPointerEvent(); remaining = rest.changes.any { it.pressed }; rest.changes.forEach { it.consume() } }
                                        return@awaitEachGesture
                                    }
                                    vm.rasterCancel(); cancelled = true; break
                                }
                                if (!c.pressed) break
                                for (h in c.historical) vm.rasterMove(h.position.x, h.position.y)
                                vm.rasterMove(c.position.x, c.position.y)
                                ev.changes.forEach { it.consume() }
                            }
                            if (!cancelled) { vm.rasterUp(); return@awaitEachGesture }
                            multi = true
                        }
                    }
                    if (vm.paintToolActive() && com.vibetoon.domain.input.EditorInputPolicy.canStartStroke(down.type == PointerType.Touch, gs?.fingerDrawing != false)) {
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
                                if (!c.pressed) { vm.paintMove(c.position.x, c.position.y, if (stylus) c.pressure.coerceIn(0.05f, 1f) else 1f); break }
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
                    var pivotCapture = if (multi) false else vm.pivotDown(down.position.x, down.position.y)   // пивот: жест забирает пивот
                    var capture = if (multi || pivotCapture) false else vm.boneDown(down.position.x, down.position.y)   // режим костей: жест забирает кость
                    var total = Offset.Zero
                    var moved = false
                    var lastUptime = down.uptimeMillis
                    do {
                        val event: PointerEvent = awaitPointerEvent()
                        lastUptime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastUptime
                        val now = event.changes.maxOfOrNull { it.uptimeMillis } ?: android.os.SystemClock.uptimeMillis()
                        val pressed = event.changes.filter { it.pressed && !touchFilter.blockTouch(gs?.touchDetection == true, it.type == PointerType.Touch, now) }
                        // Если перо появилось после пальца, не передаём его координаты захваченному пальцем инструменту.
                        if (touchFilter.blockTouch(gs?.touchDetection == true, down.type == PointerType.Touch, now)) {
                            if (capture) { vm.boneCancel(); capture = false }
                            multi = true
                            event.changes.filter { it.type == PointerType.Touch }.forEach { it.consume() }
                            continue
                        }
                        if (vm.isCanvasLongPressClaimed()) { event.changes.forEach { it.consume() }; continue }
                        if (pressed.size >= 2) {
                            multi = true
                            if (capture) { vm.boneCancel(); capture = false }
                            for (c in pressed) multiStart.putIfAbsent(c.id, c.position)
                            val maxMove = pressed.maxOfOrNull { (it.position - (multiStart[it.id] ?: it.position)).getDistance() } ?: 0f
                            if (!multiTransformStarted) multiTransformStarted = com.vibetoon.domain.input.EditorInputPolicy.transformGestureStarted(maxMove, slop)
                            if (!multiTransformStarted) { event.changes.forEach { it.consume() }; continue }
                            val pan = event.calculatePan()
                            if (vm.hasSelection() && !vm.boneMode.value && !vm.warpTool.value && !vm.meshMode.value && !vm.perspTool.value && !vm.liquifyTool.value && vm.paintTool.value == null && vm.rasterTool.value == null) {
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
                                vm.onTransform(c.x, c.y, event.calculateZoom(), pan.x, pan.y, event.calculateRotation())
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && !multi) {
                            val d = pressed[0].positionChange()
                            total += d
                            if (!moved && total.getDistance() > slop) moved = true
                            if (moved && pivotCapture) {
                                vm.pivotMove(pressed[0].position.x, pressed[0].position.y)
                                pressed[0].consume()
                            } else if (moved && capture) {
                                vm.boneMove(pressed[0].position.x, pressed[0].position.y)
                                pressed[0].consume()
                            } else if (moved) {
                                // Выделено -> тащим слой или группу, иначе двигаем камеру
                                if (vm.hasSelection() && !vm.boneMode.value && !vm.warpTool.value && !vm.meshMode.value && !vm.perspTool.value && !vm.liquifyTool.value && vm.paintTool.value == null && vm.rasterTool.value == null) vm.onDragLayer(d.x, d.y) else vm.onPan(d.x, d.y)
                                pressed[0].consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (pivotCapture) vm.pivotUp()
                    if (capture) vm.boneUp()
                    if (multi) vm.snapCameraRotation()
                    val longPressClaimed = vm.finishCanvasGesture()
                    if (!longPressClaimed && !moved && !multi && !(vm.paintToolActive() && down.type == PointerType.Touch && gs?.fingerDrawing == false)) {
                        vm.onTap(down.position.x, down.position.y, gs?.selectLayersOnCanvas != false)
                        if (maySelect) { tapUp = lastUptime; tapPoint = down.position } else tapUp = -1L
                    } else tapUp = -1L
                }
            }
        ) {}
    }
}

@Composable
private fun MarqueeOverlay(vm: MainViewModel) {
    val rect by vm.marquee.collectAsStateWithLifecycle()
    val r = rect ?: return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val topLeft = Offset(minOf(r[0], r[2]), minOf(r[1], r[3]))
        val size = androidx.compose.ui.geometry.Size(kotlin.math.abs(r[2] - r[0]), kotlin.math.abs(r[3] - r[1]))
        drawRect(Color(0xFF26C6DA).copy(alpha = .15f), topLeft, size)
        drawRect(Color(0xFF26C6DA), topLeft, size, style = Stroke(2.dp.toPx()))
    }
}

/** Путь pivot в координатах проекта. Canvas только в редакторе — в экспортную сцену не попадает. */
@Composable
private fun MotionPathLayer(vm: MainViewModel) {
    if (com.vibetoon.ui.theme.LocalAppSettings.current?.showMotionPath != true) return
    val id by vm.selected.collectAsStateWithLifecycle()
    val animation by vm.animation.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val bones by vm.bones.collectAsStateWithLifecycle()
    val clips by vm.clips.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val props by vm.layerPropsState.collectAsStateWithLifecycle()
    val bindPose by vm.bindPose.collectAsStateWithLifecycle()
    val boneMode by vm.boneMode.collectAsStateWithLifecycle()
    val view by vm.gridView.collectAsStateWithLifecycle()
    val path = remember(id, animation, links, bones, clips, rows, props, bindPose, boneMode) { id?.let(vm::motionPathFor) }
        ?: return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        if (view.width <= 0f || view.height <= 0f || view.canvasWidth <= 0 || view.canvasHeight <= 0) return@Canvas
        fun screen(p: com.vibetoon.domain.anim.PathPoint): Offset {
            val (sx, sy) = view.camera.toScreen(p.x, p.y, view.width, view.height, view.canvasWidth, view.canvasHeight)
            return Offset(sx, sy)
        }
        for (segment in path.segments) for ((a, b) in segment.zipWithNext()) {
            drawLine(Color.Black.copy(alpha = .55f), screen(a), screen(b), 4.dp.toPx())
            drawLine(Color(0xFF26C6DA), screen(a), screen(b), 2.dp.toPx())
        }
        for (p in path.keys) {
            drawCircle(Color.Black, 5.dp.toPx(), screen(p))
            drawCircle(Color(0xFF26C6DA), 3.dp.toPx(), screen(p))
        }
    }
}

/** Сетка только в редакторе: координаты следуют виду и не поступают в SceneBuilder/экспорт. */
@Composable
private fun CanvasGridLayer(vm: MainViewModel) {
    val view by vm.gridView.collectAsStateWithLifecycle()
    val cells = com.vibetoon.ui.theme.LocalAppSettings.current?.gridCells ?: 16
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val color = Color.Black.copy(alpha = 0.28f)
        val width = 1.dp.toPx()
        val cam = view.camera
        if (cam.rotDeg != 0f || cam.flipX || cam.flipY) {
            // Повёрнутый/отражённый вид: линии строятся в координатах холста и переводятся камерой.
            if (view.width <= 0f || view.height <= 0f || view.canvasWidth <= 0 || view.canvasHeight <= 0) return@Canvas
            val cw = view.canvasWidth.toFloat(); val ch = view.canvasHeight.toFloat()
            val step = cw / com.vibetoon.domain.render.CanvasGrid.density(cells)
            fun pt(x: Float, y: Float): Offset { val (sx, sy) = cam.toScreen(x, y, view.width, view.height, view.canvasWidth, view.canvasHeight); return Offset(sx, sy) }
            var x = step
            while (x < cw - 0.5f) { drawLine(color, pt(x, 0f), pt(x, ch), width); x += step }
            var y = step
            while (y < ch - 0.5f) { drawLine(color, pt(0f, y), pt(cw, y), width); y += step }
            return@Canvas
        }
        val grid = com.vibetoon.domain.render.CanvasGrid.lines(view.width, view.height, view.canvasWidth, view.canvasHeight, view.camera, cells) ?: return@Canvas
        for (x in grid.vertical) drawLine(color, Offset(x, grid.top), Offset(x, grid.bottom), width)
        for (y in grid.horizontal) drawLine(color, Offset(grid.left, y), Offset(grid.right, y), width)
    }
}

/** Скелет поверх холста: ромбик в суставе, линия, кружок на конце; выбранная кость оранжевая. */
@Composable
private fun BoneOverlayLayer(vm: MainViewModel) {
    val overlay by vm.boneOverlay.collectAsStateWithLifecycle()
    val colored = com.vibetoon.ui.theme.LocalAppSettings.current?.boneColors == true
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
            draw(b.hx, b.hy, b.tx, b.ty, if (b.selected) Color(0xFFFFA726) else if (colored) Color.hsv(((b.id.toLong() * 137L % 360L + 360L) % 360L).toFloat(), 0.7f, 0.95f) else Color(0xFF26C6DA))
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
    val w = overlay.width; val h = overlay.height
    if (w !in 2..33 || h !in 2..33 || p.size != w * h * 2) return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val line = Color(0xFFFFA726)
        fun pt(k: Int) = Offset(p[k * 2], p[k * 2 + 1])
        for (j in 0 until h) for (i in 0 until w) {
            val k = j * w + i
            if (i < w - 1) drawLine(Color.Black.copy(alpha = 0.5f), pt(k), pt(k + 1), strokeWidth = 4.dp.toPx())
            if (j < h - 1) drawLine(Color.Black.copy(alpha = 0.5f), pt(k), pt(k + w), strokeWidth = 4.dp.toPx())
        }
        for (j in 0 until h) for (i in 0 until w) {
            val k = j * w + i
            if (i < w - 1) drawLine(line, pt(k), pt(k + 1), strokeWidth = 1.5.dp.toPx())
            if (j < h - 1) drawLine(line, pt(k), pt(k + w), strokeWidth = 1.5.dp.toPx())
        }
        for (k in 0 until w * h) {
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
private fun RulerGuideLayer(vm: MainViewModel) {
    val guide by vm.rulerGuide.collectAsStateWithLifecycle()
    val mode by vm.rulerMode.collectAsStateWithLifecycle()
    val gradient by vm.gradientMode.collectAsStateWithLifecycle()
    if (guide.size != 4) return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val a = Offset(guide[0], guide[1]); val b = Offset(guide[2], guide[3])
        val radius = (b - a).getDistance()
        val color = Color(0xFFFFA726)
        if (mode == com.vibetoon.domain.paint.RulerPath.Mode.COMPASS) {
            drawCircle(color, radius, a, style = Stroke(2.dp.toPx()))
            drawLine(color.copy(alpha = .7f), a, b, strokeWidth = 1.dp.toPx())
        } else if (gradient || mode == com.vibetoon.domain.paint.RulerPath.Mode.LINE) {
            drawLine(Color.Black, a, b, strokeWidth = 4.dp.toPx())
            drawLine(color, a, b, strokeWidth = 2.dp.toPx())
        }
    }
}

/** Панель метрик производительности (Настройки -> General): только в редакторе, не попадает в экспорт. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PerfHudLayer(vm: MainViewModel) {
    val hud by vm.perfHud.collectAsStateWithLifecycle()
    val text = hud ?: return
    Box(
        Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101418).copy(alpha = 0.78f)).padding(horizontal = 8.dp, vertical = 4.dp)
    ) { Text(text, color = Color(0xFFB2FF59), fontSize = 10.sp, lineHeight = 12.sp) }
}

/** Бейдж угла поворота холста: виден при ненулевом повороте; тап сбрасывает в 0°, долгий тап — поворот на 90°. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CameraAngleBadge(vm: MainViewModel) {
    val view by vm.gridView.collectAsStateWithLifecycle()
    val rot = view.camera.rotDeg
    if (kotlin.math.abs(rot) < 0.5f) return
    val shown = ((rot % 360f) + 360f) % 360f
    Box(
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF20242B).copy(alpha = 0.85f))
            .combinedClickable(onClick = { vm.resetCameraRotation() }, onLongClick = { vm.rotateCamera90() })
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("${shown.toInt()}°", color = Color.White, fontSize = 13.sp)
    }
}

/** Маркер пивота выделенного узла: перетаскивается пальцем/пером, снап к центру и углам рамки. */
@Composable
private fun PivotOverlay(vm: MainViewModel) {
    val id by vm.selected.collectAsStateWithLifecycle()
    val inspector by vm.inspector.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val view by vm.gridView.collectAsStateWithLifecycle()   // перерисовка при сдвиге/повороте вида
    val frame by vm.frame.collectAsStateWithLifecycle()
    val paintTool by vm.paintTool.collectAsStateWithLifecycle()
    val rasterTool by vm.rasterTool.collectAsStateWithLifecycle()
    val boneMode by vm.boneMode.collectAsStateWithLifecycle()
    val warpTool by vm.warpTool.collectAsStateWithLifecycle()
    val sel = id ?: return
    if (paintTool != null || rasterTool != null || boneMode || warpTool) return
    val info = inspector ?: return
    if (view.width <= 0f || frame < 0) return
    if (playing) return
    val p = vm.pivotScreen(sel) ?: return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val c = Offset(p.first, p.second)
        // Центр задаётся явно: без center drawCircle рисует в центре Canvas (то есть экрана), и раньше кольцо с точкой
        // «отрывалось» от крестика пивота и висело посреди холста.
        val shadow = Color.Black.copy(alpha = 0.45f)
        val col = Color(0xFFFFC107)
        val arm = 14.dp.toPx()
        drawCircle(shadow, radius = 9.dp.toPx(), center = c, style = Stroke(3.5.dp.toPx()))
        drawLine(shadow, c + Offset(-arm, 0f), c + Offset(arm, 0f), 3.dp.toPx())
        drawLine(shadow, c + Offset(0f, -arm), c + Offset(0f, arm), 3.dp.toPx())
        drawCircle(col, radius = 9.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
        drawCircle(col, radius = 2.5.dp.toPx(), center = c)
        drawLine(col, c + Offset(-arm, 0f), c + Offset(arm, 0f), 1.5.dp.toPx())
        drawLine(col, c + Offset(0f, -arm), c + Offset(0f, arm), 1.5.dp.toPx())
    }
}

@Composable
private fun PaintCursorLayer(vm: MainViewModel) {
    if (!com.vibetoon.domain.input.EditorInputPolicy.showCursor(com.vibetoon.ui.theme.LocalAppSettings.current?.cursorEnabled != false)) return
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
    if (!com.vibetoon.domain.input.EditorInputPolicy.showCursor(com.vibetoon.ui.theme.LocalAppSettings.current?.cursorEnabled != false)) return
    val c by vm.brushCircle.collectAsStateWithLifecycle()
    val b = c ?: return
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        drawCircle(Color.Black.copy(alpha = 0.5f), b[2], Offset(b[0], b[1]), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        drawCircle(Color.White, b[2], Offset(b[0], b[1]), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}

/** Perspective: четыре угла и четыре ручки сторон; боковые перемещают оба угла. */
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
        for (k in 0 until minOf(8, p.size / 2)) {
            val c = Offset(p[k * 2], p[k * 2 + 1])
            drawCircle(Color.White, 10.dp.toPx(), c)
            drawCircle(line, 7.dp.toPx(), c)
        }
    }
}
