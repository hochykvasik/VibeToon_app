package com.vibetoon.ui.shell

import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import android.app.Activity
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.zIndex
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vibetoon.ui.RasterTool
import com.vibetoon.ui.SelMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.vibetoon.domain.model.LayerBlend
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.vibetoon.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.ui.AddActions
import com.vibetoon.ui.BoneTool
import com.vibetoon.ui.MeshTool
import com.vibetoon.ui.EditorCanvas
import com.vibetoon.ui.thumbnailChecker
import com.vibetoon.ui.MainViewModel
import com.vibetoon.ui.StatusLine
import com.vibetoon.ui.inspector.InspectorPanel
import com.vibetoon.ui.timeline.TimelinePanel
import kotlinx.coroutines.launch

/** Заглушка кнопки, у которой ещё нет логики: показывает Snackbar "<что> — в разработке". */
val LocalStub = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Ширина, с которой панели становятся шире и все кнопки верхней строки видны без меню "Ещё". */
private val WIDE = 840.dp

/** Ширина меньше этой - телефон: правая панель выезжает поверх холста, ряд инструментов таймлайна прячется под «Ещё». */
private val PHONE_MAX = 800.dp

/** true на узком экране (телефон). */
val LocalPhone = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Каркас ландшафтного редактора (как у ToonSquid):
 *   верхняя панель
 *   [ слева инструменты | центр: холст + таймлайн | справа слои и свойства ]
 * Правая панель и таймлайн сворачиваются, холст занимает всё освободившееся место.
 */
@Composable
fun EditorShell(vm: MainViewModel, onBack: () -> Unit, add: AddActions, onExport: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val stub: (String) -> Unit = { what ->
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar("$what — в разработке")
        }
    }
    var showSettings by remember { mutableStateOf(false) }
    var rightOpen by rememberSaveable { mutableStateOf(true) }
    var timelineOpen by rememberSaveable { mutableStateOf(true) }
    var userTimelineDp by rememberSaveable { mutableStateOf(0f) }   // 0 - высота по умолчанию, иначе заданная хватом
    var tool by rememberSaveable { mutableStateOf(ToolId.TRANSFORM.name) }
    var mesh by rememberSaveable { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var showExportChooser by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(0xFFE53935L) }
    var showColor by remember { mutableStateOf(false) }
    val boneMode by vm.boneMode.collectAsStateWithLifecycle()
    val symEdit by vm.editingSymbol.collectAsStateWithLifecycle()
    var markerEdit by remember { mutableStateOf(false) }
    if (markerEdit && symEdit != null) com.vibetoon.ui.MarkerEditDialog(vm) { markerEdit = false }
    val bindPoseNow by vm.bindPose.collectAsStateWithLifecycle()
    val boneToolNow by vm.boneTool.collectAsStateWithLifecycle()
    val warpOn by vm.warpTool.collectAsStateWithLifecycle()
    // инструмент Warp активен только в режиме объектов
    androidx.compose.runtime.LaunchedEffect(tool, boneMode, mesh) { vm.setWarpTool(tool == ToolId.WARP.name && !boneMode && !mesh) }
    androidx.compose.runtime.LaunchedEffect(boneMode, mesh) { vm.setMeshMode(mesh && !boneMode) }
    val perspOn by vm.perspTool.collectAsStateWithLifecycle()
    val liqOn by vm.liquifyTool.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(tool, boneMode, mesh) { vm.setLiquifyTool(tool == ToolId.LIQUIFY.name && !boneMode && !mesh) }
    androidx.compose.runtime.LaunchedEffect(tool, boneMode, mesh) { vm.setPerspTool(tool == ToolId.PERSPECTIVE.name && !boneMode && !mesh) }
    val paintOn by vm.paintTool.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(tool, boneMode, mesh) {
        vm.setPaintTool(
            if (boneMode || mesh) null else when (tool) {
                ToolId.BRUSH.name -> MainViewModel.PaintTool.BRUSH
                ToolId.ERASER.name -> MainViewModel.PaintTool.ERASER
                else -> null
            }
        )
    }
    androidx.compose.runtime.LaunchedEffect(color) { vm.setBrushColor(color.toInt()) }
    // растровые инструменты: заливка, пипетка, выделение, смазка, перо
    androidx.compose.runtime.LaunchedEffect(tool, boneMode, mesh) {
        vm.setRasterTool(
            if (boneMode || mesh) null else when (tool) {
                ToolId.FILL.name -> RasterTool.FILL
                ToolId.PIPETTE.name -> RasterTool.PIPETTE
                ToolId.SELECTION.name -> RasterTool.SELECT
                ToolId.SMUDGE.name -> RasterTool.SMUDGE
                ToolId.PATH.name -> RasterTool.PATH
                else -> null
            }
        )
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.pickedColor.collect { color = it } }
    val rasterOn by vm.rasterTool.collectAsStateWithLifecycle()
    val meshToolNow by vm.meshTool.collectAsStateWithLifecycle()
    val view = LocalView.current
    // Полный экран: прячутся панели самого VibeToon (верхняя, инструменты, таймлайн, правая), остаётся холст и кнопка выхода;
    // системными панелями Android управляет Immersive Sticky в MainActivity
    fun toggleFullscreen() { full = !full }
    val toolTitle = when {
        boneMode -> when (boneToolNow) { BoneTool.ADD -> "Кости: добавить кость"; BoneTool.LASSO -> "Кости: лассо"; else -> "Кости: двигать кости" }
        mesh -> "Сетка"
        else -> ToolId.valueOf(tool).title
    }

    CompositionLocalProvider(LocalStub provides stub) {
        Scaffold(
            snackbarHost = { SnackbarHost(snack) { data -> androidx.compose.material3.Snackbar { Text(data.visuals.message) } } },
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.background
        ) { pad ->
            // Edge-to-Edge: холст тянется на весь экран; от выреза камеры и системных панелей отступают только панели с кнопками
            BoxWithConstraints(Modifier.padding(pad).fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
                CompositionLocalProvider(LocalPhone provides (maxWidth < PHONE_MAX)) {
                val wide = maxWidth >= WIDE
                val phone = maxWidth < PHONE_MAX
                // на телефоне правая панель по умолчанию закрыта: холст получает всю ширину
                androidx.compose.runtime.LaunchedEffect(phone) { if (phone) rightOpen = false }
                if (!rightOpen && !full) Box(
                    Modifier.align(Alignment.CenterEnd).zIndex(10f).width(36.dp).height(200.dp)   // поверх холста; зона исключена из системного жеста «Назад» (макс. 200 dp высоты)
                        .systemGestureExclusion()
                        .pointerInput(Unit) {
                            var sum = 0f
                            detectHorizontalDragGestures(onDragStart = { sum = 0f }, onDragEnd = { if (sum < -30f) rightOpen = true }) { c, d -> c.consume(); sum += d }
                        }
                )
                val tallScreen = maxHeight >= 480.dp
                val maxHdp = maxHeight.value
                // при открытой клавиатуре высота может стать меньше нижней границы: пределы подгоняются, чтобы coerceIn не падал
                val tlMin = minOf(if (phone) 120.dp else 150.dp, maxHeight * 0.75f)
                val timelineH = if (userTimelineDp > 0f) userTimelineDp.dp.coerceIn(minOf(96.dp, maxHeight * 0.75f), maxOf(96.dp, maxHeight * 0.75f))
                    else (maxHeight * (if (phone) 0.34f else 0.4f)).coerceIn(tlMin, maxOf(tlMin, 340.dp))
                Column(Modifier.fillMaxSize()) {
                    if (!full) TopBar(
                        vm, wide, onBack, add, onExport,
                        rightOpen = rightOpen, onToggleRight = { rightOpen = !rightOpen },
                        onSettings = { showSettings = true }, onFullscreen = ::toggleFullscreen,
                        modes = !tallScreen,   // на низком экране режимы живут в верхней строке
                        mesh = mesh, onMesh = { mesh = it }
                    )
                    if (tallScreen && !full) ModeBar(vm, mesh) { mesh = it }
                    // Режим левши: Row раскладывается справа налево (панель инструментов справа, слои слева), содержимое каждой части остаётся обычным
                    val leftHanded = com.vibetoon.ui.theme.LocalAppSettings.current?.leftHanded == true
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides (if (leftHanded) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr)) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (!full) CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) { Box(Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))) { LeftToolbar(
                            tool, wide, boneMode, mesh, boneToolNow, meshToolNow, vm,
                            onPick = { t ->
                                when (t) {
                                    ToolId.TEXT -> add.text()
                                    ToolId.TRANSFORM, ToolId.WARP, ToolId.PERSPECTIVE, ToolId.LIQUIFY, ToolId.BRUSH, ToolId.ERASER,
                                    ToolId.FILL, ToolId.PIPETTE, ToolId.SELECTION, ToolId.SMUDGE, ToolId.PATH -> tool = t.name
                                    else -> {
                                        val again = tool == t.name
                                        tool = t.name
                                        if (again && t in BRUSH_LIKE) stub("Настройки инструмента «${t.title}»") else stub("Инструмент «${t.title}»")
                                    }
                                }
                            }
                        ) } }
                        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                EditorCanvas(vm, Modifier.fillMaxSize())
                                val markerPanel by vm.markerPanel.collectAsStateWithLifecycle()
                                androidx.compose.animation.AnimatedVisibility(visible = markerPanel != null && !full, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.92f), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.92f)) {
                                    val last = remember { mutableStateOf(markerPanel) }
                                    if (markerPanel != null) last.value = markerPanel
                                    last.value?.let { mid -> com.vibetoon.ui.MarkerPanel(vm, mid) { vm.closeMarkerPanel() } }
                                }
                                // индикатор текущего инструмента
                                if (!full) Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                ) { Text(toolTitle, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) }
                                if (full) IconButton(
                                    onClick = ::toggleFullscreen,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(40.dp)
                                        .clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                ) { Icon(Icons.Default.FullscreenExit, "Выйти из полного экрана") }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = warpOn && !full, modifier = Modifier.align(Alignment.TopStart),
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 }
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { WarpToolRow(vm) }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = liqOn && !full, modifier = Modifier.align(Alignment.TopStart),
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 }
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { LiquifyToolRow(vm) }
                                }
                                val brushMenuOpen by vm.brushMenu.collectAsStateWithLifecycle()
                                if (brushMenuOpen && paintOn != null && !full && !boneMode && !mesh) {
                                    // Popup не обрезается холстом: на низком экране список кистей виден целиком
                                    val off = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.unit.IntOffset(6.dp.roundToPx(), 56.dp.roundToPx()) }
                                    androidx.compose.ui.window.Popup(
                                        alignment = Alignment.TopStart, offset = off,
                                        onDismissRequest = { vm.closeBrushMenu() },
                                        properties = androidx.compose.ui.window.PopupProperties(focusable = true)
                                    ) {
                                        val enter = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                                        androidx.compose.animation.AnimatedVisibility(
                                            visibleState = enter,
                                            enter = androidx.compose.animation.slideInHorizontally { -it } + androidx.compose.animation.fadeIn()
                                        ) { BrushMenu(vm) }
                                    }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = paintOn != null && !full && !boneMode && !mesh, modifier = Modifier.align(Alignment.TopStart),
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 }
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { PaintToolRow(vm) { showColor = true } }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = perspOn && !full, modifier = Modifier.align(Alignment.TopStart),
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 }
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { PerspToolRow(vm) }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = mesh && !boneMode && !full, modifier = Modifier.align(Alignment.TopStart),
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 }
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { MeshToolRow(vm) }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = boneMode && !full, modifier = Modifier.align(Alignment.TopStart),
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 }
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { BoneToolRow(vm) }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = rasterOn != null && !full && !boneMode && !mesh, modifier = Modifier.align(Alignment.TopStart),
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 }
                                ) {
                                    val shown = remember { mutableStateOf(rasterOn) }
                                    if (rasterOn != null) shown.value = rasterOn
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) {
                                        RasterToolRow(vm, shown.value)
                                    }
                                }
                                symEdit?.let { sid ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.align(Alignment.TopCenter).padding(6.dp)
                                    ) {
                                        Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                            androidx.compose.material3.TextButton(onClick = { vm.exitSymbol() }) { Text("◀ На главную сцену") }
                                            Text("Символ: ${vm.symbolName(sid)}", fontSize = 13.sp)
                                            androidx.compose.material3.TextButton(onClick = { markerEdit = true }) { Text("Маркеры") }
                                        }
                                    }
                                }
                                Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                                    StatusLine(vm)
                                }
                            }
                            // хват на верхней границе таймлайна: тянуть вверх/вниз - меняет высоту панели
                            if (!full && timelineOpen) {
                                val density = androidx.compose.ui.platform.LocalDensity.current
                                Box(
                                    Modifier.fillMaxWidth().height(18.dp)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .pointerInput(Unit) {
                                            detectVerticalDragGestures { change, dy ->
                                                change.consume()
                                                val cur = if (userTimelineDp > 0f) userTimelineDp else timelineH.value
                                                userTimelineDp = (cur - dy / density.density).coerceIn(minOf(96f, maxHdp * 0.75f), maxOf(96f, maxHdp * 0.75f))
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
                                }
                            }
                            if (!full) TimelinePanel(
                                vm,
                                modifier = if (timelineOpen) Modifier.height(timelineH) else Modifier,
                                fill = true, collapsed = !timelineOpen,
                                onToggleCollapse = { timelineOpen = !timelineOpen }
                            )
                        } }
                        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = rightOpen && !full && !phone,
                            enter = androidx.compose.animation.slideInHorizontally { if (leftHanded) -it else it } + androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.slideOutHorizontally { if (leftHanded) -it else it } + androidx.compose.animation.fadeOut()
                        ) {
                            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))) { RightPanel(
                                vm, add, color, { showColor = true }, { rightOpen = false },
                                Modifier.width(if (wide) 320.dp else 236.dp).fillMaxHeight()
                            ) }
                        } }
                    } }
                }
                // телефон: правая панель выезжает поверх холста и закрывается кнопкой в её шапке или в верхней панели
                // тап по холсту рядом с панелью закрывает её
                if (phone && rightOpen && !full) Box(
                    Modifier.fillMaxSize().padding(top = 48.dp).pointerInput(Unit) { detectTapGestures { rightOpen = false } }
                )
                if (phone) androidx.compose.animation.AnimatedVisibility(
                    visible = rightOpen && !full,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(top = 48.dp),
                    enter = androidx.compose.animation.slideInHorizontally { it } + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.slideOutHorizontally { it } + androidx.compose.animation.fadeOut()
                ) {
                    Surface(shadowElevation = 12.dp, tonalElevation = 3.dp, shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)) {
                        RightPanel(
                            vm, add, color, { showColor = true }, { rightOpen = false },
                            Modifier.width(minOf(maxWidth * 0.62f, 300.dp)).fillMaxHeight()
                        )
                    }
                }
                }
            }
        }
    }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
    if (showColor) com.vibetoon.ui.inspector.ColorPickerDialog(
        initial = color, onColor = { color = it }, onEyedropper = { stub("Пипетка для текущего цвета") }, onDismiss = { showColor = false }
    )
}

// ---------------------------------------------------------------- верхняя панель

/** Рамка находится в том же Popup и layout, что и пункт меню: скролл/поворот не меняет её систему координат. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TestPsdMenuItem(highlight: Boolean, onClick: () -> Unit) {
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.DisposableEffect(highlight) {
        onDispose { if (highlight) Coach.popupTargetVisible = false }
    }
    androidx.compose.runtime.LaunchedEffect(highlight) {
        if (highlight) {
            kotlinx.coroutines.delay(150) // дождаться измерения и открытия Popup
            requester.bringIntoView()
        }
    }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "popup-coach")
    val alpha by transition.animateFloat(0.55f, 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "popup-coach-alpha")
    DropdownMenuItem(
        text = { Text("Тестовый PSD") }, onClick = onClick,
        modifier = Modifier.bringIntoViewRequester(requester).onGloballyPositioned { coordinates ->
            if (highlight) {
                val r = coordinates.boundsInWindow()
                Coach.popupTargetVisible = r.width > 0f && r.height > 0f
            }
        }.then(if (highlight) Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(6.dp)) else Modifier)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(
    vm: MainViewModel, wide: Boolean, onBack: () -> Unit, add: AddActions, onExport: () -> Unit,
    rightOpen: Boolean, onToggleRight: () -> Unit, onSettings: () -> Unit, onFullscreen: () -> Unit, modes: Boolean,
    mesh: Boolean, onMesh: (Boolean) -> Unit
) {
    val stub = LocalStub.current
    val name by vm.projectName.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val fxOn by vm.fx.collectAsStateWithLifecycle()
    var actions by remember { mutableStateOf(false) }
    val coachTarget = Coach.session?.steps?.getOrNull(Coach.index)?.target
    androidx.compose.runtime.LaunchedEffect(coachTarget) {
        if (coachTarget == "Тестовый PSD") actions = true
    }
    var more by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            BarIcon(Icons.Default.ArrowBack, "Проекты", onClick = onBack)
            var showLibrary by remember { mutableStateOf(false) }
            BarIcon(Icons.Default.PhotoLibrary, "Библиотека") { showLibrary = true }
            if (showLibrary) AssetLibraryDialog(vm) { showLibrary = false }
            Box {
                BarIcon(Icons.Default.Menu, "Действия") { actions = true }
                DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                    DropdownMenuItem(text = { Text("Экспорт…") }, onClick = { actions = false; onExport() })
                    DropdownMenuItem(text = { Text("Настройки") }, onClick = { actions = false; onSettings() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("PSD-файл") }, onClick = { actions = false; add.psd() })
                    DropdownMenuItem(text = { Text("Изображение (PNG, JPG)") }, onClick = { actions = false; add.image() })
                    DropdownMenuItem(text = { Text("Изображение как символ") }, onClick = { actions = false; add.imageAsSymbol() })
                    DropdownMenuItem(text = { Text("Видео (MP4)") }, onClick = { actions = false; add.video() })
                    DropdownMenuItem(text = { Text("Аудио (MP3, WAV)") }, onClick = { actions = false; add.audio() })
                    DropdownMenuItem(text = { Text("Текст") }, onClick = { actions = false; add.text() })
                    DropdownMenuItem(text = { Text("Камера (Pan & Zoom)") }, onClick = { actions = false; vm.addCameraLayer() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Сбросить вид холста") }, onClick = { actions = false; vm.resetCamera() })
                    DropdownMenuItem(text = { Text("Повернуть холст на 90°") }, onClick = { actions = false; vm.rotateCamera90() })
                    DropdownMenuItem(text = { Text("Сбросить поворот холста (0°)") }, onClick = { actions = false; vm.resetCameraRotation() })
                    DropdownMenuItem(text = { Text("Отразить холст по горизонтали") }, onClick = { actions = false; vm.flipCameraX() })
                    DropdownMenuItem(text = { Text("Отразить холст по вертикали") }, onClick = { actions = false; vm.flipCameraY() })
                    TestPsdMenuItem(highlight = coachTarget == "Тестовый PSD") { actions = false; vm.parseTestPsd() }
                }
            }
            Text(
                name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            if (modes) ModeChips(vm, mesh, onMesh)
            if (com.vibetoon.ui.theme.LocalAppSettings.current?.refocusButton == true) {
                Box(Modifier.size(40.dp).combinedClickable(onClick = {}, onDoubleClick = vm::resetCamera), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CropFree, com.vibetoon.ui.i18n.L("Дважды нажмите, чтобы сбросить вид холста"), modifier = Modifier.size(22.dp))
                }
            }
            BarIcon(Icons.Default.AutoAwesome, "FX: вкл — правки пишутся в ключи, выкл — в базовую форму", active = fxOn) { vm.toggleFx() }
            BarIcon(Icons.Default.Undo, "Отменить", enabled = canUndo && !playing) { vm.undo() }
            BarIcon(Icons.Default.Redo, "Повторить", enabled = canRedo && !playing) { vm.redo() }
            Button(onClick = onExport, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(34.dp).coachTarget("Export")) {
                Text("Export", fontSize = 13.sp, maxLines = 1)
            }
            if (wide) {
                BarIcon(Icons.Default.Settings, "Настройки", onClick = onSettings)
                BarIcon(Icons.Default.Fullscreen, "Полный экран", onClick = onFullscreen)
                BarIcon(Icons.Default.ViewSidebar, "Правая панель", active = rightOpen, onClick = onToggleRight)
            } else {
                // телефон: второстепенное прячется в меню "Ещё"
                Box {
                    BarIcon(Icons.Default.MoreVert, "Ещё") { more = true }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("Настройки") }, onClick = { more = false; onSettings() })
                        DropdownMenuItem(text = { Text("Полный экран") }, onClick = { more = false; onFullscreen() })
                        DropdownMenuItem(text = { Text(if (rightOpen) "Скрыть правую панель" else "Показать правую панель") }, onClick = { more = false; onToggleRight() })
                    }
                }
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, desc: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp).coachTarget(desc)) {
        Icon(
            icon, desc, modifier = Modifier.size(22.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current
        )
    }
}

/** Режимы: Объект / Кости / Сетка (Mesh). Сетка пока только выбирает набор инструментов. */
@Composable
private fun ModeChips(vm: MainViewModel, mesh: Boolean, onMesh: (Boolean) -> Unit) {
    val boneMode by vm.boneMode.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(modifier = Modifier.coachTarget("chip:Объект"), selected = !boneMode && !mesh, onClick = { onMesh(false); vm.setBoneMode(false) }, label = { Text("Объект", fontSize = 12.sp, maxLines = 1, softWrap = false) })
        FilterChip(modifier = Modifier.coachTarget("chip:Кости"), selected = boneMode, onClick = { onMesh(false); vm.setBoneMode(true) }, label = { Text("Кости", fontSize = 12.sp, maxLines = 1, softWrap = false) })
        FilterChip(modifier = Modifier.coachTarget("chip:Сетка"), selected = mesh, onClick = { vm.setBoneMode(false); onMesh(true) }, label = { Text("Сетка", fontSize = 12.sp, maxLines = 1, softWrap = false) })
    }
}

@Composable
private fun ModeBar(vm: MainViewModel, mesh: Boolean, onMesh: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(38.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { ModeChips(vm, mesh, onMesh) }
    }
}

/** Выезжающая панель кистей: список пресетов с превью S-образного мазка; тап выбирает кисть. */
@Composable
private fun BrushMenu(vm: MainViewModel) {
    val tool by vm.paintTool.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val eraser by vm.eraser.collectAsStateWithLifecycle()
    val current = if (tool == MainViewModel.PaintTool.ERASER) eraser.presetId else brush.presetId
    val items = vm.presetsForTool()
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = Modifier.width(300.dp).heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.78f).dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tool == MainViewModel.PaintTool.ERASER) "Ластики" else "Кисти", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Close, "Закрыть", modifier = Modifier.clickable { vm.closeBrushMenu() }.padding(6.dp).size(22.dp))
            }
            androidx.compose.foundation.lazy.LazyColumn {
                items(items.size) { i ->
                    val pr = items[i]
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (pr.id == current) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { vm.selectPreset(pr) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.width(150.dp).height(38.dp).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color(0xFFF2F2F2)),
                            contentAlignment = Alignment.Center
                        ) {
                            val prev = vm.brushPreviews[pr.id]
                            if (prev != null) androidx.compose.foundation.Image(prev, com.vibetoon.ui.i18n.L(pr.title), modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                        }
                        Text(pr.title, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
}

/** Панель растровых инструментов: параметры заливки, режимы выделения, смазка, перо. */
@Composable
private fun RasterToolRow(vm: MainViewModel, tool: RasterTool?) {
    val fp by vm.fillParams.collectAsStateWithLifecycle()
    val sp by vm.smudgeParams.collectAsStateWithLifecycle()
    val mode by vm.selMode.collectAsStateWithLifecycle()
    val has by vm.hasSel.collectAsStateWithLifecycle()
    val pendingLasso by vm.pendingLasso.collectAsStateWithLifecycle()
    val pen by vm.penCount.collectAsStateWithLifecycle()
    val fillOn by vm.vecFill.collectAsStateWithLifecycle()
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        when (tool) {
            RasterTool.FILL -> {
                Text("Заливка", fontSize = 12.sp, maxLines = 1, softWrap = false)
                com.vibetoon.ui.inspector.ScrubBox("Порог", fp.threshold.toFloat(), 0f, 128f, { v -> vm.setFillParams { it.copy(threshold = v.toInt()) } }, perPx = 0.4f)
                com.vibetoon.ui.inspector.ScrubBox("Расшир.", fp.expand.toFloat(), 0f, 6f, { v -> vm.setFillParams { it.copy(expand = v.toInt()) } }, perPx = 0.05f)
                com.vibetoon.ui.inspector.ScrubBox("Зазор", fp.gap.toFloat(), 0f, 8f, { v -> vm.setFillParams { it.copy(gap = v.toInt()) } }, perPx = 0.05f)
            }
            RasterTool.PIPETTE -> Text("Пипетка: коснитесь холста, чтобы взять цвет", fontSize = 12.sp, maxLines = 1, softWrap = false)
            RasterTool.SELECT -> {
                for ((m, t) in listOf(SelMode.RECT to "Прямоугольник", SelMode.OVAL to "Овал", SelMode.LASSO to "Лассо", SelMode.WAND to "Палочка"))
                    androidx.compose.material3.FilterChip(selected = mode == m, onClick = { vm.setSelMode(m) }, label = { Text(t, fontSize = 12.sp, maxLines = 1, softWrap = false) })
                if (pendingLasso) OutlinedButton(onClick = vm::cancelPendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Отменить контур", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (mode == SelMode.WAND) com.vibetoon.ui.inspector.ScrubBox("Порог", fp.threshold.toFloat(), 0f, 128f, { v -> vm.setFillParams { it.copy(threshold = v.toInt()) } }, perPx = 0.4f)
                if (has) {
                    Text("Два пальца: поворот и масштаб выделения", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    val partName = com.vibetoon.ui.i18n.L("часть")
                    OutlinedButton(onClick = { vm.mirrorSelectedPixels(true) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Отразить X", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.mirrorSelectedPixels(false) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Отразить Y", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.rotateSelectedPixels(false) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Повернуть −90°", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.rotateSelectedPixels(true) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Повернуть +90°", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.scaleSelectedPixels(false) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Размер ½", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.scaleSelectedPixels(true) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Размер ×2", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = vm::clearSelectionContent, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Очистить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.selectionToNewLayer(partName) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Вырезать в новый слой", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.copySelectionToNewLayer(partName) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Копировать в новый слой", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = vm::invertSelection, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Инверсия", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = vm::clearSelection, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Снять", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
            }
            RasterTool.SMUDGE -> {
                Text("Смазка", fontSize = 12.sp, maxLines = 1, softWrap = false)
                com.vibetoon.ui.inspector.ScrubBox("Размер", sp.size, 4f, 300f, { v -> vm.setSmudgeParams { it.copy(size = v) } }, log = true)
                com.vibetoon.ui.inspector.ScrubBox("Сила", sp.strength * 100f, 5f, 100f, { v -> vm.setSmudgeParams { it.copy(strength = v / 100f) } }, perPx = 0.35f)
            }
            RasterTool.PATH -> {
                Text("Перо", fontSize = 12.sp, maxLines = 1, softWrap = false)
                androidx.compose.material3.FilterChip(selected = fillOn, onClick = { vm.toggleVecFill() }, label = { Text("Заливка", fontSize = 12.sp, maxLines = 1, softWrap = false) })
                if (pen >= 2) OutlinedButton(onClick = { vm.penFinish(false) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Готово", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen >= 3) OutlinedButton(onClick = { vm.penFinish(true) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Замкнуть", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen >= 1) OutlinedButton(onClick = vm::penUndoPoint, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Убрать точку", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen == 0) OutlinedButton(onClick = vm::restyleSelectedVector, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Цвет и толщина кисти", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen == 0) OutlinedButton(onClick = vm::deleteVectorNode, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Удалить узел", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen == 0) OutlinedButton(onClick = vm::deleteVectorShape, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Удалить примитив", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen >= 1) OutlinedButton(onClick = vm::penCancel, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Отмена", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
            null -> {}
        }
    }
}

/** Панель рисования: режим, размер (логарифмический регулятор «потяни»), непрозрачность, жёсткость, форма, новый слой. */
@Composable
private fun PaintToolRow(vm: MainViewModel, onColor: () -> Unit) {
    val tool by vm.paintTool.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val eraser by vm.eraser.collectAsStateWithLifecycle()
    val isEraser = tool == MainViewModel.PaintTool.ERASER
    val p = if (isEraser) eraser else brush
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (isEraser) "Ластик" else "Кисть", fontSize = 12.sp, maxLines = 1, softWrap = false)
        OutlinedButton(onClick = vm::addPaintLayer, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Новый слой", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        if (!isEraser) {
            val gradient by vm.gradientMode.collectAsStateWithLifecycle()
            val alpha by vm.gradientAlpha.collectAsStateWithLifecycle()
            androidx.compose.material3.FilterChip(selected = gradient, onClick = vm::toggleGradient, label = { Text("Градиент", fontSize = 12.sp) })
            if (gradient) androidx.compose.material3.FilterChip(selected = alpha, onClick = vm::toggleGradientAlpha, label = { Text("По альфе рисунка", fontSize = 12.sp) })
        }
        val ruler by vm.rulerMode.collectAsStateWithLifecycle()
        var rulerMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { rulerMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                com.vibetoon.ui.DropText("Линейка")
            }
            androidx.compose.material3.DropdownMenu(expanded = rulerMenu, onDismissRequest = { rulerMenu = false }) {
                for ((mode, title) in listOf(
                    com.vibetoon.domain.paint.RulerPath.Mode.OFF to "Выкл",
                    com.vibetoon.domain.paint.RulerPath.Mode.LINE to "Обычная линейка",
                    com.vibetoon.domain.paint.RulerPath.Mode.COMPASS to "Циркуль"
                )) androidx.compose.material3.DropdownMenuItem(text = { Text((if (ruler == mode) "✓ " else "") + com.vibetoon.ui.i18n.L(title)) }, onClick = { rulerMenu = false; vm.setRulerMode(mode) })
            }
        }
        var shapeMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { shapeMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                com.vibetoon.ui.DropText("Фигуры")
            }
            androidx.compose.material3.DropdownMenu(expanded = shapeMenu, onDismissRequest = { shapeMenu = false }) {
                for (k in com.vibetoon.domain.paint.ShapeKind.values()) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(k.title) }, onClick = { shapeMenu = false; vm.addShape(k) })
                }
            }
        }
        // векторный слой: что рисует жест, заливка, откат последнего примитива
        val selId by vm.selected.collectAsStateWithLifecycle()
        if (selId?.let { vm.isVectorLayer(it) } == true) {
            val vk by vm.vecKind.collectAsStateWithLifecycle()
            val vf by vm.vecFill.collectAsStateWithLifecycle()
            for ((k, t) in listOf(
                com.vibetoon.domain.vector.VecKind.PATH to "Путь", com.vibetoon.domain.vector.VecKind.LINE to "Линия",
                com.vibetoon.domain.vector.VecKind.RECT to "Прямоугольник", com.vibetoon.domain.vector.VecKind.ELLIPSE to "Эллипс"
            )) androidx.compose.material3.FilterChip(selected = vk == k, onClick = { vm.setVecKind(k) }, label = { Text(t, fontSize = 12.sp, maxLines = 1, softWrap = false) })
            androidx.compose.material3.FilterChip(selected = vf, onClick = { vm.toggleVecFill() }, label = { Text("Заливка", fontSize = 12.sp, maxLines = 1, softWrap = false) })
            OutlinedButton(onClick = vm::removeLastVector, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                Text("Убрать последнюю", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = vm::clearVectors, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                Text("Очистить", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = vm::restyleSelectedVector, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                Text("Цвет/толщина", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }
        // выезжающая панель кистей: пресет с превью мазка
        val cur = com.vibetoon.domain.paint.BrushPresets.byId(p.presetId)
        OutlinedButton(onClick = vm::toggleBrushMenu, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(36.dp)) {
            val prev = vm.brushPreviews[p.presetId]
            if (prev != null) androidx.compose.foundation.Image(prev, null, modifier = Modifier.height(24.dp).width(64.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            com.vibetoon.ui.DropText(cur?.title ?: "Кисти", 12.sp, Modifier.padding(start = 6.dp))
        }
        com.vibetoon.ui.inspector.ScrubBox("Размер", p.size, 1f, 1500f, { v -> vm.updatePaint { it.copy(size = v) } }, log = true)
        com.vibetoon.ui.inspector.ScrubBox("Непр.", p.opacity * 100f, 1f, 100f, { v -> vm.updatePaint { it.copy(opacity = v / 100f) } }, perPx = 0.35f)
        com.vibetoon.ui.inspector.ScrubBox("Жёстк.", p.hardness * 100f, 0f, 100f, { v -> vm.updatePaint { it.copy(hardness = v / 100f) } }, perPx = 0.35f)
        if (!isEraser && p.mode == com.vibetoon.domain.paint.StrokeMode.ACCUMULATE)
            com.vibetoon.ui.inspector.ScrubBox("Flow", p.flow * 100f, 1f, 100f, { v -> vm.updatePaint { it.copy(flow = v / 100f) } }, perPx = 0.35f)
        if (!isEraser) Box(
            Modifier.size(32.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(brush.color))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onColor)
        )
    }
}

/** Панель Liquify: размер кисти и откат последнего мазка. */
@Composable
private fun LiquifyToolRow(vm: MainViewModel) {
    val radius by vm.liqRadius.collectAsStateWithLifecycle()
    val undo by vm.liqUndoCount.collectAsStateWithLifecycle()
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Liquify: проведите по картинке · кисть ${radius.toInt()}", fontSize = 12.sp, maxLines = 1, softWrap = false)
        com.vibetoon.ui.inspector.ScrubBox("R", radius, 20f, 600f, { vm.setLiqRadius(it) }, log = true)
        OutlinedButton(onClick = vm::liquifyUndo, enabled = undo > 0, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Откатить мазок", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
    }
}

/** Панель Perspective: подсказка по FX и сброс перспективы слоя. */
@Composable
private fun PerspToolRow(vm: MainViewModel) {
    val fx by vm.fx.collectAsStateWithLifecycle()
    val overlay by vm.perspOverlay.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val isGroup = rows.any { it.layer.id == selected && it.layer.kind != com.vibetoon.nativebridge.LayerKind.LAYER }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (overlay.isEmpty() && isGroup) "Перспектива для группы: нажмите «Включить для группы»" else if (overlay.isEmpty()) "Перспектива: выберите слой" else if (fx) "Перспектива · FX вкл: углы пишутся в ключи" else "Перспектива · FX выкл: базовая форма",
            fontSize = 12.sp, maxLines = 1, softWrap = false
        )
        if (isGroup && overlay.isEmpty()) OutlinedButton(onClick = vm::enableGroupPerspective,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Включить для группы", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        if (isGroup) OutlinedButton(onClick = vm::prepareGroupDeformationAnchor,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Подготовить область", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        if (isGroup) OutlinedButton(onClick = vm::clearGroupDeformationAnchor,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Удалить область", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        OutlinedButton(
            onClick = vm::resetPersp, enabled = overlay.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)
        ) { Text("Сбросить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
    }
}

/** Панель Mesh: сгенерировать сетку с выбранной плотностью и сбросить её. */
@Composable
private fun MeshToolRow(vm: MainViewModel) {
    val overlay by vm.meshOverlay.collectAsStateWithLifecycle()
    val fx by vm.fx.collectAsStateWithLifecycle()
    val tool by vm.meshTool.collectAsStateWithLifecycle()
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            when {
                overlay.points.isEmpty() -> "Сетка: выберите слой"
                tool == MeshTool.PIN_ADD -> "Тап по рисунку ставит пин"
                tool == MeshTool.PIN_MOVE -> if (fx) "FX вкл: пин пишется в ключи" else "FX выкл: тяните пин (форма привязки)"
                tool == MeshTool.ADD -> "Тап добавляет вершину"
                tool == MeshTool.DELETE -> "Тап по вершине удаляет её, сетка перестраивается"
                tool == MeshTool.CUT -> "Проведите по сетке: треугольники удаляются"
                tool == MeshTool.SMUDGE -> "Кисть: проведите по сетке, двигаются вершины"
                fx -> "FX вкл: вершины пишутся в ключи"
                else -> "FX выкл: форма привязки"
            },
            fontSize = 12.sp, maxLines = 1, softWrap = false
        )
        val pin by vm.pinInfo.collectAsStateWithLifecycle()
        if (tool == MeshTool.SMUDGE) {
            val bm by vm.brushMode.collectAsStateWithLifecycle()
            val br by vm.brushRadius.collectAsStateWithLifecycle()
            for ((mode, label) in listOf(
                com.vibetoon.domain.anim.BrushMath.Mode.GRAB to "Сдвиг", com.vibetoon.domain.anim.BrushMath.Mode.SMOOTH to "Разгладить",
                com.vibetoon.domain.anim.BrushMath.Mode.BLOAT to "Раздуть", com.vibetoon.domain.anim.BrushMath.Mode.PINCH to "Сжать"
            )) androidx.compose.material3.FilterChip(selected = bm == mode, onClick = { vm.setBrushMode(mode) }, label = { Text(label, fontSize = 12.sp) })
            com.vibetoon.ui.inspector.ScrubBox("R", br, 30f, 600f, { vm.setBrushRadius(it) }, log = true)
        }
        if (tool == MeshTool.PIN_ADD || tool == MeshTool.PIN_MOVE) {
            Text("Пин ${if (pin.selected >= 0) pin.selected + 1 else "-"}/${pin.count}", fontSize = 12.sp, maxLines = 1, softWrap = false)
            androidx.compose.material3.FilterChip(selected = !pin.stiff, onClick = { vm.setPinStiff(false) }, label = { Text("Move", fontSize = 12.sp) })
            androidx.compose.material3.FilterChip(selected = pin.stiff, onClick = { vm.setPinStiff(true) }, label = { Text("Stiff", fontSize = 12.sp) })
            com.vibetoon.ui.inspector.ScrubBox("R", pin.radius, 30f, 2000f, { vm.setPinRadius(it) }, log = true)
            OutlinedButton(onClick = vm::pinAddKey, enabled = pin.selected >= 0, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("◆ Key", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::pinDuplicate, enabled = pin.selected >= 0, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Дубль", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::pinResetAll, enabled = pin.count > 0, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Сброс", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = vm::pinDeleteSelected, enabled = pin.selected >= 0, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Удалить пин", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        val high by vm.meshHigh.collectAsStateWithLifecycle()
        androidx.compose.material3.FilterChip(selected = !high, onClick = { vm.setMeshHigh(false) }, label = { Text("Стандарт", fontSize = 12.sp) })
        androidx.compose.material3.FilterChip(selected = high, onClick = { vm.setMeshHigh(true) }, label = { Text("Высокая", fontSize = 12.sp) })
        Button(onClick = vm::generateMeshContour, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Авто-сетка", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        val skinned by vm.skinned.collectAsStateWithLifecycle()
        OutlinedButton(onClick = vm::toggleSkin, enabled = overlay.points.isNotEmpty(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            if (skinned) com.vibetoon.ui.IconText(Icons.Default.Check, "Кости") else Text("Кости", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        OutlinedButton(
            onClick = vm::resetMesh, enabled = overlay.points.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)
        ) { Text("Удалить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
    }
}

/** Панель инструмента Warp: подсказка по режиму FX и сброс деформации слоя. */
@Composable
private fun WarpToolRow(vm: MainViewModel) {
    val fx by vm.fx.collectAsStateWithLifecycle()
    val overlay by vm.warpOverlay.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val isGroup = rows.any { it.layer.id == selected && it.layer.kind != com.vibetoon.nativebridge.LayerKind.LAYER }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (overlay.points.isEmpty() && isGroup) "Warp для группы: нажмите «Включить для группы»" else if (overlay.points.isEmpty()) "Warp: выберите слой" else if (fx) "Warp · FX вкл: точки пишутся в ключи" else "Warp · FX выкл: базовая форма",
            fontSize = 12.sp, maxLines = 1, softWrap = false
        )
        if (isGroup && overlay.points.isEmpty()) OutlinedButton(onClick = vm::enableGroupWarp,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Включить для группы", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        if (isGroup) OutlinedButton(onClick = vm::prepareGroupDeformationAnchor,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Подготовить область", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        if (isGroup) OutlinedButton(onClick = vm::clearGroupDeformationAnchor,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Удалить область", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        OutlinedButton(
            onClick = vm::resetWarp, enabled = overlay.points.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)
        ) { Text("Сбросить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        if (overlay.points.isNotEmpty()) {
            Text("Раздел X", fontSize = 12.sp, maxLines = 1, softWrap = false)
            OutlinedButton(onClick = { vm.changeWarpGrid(true, -1) }, enabled = overlay.width > 2,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("−") }
            Text("${overlay.width - 1}", fontSize = 12.sp)
            OutlinedButton(onClick = { vm.changeWarpGrid(true, 1) }, enabled = overlay.width < 33,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("+") }
            Text("Раздел Y", fontSize = 12.sp, maxLines = 1, softWrap = false)
            OutlinedButton(onClick = { vm.changeWarpGrid(false, -1) }, enabled = overlay.height > 2,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("−") }
            Text("${overlay.height - 1}", fontSize = 12.sp)
            OutlinedButton(onClick = { vm.changeWarpGrid(false, 1) }, enabled = overlay.height < 33,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("+") }
        }
        if (overlay.width > 4 || overlay.height > 4)
            Text("Плотный Warp: статический, FX выкл", fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

/** Инструменты костей и тумблер Bind Pose / Animation (виден только в режиме «Кости»). */
@Composable
private fun BoneToolRow(vm: MainViewModel) {
    val bindPose by vm.bindPose.collectAsStateWithLifecycle()
    val lasso by vm.lassoLayers.collectAsStateWithLifecycle()
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        if (lasso.isNotEmpty()) {
            Button(onClick = vm::bindLassoToBone, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                Text("Привязать (${lasso.size})", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = vm::clearLasso, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                Icon(Icons.Default.Close, "Сбросить", Modifier.size(16.dp))
            }
        }
        OutlinedButton(onClick = vm::autoBind, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Автопривязка", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        OutlinedButton(onClick = vm::detachBone, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
            Text("Без родителя", fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        Text("Animation", fontSize = 12.sp, maxLines = 1, softWrap = false,
            color = if (!bindPose) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Switch(checked = bindPose, onCheckedChange = vm::setBindPose, modifier = Modifier.height(28.dp))
        Text("Bind Pose", fontSize = 12.sp, maxLines = 1, softWrap = false,
            color = if (bindPose) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

// ---------------------------------------------------------------- левая панель инструментов

enum class ToolId(val title: String, val icon: ImageVector) {
    TRANSFORM("Трансформ", Icons.Default.OpenWith),
    SELECTION("Выделение", Icons.Default.HighlightAlt),
    BRUSH("Кисть", Icons.Default.Brush),
    ERASER("Ластик", Icons.Default.Backspace),
    SMUDGE("Смазка", Icons.Default.Gesture),
    FILL("Заливка", Icons.Default.FormatColorFill),
    PIPETTE("Пипетка", Icons.Default.Colorize),
    TEXT("Текст", Icons.Default.TextFields),
    PATH("Путь", Icons.Default.Timeline),
    WARP("Warp", Icons.Default.Transform),
    PERSPECTIVE("Перспектива", Icons.Default.CropFree),
    LIQUIFY("Liquify", Icons.Default.WaterDrop)
}

/** Повторное нажатие этих инструментов открывает настройки кисти. */
private val BRUSH_LIKE = setOf(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE)

/** Один значок на левой панели; обычные инструменты и контекстные (кости, сетка) рисуются одинаково. */
@Composable
private fun RailButton(icon: ImageVector, title: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 3.dp)
            .fillMaxWidth()
            .coachTarget(title)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, title, modifier = Modifier.size(20.dp), tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(title, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
    }
}

@Composable
private fun LeftToolbar(
    selected: String, wide: Boolean, boneMode: Boolean, mesh: Boolean, boneTool: BoneTool, meshTool: MeshTool, vm: MainViewModel,
    onPick: (ToolId) -> Unit
) {
    val stub = LocalStub.current
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(
            Modifier.width(if (wide) 60.dp else 52.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // контекстные инструменты режима
            if (boneMode) {
                RailButton(Icons.Default.AddCircleOutline, "+Кость", boneTool == BoneTool.ADD) { vm.setBoneTool(BoneTool.ADD) }
                RailButton(Icons.Default.OpenWith, "Кости", boneTool == BoneTool.SELECT) { vm.setBoneTool(BoneTool.SELECT) }
                RailButton(Icons.Default.Polyline, "Лассо", boneTool == BoneTool.LASSO) { vm.setBoneTool(BoneTool.LASSO) }
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
            } else if (mesh) {
                RailButton(Icons.Default.PinDrop, "+Пин", meshTool == MeshTool.PIN_ADD) { vm.setMeshTool(MeshTool.PIN_ADD) }
                RailButton(Icons.Default.OpenWith, "Пин", meshTool == MeshTool.PIN_MOVE) { vm.setMeshTool(MeshTool.PIN_MOVE) }
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                RailButton(Icons.Default.ContentCut, "Разрез", meshTool == MeshTool.CUT) { vm.setMeshTool(MeshTool.CUT) }
                RailButton(Icons.Default.Gesture, "Кисть", meshTool == MeshTool.SMUDGE) { vm.setMeshTool(MeshTool.SMUDGE) }
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
            }
            for (t in ToolId.values()) RailButton(t.icon, t.title, t.name == selected && !boneMode && !mesh) { onPick(t) }
        }
    }
}

// ---------------------------------------------------------------- правая панель

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RightPanel(vm: MainViewModel, add: AddActions, color: Long, onColor: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val stub = LocalStub.current
    var tab by rememberSaveable { mutableStateOf(0) }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // свайп вправо по шапке панели (вкладки) закрывает её
            TabRow(
                selectedTabIndex = tab,
                modifier = Modifier.height(40.dp).pointerInput(Unit) {
                    var sum = 0f
                    detectHorizontalDragGestures(onDragStart = { sum = 0f }, onDragEnd = { if (sum > 80f) onClose() }) { _, d -> sum += d }
                }
            ) {
                Tab(modifier = Modifier.coachTarget("Слои"), selected = tab == 0, onClick = { tab = 0 }, text = { Text("Слои", fontSize = 13.sp) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Свойства", fontSize = 13.sp) })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) LayersTab(vm, add)
                else Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    val selId by vm.selected.collectAsStateWithLifecycle()
                    val boneList by vm.bones.collectAsStateWithLifecycle()
                    val bone = boneList.firstOrNull { it.id == selId }
                    if (bone != null) BoneInspector(vm, bone) else InspectorPanel(vm)
                }
            }
            HorizontalDivider()
            // быстрый доступ: цвет, слои, корзина, снять выделение, Undo/Redo
            val selection by vm.selection.collectAsStateWithLifecycle()
            val canUndo by vm.canUndo.collectAsStateWithLifecycle()
            val canRedo by vm.canRedo.collectAsStateWithLifecycle()
            val playing by vm.playing.collectAsStateWithLifecycle()
            val deleteDrawing = com.vibetoon.ui.theme.LocalAppSettings.current?.quickDeleteDrawing == true
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(color.toInt()))
                        .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), CircleShape)
                        .clickable { onColor() }
                )
                IconButton(onClick = { tab = 0 }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Layers, "Слои", tint = if (tab == 0) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current) }
                IconButton(onClick = { vm.undo() }, enabled = canUndo && !playing, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Undo, "Отменить") }
                IconButton(onClick = { vm.redo() }, enabled = canRedo && !playing, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Redo, "Повторить") }
                IconButton(onClick = { vm.quickDelete(deleteDrawing) }, enabled = selection.isNotEmpty() && !playing, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Удалить") }
                IconButton(onClick = { vm.selectLayer(null) }, enabled = selection.isNotEmpty(), modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Deselect, "Снять выделение") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayersTab(vm: MainViewModel, add: AddActions) {
    val stub = LocalStub.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val lprops by vm.layerPropsState.collectAsStateWithLifecycle()
    val bonesList by vm.bones.collectAsStateWithLifecycle()
    var multi by remember { mutableStateOf(false) }   // режим галочек: выбрать несколько слоёв и привязать к кости за одно действие
    var boneMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var renameFor by remember { mutableStateOf<Int?>(null) }
    var symbolModeFor by remember { mutableStateOf<Int?>(null) }
    symbolModeFor?.let { sid ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { symbolModeFor = null },
            title = { Text("Создать символ") },
            text = { Text("Слои как кадры: каждый слой станет отдельным кадром символа, а на кадре появится маркер с именем слоя - удобно для форм рта и анимации по рисункам.\n\nСлои стопкой: все слои остаются вместе и видны одновременно (один составной объект).") },
            confirmButton = { androidx.compose.material3.TextButton({ symbolModeFor = null; vm.createSymbol(sid, asFrames = true) }) { Text("Слои как кадры") } },
            dismissButton = { androidx.compose.material3.TextButton({ symbolModeFor = null; vm.createSymbol(sid, asFrames = false) }) { Text("Слои стопкой") } }
        )
    }
    // Drag & Drop: долгое нажатие на строку и перетаскивание на группу (внутрь) или на слой (рядом с ним)
    var dragId by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    var dragStartIdx by remember { mutableStateOf(0) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
    val targetIdx = if (dragId == null) -1 else dragStartIdx + Math.round(dragDy / rowPx)
    renameFor?.let { rid ->
        var nm by remember(rid) { mutableStateOf(vm.layerNameOrNull(rid) ?: "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Имя слоя") },
            text = { androidx.compose.material3.OutlinedTextField(value = nm, onValueChange = { nm = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ vm.renameLayer(rid, nm); renameFor = null }) { Text("Готово") } },
            dismissButton = { TextButton({ renameFor = null }) { Text("Отмена") } }
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Слоёв: ${rows.size}", fontSize = 12.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            IconButton(onClick = { multi = !multi }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Checklist, "Мультивыбор", tint = if (multi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            Box {
                IconButton(onClick = { addMenu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.AddCircle, "Добавить слой") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Пиксельный") }, onClick = { addMenu = false; vm.addPaintLayer() })
                    DropdownMenuItem(text = { Text("Анимационный (покадровый)") }, onClick = { addMenu = false; vm.addAnimationLayer() })
                    DropdownMenuItem(text = { Text("Векторный") }, onClick = { addMenu = false; vm.addVectorLayer() })
                    DropdownMenuItem(text = { Text("Группа") }, onClick = { addMenu = false; vm.addEmptyGroup() })
                    DropdownMenuItem(text = { Text("Фон") }, onClick = { addMenu = false; vm.addBackgroundLayer() })
                    DropdownMenuItem(text = { Text("Аудио") }, onClick = { addMenu = false; add.audio() })
                    DropdownMenuItem(text = { Text("Видео") }, onClick = { addMenu = false; add.video() })
                    DropdownMenuItem(text = { Text("Изображение") }, onClick = { addMenu = false; add.image() })
                    DropdownMenuItem(text = { Text("Текст") }, onClick = { addMenu = false; add.text() })
                    DropdownMenuItem(text = { Text("Камера (Pan & Zoom)") }, onClick = { addMenu = false; vm.addCameraLayer() })
                    DropdownMenuItem(text = { Text("Transform Layer (null)") }, onClick = { addMenu = false; vm.addNullLayer() })
                }
            }
        }
        if (multi && selection.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.deleteSelected() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                com.vibetoon.ui.IconText(Icons.Default.Delete, "Удалить выбранные (${selection.size})", 11.sp)
            }
            OutlinedButton(onClick = { vm.groupSelected() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                com.vibetoon.ui.IconText(Icons.Default.Folder, "В группу", 11.sp)
            }
            OutlinedButton(onClick = { vm.mergeSelected() }, enabled = selection.size >= 2, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                com.vibetoon.ui.IconText(Icons.Default.MergeType, "Слить вместе", 11.sp)
            }
            Box {
                OutlinedButton(onClick = { boneMenu = true }, enabled = bonesList.isNotEmpty(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                    com.vibetoon.ui.DropText("Привязать к кости (${selection.size})", 11.sp)
                }
                DropdownMenu(expanded = boneMenu, onDismissRequest = { boneMenu = false }) {
                    for (b in bonesList) DropdownMenuItem(text = { Text("Кость: ${com.vibetoon.ui.i18n.boneDisplayName(b.name)}") }, onClick = { boneMenu = false; vm.setParent(b.id) })
                }
            }
            TextButton(onClick = { vm.setParent(null) }) { Text("Отвязать", fontSize = 11.sp) }
            if (bonesList.isEmpty()) Text("Костей нет: создайте кость инструментом «Кости»", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (rows.isEmpty()) Text(
                "Слоёв пока нет. Нажмите «+» вверху, чтобы добавить PSD, картинку, видео или текст.",
                fontSize = 12.sp, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            for ((rowIdx, r) in rows.withIndex()) {
                val l = r.layer
                val sel = l.id in selection
                val isDrag = dragId == l.id
                val isTarget = dragId != null && rowIdx == targetIdx && !isDrag
                val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    when (v) {
                        androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> menuFor = l.id   // влево: Lock / Duplicate / Delete
                        androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> vm.toggleSelect(l.id)   // вправо: мультивыбор
                        else -> {}
                    }
                    false
                })
                androidx.compose.material3.SwipeToDismissBox(
                    state = swipe,
                    backgroundContent = {
                        Row(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Checklist, "Мультивыбор")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Lock, "Замок"); Icon(Icons.Default.ContentCopy, "Дублировать"); Icon(Icons.Default.Delete, "Удалить")
                            }
                        }
                    }
                ) {
                  Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .height(40.dp)
                            .graphicsLayer { translationY = if (isDrag) dragDy else 0f; alpha = if (isDrag) 0.6f else 1f }
                            .background(
                                when {
                                    isTarget && r.isGroup -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                                    isTarget -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                    sel -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else -> androidx.compose.ui.graphics.Color.Transparent
                                }
                            )
                            .combinedClickable(
                                onClick = { if (multi) vm.toggleSelect(l.id) else if (r.isGroup) vm.toggleGroup(l.id) else vm.selectLayer(l.id) },
                                onLongClick = { menuFor = l.id }   // долгое нажатие: только контекстное меню; порядок меняется за ручку
                            )
                            .padding(start = (4 + 10 * r.depth).dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (multi) Icon(if (sel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, "Выбрать", tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(end = 4.dp).size(24.dp).clickable { vm.toggleSelect(l.id) })
                        Icon(
                            if (lprops[l.id]?.visible == false) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Видимость",
                            modifier = Modifier.size(20.dp).clickable { vm.toggleVisible(l.id) }
                        )
                        Box(
                            Modifier.padding(horizontal = 6.dp).size(26.dp).clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            val type = vm.layerType(l.id)
                            val thumb = vm.thumbs[l.id]
                            if (!r.isGroup) androidx.compose.runtime.LaunchedEffect(l.id, thumb) {
                                var tries = 0
                                while (vm.thumbs[l.id] == null && tries < 40) { vm.requestThumb(l.id); kotlinx.coroutines.delay(800); tries++ }
                            }
                            if (thumb != null) androidx.compose.foundation.Image(
                                thumb, type, modifier = Modifier.fillMaxSize().thumbnailChecker(com.vibetoon.ui.theme.LocalAppSettings.current?.checkerThumbnails == true), contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            ) else Icon(
                                when (type) {
                                    "Текст" -> Icons.Default.TextFields; "Картинка" -> Icons.Default.Image; "Видео" -> Icons.Default.Videocam
                                    "Группа" -> Icons.Default.Folder; else -> Icons.Default.Layers
                                }, type, modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (r.isGroup) Icon(if (r.expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(14.dp))
                                Text(l.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val lp = lprops[l.id]
                            val extra = if (lp == null) "" else buildString {
                                if (lp.blend != "norm") append(" · ${LayerBlend.title(lp.blend)}")
                                if (lp.opacity < 0.995f) append(" · ${(lp.opacity * 100).toInt()}%")
                                if (lp.alphaLock) append(" · Alpha Lock")
                                if (lp.clip) append(" · Clip")
                            }
                            Text(vm.layerType(l.id) + extra, fontSize = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        Box(
                            Modifier.width(36.dp).fillMaxHeight().pointerInput(l.id) {
                                // Ручка забирает нажатие сразу (consume): родительский combinedClickable не успевает открыть контекстное меню,
                                // перетаскивание Z-порядка начинается с первого касания и работает и без движения до долгого нажатия
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    dragId = l.id; dragDy = 0f; dragStartIdx = rows.indexOfFirst { it.layer.id == l.id }
                                    val lifted = drag(down.id) { ch -> dragDy += ch.positionChange().y; ch.consume() }
                                    val steps = Math.round(dragDy / rowPx)
                                    if (lifted && steps != 0) vm.dropLayer(l.id, rows.getOrNull(dragStartIdx + steps)?.layer?.id)
                                    dragId = null; dragDy = 0f
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) { Text("≡", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) }
                        Icon(
                            if (lprops[l.id]?.locked == true) Icons.Default.Lock else Icons.Default.LockOpen, "Замок",
                            modifier = Modifier.size(18.dp).clickable { vm.toggleLocked(l.id) }
                        )
                    }
                    DropdownMenu(expanded = menuFor == l.id, onDismissRequest = { menuFor = null }) {
                        for (title in listOf(
                            "Выбрать несколько", "Переименовать", "Заблокировать / разблокировать", "Копировать", "Вставить выше",
                            "Дублировать", "Удалить", "Очистить", "Маска вкл/выкл",
                            "Растрировать", "Слить вниз", "Слить все видимые", "Создать символ"
                        ) + (if (vm.isSymbolInstance(l.id)) listOf("Редактировать символ", "Выбрать маркер (фонему)") else emptyList())) {
                            DropdownMenuItem(text = { Text(title) }, onClick = {
                                menuFor = null
                                when (title) {
                                    "Выбрать несколько" -> { multi = true; if (l.id !in selection) vm.toggleSelect(l.id) }
                                    "Переименовать" -> renameFor = l.id
                                    "Заблокировать / разблокировать" -> vm.toggleLocked(l.id)
                                    "Копировать" -> vm.copyLayer(l.id)
                                    "Вставить выше" -> vm.pasteAbove(l.id)
                                    "Дублировать" -> { vm.selectLayer(l.id); vm.duplicateSelected() }
                                    "Удалить" -> { vm.selectLayer(l.id); vm.deleteSelected() }
                                    "Очистить" -> vm.clearLayer(l.id)
                                    "Маска вкл/выкл" -> vm.toggleClipMask(l.id)
                                    "Растрировать" -> vm.rasterizeLayer(l.id)
                                    "Слить вниз" -> vm.mergeDown(l.id)
                                    "Слить все видимые" -> vm.mergeVisible()
                                    "Создать символ" -> if (selection.size >= 2 && l.id in selection) symbolModeFor = l.id else vm.createSymbol(l.id)
                                    "Редактировать символ" -> vm.enterSymbol(l.id)
                                    "Выбрать маркер (фонему)" -> vm.openMarkerPanel(l.id)
                                    else -> stub(title)
                                }
                            })
                        }
                    }
                  }
                }
            }
        }
        val single = selection.singleOrNull()
        val selRow = rows.firstOrNull { it.layer.id == single }
        if (single != null && selRow != null) {
            HorizontalDivider()
            LayerPropsPanel(vm, single, selRow.isGroup, lprops)
        }
    }
}

/**
 * Свойства выбранного слоя: Opacity, Blend Mode, Alpha Lock (кисть рисует только по непрозрачным пикселям)
 * и Clipping Mask (слой обтравливается по альфе слоя под ним). Alpha Lock и обтравка - только у слоёв, не у групп.
 */
@Composable
private fun LayerPropsPanel(vm: MainViewModel, id: Int, isGroup: Boolean, all: Map<Int, com.vibetoon.domain.model.LayerProps>) {
    val p = all[id] ?: vm.layerPropsOf(id)
    var blendMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Непр.", fontSize = 11.sp, modifier = Modifier.width(38.dp))
            androidx.compose.material3.Slider(
                value = p.opacity, onValueChange = { v -> vm.updateLayerProps(id) { it.copy(opacity = v) } },
                valueRange = 0f..1f, modifier = Modifier.weight(1f).height(28.dp)
            )
            Text("${(p.opacity * 100).toInt()}%", fontSize = 11.sp, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                OutlinedButton(onClick = { blendMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                    Text(LayerBlend.title(p.blend), fontSize = 11.sp)
                }
                DropdownMenu(expanded = blendMenu, onDismissRequest = { blendMenu = false }) {
                    for ((key, title) in LayerBlend.modes)
                        DropdownMenuItem(text = { Text(title) }, onClick = { blendMenu = false; vm.updateLayerProps(id) { it.copy(blend = key) } })
                }
            }
            if (!isGroup) {
                FilterChip(
                    selected = p.alphaLock, onClick = { vm.updateLayerProps(id) { it.copy(alphaLock = !it.alphaLock) } },
                    label = { Text("Alpha", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.GridOn, "Замок прозрачности", modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(30.dp)
                )
                FilterChip(
                    selected = p.clip, onClick = { vm.updateLayerProps(id) { it.copy(clip = !it.clip) } },
                    label = { Text("Clip", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.SubdirectoryArrowRight, "Обтравочная маска", modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(30.dp)
                )
            }
        }
    }
}

/** Инспектор кости; общий скролл Properties ограничивает высоту всего содержимого. */
@Composable
private fun BoneInspector(vm: MainViewModel, bone: com.vibetoon.domain.project.Bone) {
    val links by vm.links.collectAsStateWithLifecycle()
    val bones by vm.bones.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val props by vm.layerPropsState.collectAsStateWithLifecycle()
    val audio by vm.audioTracks.collectAsStateWithLifecycle()
    val parent = links[bone.id]
    val parentBone = bones.firstOrNull { it.id == parent }
    val parentName = if (parent == null) com.vibetoon.ui.i18n.L("нет (корневая)")
        else if (parentBone != null) com.vibetoon.ui.i18n.boneDisplayName(parentBone.name)
        else com.vibetoon.ui.i18n.layerDisplayName(vm.layerNameOrNull(parent) ?: "#$parent", props[parent]?.name != null)
    val bound = remember(bone.id, links, rows, props, audio) { vm.boundLayers(bone.id, links) }
    val checker = com.vibetoon.ui.theme.LocalAppSettings.current?.checkerThumbnails == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Кость: ${com.vibetoon.ui.i18n.boneDisplayName(bone.name)}", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text("Длина: ${bone.length.toInt()} px", fontSize = 12.sp)
        Text("Родитель: $parentName", fontSize = 12.sp)
        Text("Привязано слоёв: ${bound.size}", fontSize = 12.sp)
        if (bound.isNotEmpty()) androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)
        ) {
            items(count = bound.size, key = { bound[it].id }) { index ->
            val layer = bound[index]
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .thumbnailChecker(checker), contentAlignment = Alignment.Center) {
                    val thumb = vm.thumbs[layer.id]
                    if (!layer.isGroup) androidx.compose.runtime.LaunchedEffect(layer.id, thumb) {
                        var tries = 0
                        while (vm.thumbs[layer.id] == null && tries < 40) { vm.requestThumb(layer.id); kotlinx.coroutines.delay(800); tries++ }
                    }
                    if (thumb != null) androidx.compose.foundation.Image(thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    else Icon(if (layer.isGroup) Icons.Default.Folder else Icons.Default.Layers, null, modifier = Modifier.size(16.dp))
                }
                // Переводим только известные подписи по умолчанию, переименованные слои не трогаем.
                androidx.compose.material3.Text(com.vibetoon.ui.i18n.layerDisplayName(layer.name, props[layer.id]?.name != null), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Независимый поворот", fontSize = 14.sp)
                Text("Кость не наследует вращение родителя, но наследует позицию", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Switch(checked = bone.independentRot, onCheckedChange = { vm.setBoneIndependent(bone.id, it) })
        }
    }
}

/** Библиотека ассетов: картинки слоёв проекта и файлы удалённых слоёв (миниатюры); тап вставляет как новый слой. Кэш скрыт. */
@Composable
private fun AssetLibraryDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val assets = remember { vm.assetLibrary() }
    val hidden = remember { vm.assetServiceCount() }
    var showUnused by remember { mutableStateOf(false) }
    val inProject = assets.filter { it.kind == com.vibetoon.domain.project.AssetCatalog.Kind.IN_PROJECT }
    val unused = assets.filter { it.kind == com.vibetoon.domain.project.AssetCatalog.Kind.UNUSED }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Библиотека ассетов") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (assets.isEmpty()) item { Text("Пока пусто: импортированные картинки и рисунки слоёв появятся здесь") }
                if (inProject.isNotEmpty()) item { Text("В проекте", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                items(inProject.chunked(3).size) { i -> val row = inProject.chunked(3)[i]; AssetRow(vm, row) { vm.insertAsset(it); onDismiss() } }
                if (unused.isNotEmpty()) item {
                    androidx.compose.material3.TextButton(onClick = { showUnused = !showUnused }) {
                        Text(if (showUnused) "Скрыть файлы удалённых слоёв (${unused.size})" else "Файлы удалённых слоёв (${unused.size})", fontSize = 12.sp)
                    }
                }
                if (showUnused) items(unused.chunked(3).size) { i -> val row = unused.chunked(3)[i]; AssetRow(vm, row) { vm.insertAsset(it); onDismiss() } }
                if (hidden > 0) item { Text("Скрыто служебных файлов (кэш миниатюр, Liquify, кадры анимации): $hidden", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
private fun AssetRow(vm: MainViewModel, row: List<com.vibetoon.domain.project.AssetCatalog.Entry>, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (e in row) {
            val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, e.rel) { value = vm.assetThumb(e.rel) }
            Column(
                Modifier.width(84.dp).clip(RoundedCornerShape(8.dp)).clickable { onPick(e.rel) }.padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(76.dp).background(androidx.compose.ui.graphics.Color(0xFFEFEFEF), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    val t = thumb
                    if (t != null) androidx.compose.foundation.Image(t, e.title, Modifier.size(72.dp)) else Text("…", fontSize = 12.sp)
                }
                Text(e.title, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
