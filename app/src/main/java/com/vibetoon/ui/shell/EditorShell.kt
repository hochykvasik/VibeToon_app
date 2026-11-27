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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.onSizeChanged
import com.vibetoon.ui.dragEdgeAutoScroll
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
import com.vibetoon.ui.i18n.L
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
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt

/** Заглушка кнопки, у которой ещё нет логики: показывает Snackbar "<что> — в разработке". */
val LocalStub = staticCompositionLocalOf<(String) -> Unit> { {} }

/** true на телефоне: короткая сторона окна < 600 dp (спринт 60, EditorLayout; раньше - ширина < 800 dp). */
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
    var full by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }   // спринт 60: переживает поворот и пересоздание
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
    // Пакет 12: повторный тап по активному инструменту прячет/показывает его панель настроек (у кисти и ластика - открывает набор кистей)
    var toolRowHidden by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
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
    val boneSelectNow by vm.boneSelectMode.collectAsStateWithLifecycle()
    val toolTitle = when {
        boneMode -> when (boneToolNow) {
            BoneTool.ADD -> "Кости: добавить кость"
            BoneTool.LASSO -> if (boneSelectNow == com.vibetoon.domain.input.BoneSelectMode.POINT) "Кости: точечный выбор" else "Кости: лассо"
            else -> "Кости: двигать кости"
        }
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
            // спринт 49: без клавиатуры (safeDrawing включал её - холст и панели схлопывались при каждом вводе числа)
            BoxWithConstraints(Modifier.padding(pad).fillMaxSize().windowInsetsPadding(WindowInsets.editorSafe.only(WindowInsetsSides.Vertical))) {
                // Спринт 60: класс окна - по короткой стороне и ориентации (EditorLayout), а не по ширине: телефон в ландшафте
                // больше не получает планшетную раскладку, в портрете инструменты - строкой под холстом
                val leftHanded = com.vibetoon.ui.theme.LocalAppSettings.current?.leftHanded == true
                val spec = remember(maxWidth, maxHeight, leftHanded) { com.vibetoon.domain.ui.EditorLayout.of(maxWidth.value, maxHeight.value, leftHanded) }
                CompositionLocalProvider(LocalPhone provides spec.phone) {
                val wide = spec.wide
                val phone = spec.phone
                // на телефоне правая панель по умолчанию закрыта: холст получает всю ширину
                androidx.compose.runtime.LaunchedEffect(phone) { if (phone) rightOpen = false }
                // Режим левши: Row ниже раскладывается справа налево, правая панель оказывается у левого края
                // Спринт 57: правая панель планшета - выезжающий слой поверх края (SidePanelSlide): место в строке занято
                // только у полностью открытой панели, холст отодвигается по тому же ходу - без серого поля и скачка таймлайна
                val sideOpen = rightOpen && !full && !phone
                val sideAtRight = com.vibetoon.domain.settings.SidePanelEdge.atRightEdge(leftHanded)
                val sideAtRightNow by androidx.compose.runtime.rememberUpdatedState(sideAtRight)
                val sideP = remember { androidx.compose.animation.core.Animatable(if (sideOpen) 1f else 0f) }
                val sideWidthGuess = with(androidx.compose.ui.platform.LocalDensity.current) { (if (wide) 320.dp else 236.dp).roundToPx() }
                val sidePx = remember { androidx.compose.runtime.mutableIntStateOf(sideWidthGuess) }
                val rowBounds = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
                // чтение хода - только в производных состояниях и в фазе раскладки: EditorShell не перекомпонуется на каждом кадре
                val sideShown by remember { androidx.compose.runtime.derivedStateOf { sideP.value > 0.001f } }
                val sideSettled by remember { androidx.compose.runtime.derivedStateOf { sideP.value >= 0.999f } }
                // Спринт 42: GL-поверхность на всё окно редактора, размер не зависит от панелей. Холст вписывается в видимую
                // область между панелями (slotBounds); область меняется плавно (пружина без отскока), поэтому выезд/перетаскивание
                // таймлайна и правой панели - только равномерный масштаб и сдвиг холста, без сплющивания и скачков.
                val surfOrigin = remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                val slotBounds = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
                val tlDragging = remember { mutableStateOf(false) }
                val viewRect = remember { androidx.compose.animation.core.Animatable(androidx.compose.ui.geometry.Rect.Zero, androidx.compose.ui.geometry.Rect.VectorConverter) }
                val motionOn = com.vibetoon.ui.motion.LocalMotion.current.enabled
                androidx.compose.runtime.LaunchedEffect(sideOpen, motionOn) {
                    val target = if (sideOpen) 1f else 0f
                    if (!motionOn) sideP.snapTo(target) else sideP.animateTo(target, com.vibetoon.ui.motion.Motions.sidePanelSpec(opening = sideOpen))
                }
                androidx.compose.runtime.LaunchedEffect(motionOn) {
                    androidx.compose.runtime.snapshotFlow {
                        // спринт 57: холст не заходит под выезжающую правую панель
                        val b = slotBounds.value; val row = rowBounds.value
                        val (l, r) = com.vibetoon.domain.settings.SidePanelSlide.canvasSpan(b.left, b.right, row.left, row.right, sideP.value, sidePx.intValue, sideAtRightNow)
                        androidx.compose.ui.geometry.Rect(l, b.top, r, b.bottom).translate(-surfOrigin.value)
                    }.collectLatest { target ->
                        if (target.width < 1f || target.height < 1f) return@collectLatest
                        // первый размер и выключенные анимации («Плавные анимации интерфейса» / системный масштаб 0) - сразу
                        // спринт 57: пока едет правая панель, край холста идёт за ней кадр в кадр (тот же ход, без отставания пружины)
                        if (viewRect.value.width < 1f || !motionOn || sideP.isRunning) viewRect.snapTo(target)
                        else viewRect.animateTo(target, androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                            stiffness = com.vibetoon.domain.settings.UiMotion.CANVAS_FIT_STIFFNESS))
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    androidx.compose.runtime.snapshotFlow { Triple(viewRect.value, viewRect.isRunning || sideP.isRunning, tlDragging.value) }.collect { (r, running, dragging) ->
                        // спринт 57: выезд панели - тоже «движение»: рендерер рисует облегчённо и один точный кадр в конце
                        if (r.width >= 1f && r.height >= 1f) com.vibetoon.render.RenderHost.setViewRect(r.left, r.top, r.width, r.height, running || dragging)
                    }
                }
                // поверхность - самый нижний слой: всё, что рисуется после неё (панели), лежит поверх
                com.vibetoon.ui.EditorSurface(vm, Modifier.fillMaxSize().onGloballyPositioned { surfOrigin.value = it.positionInRoot() })
                // слой жестов и наложений ровно над видимой областью холста (те же координаты, что у рендерера)
                EditorCanvas(vm, Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(viewRect.value.left.roundToInt(), viewRect.value.top.roundToInt()) }
                    .layout { m, _ ->
                        val w = viewRect.value.width.roundToInt().coerceAtLeast(0)
                        val h = viewRect.value.height.roundToInt().coerceAtLeast(0)
                        val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                        layout(w, h) { pl.place(0, 0) }
                    })
                // Спринт 42: зона свайпа и стрелка у того края, где живёт правая панель (у левши - у левого)
                val edgeAlign = if (com.vibetoon.domain.settings.SidePanelEdge.atRightEdge(leftHanded)) Alignment.CenterEnd else Alignment.CenterStart
                val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { com.vibetoon.domain.settings.UiMotion.EDGE_SWIPE_DP.dp.toPx() }
                if (com.vibetoon.domain.settings.SidePanelEdge.showClosedHint(rightOpen, full)) Box(
                    Modifier.align(edgeAlign).zIndex(10f).width(if (phone) 28.dp else 36.dp).height(200.dp)   // поверх холста; зона исключена из системного жеста «Назад» (макс. 200 dp высоты)
                        .systemGestureExclusion()
                        .pointerInput(leftHanded, swipePx) {
                            var sum = 0f
                            // панель начинает выезжать, как только палец ушёл от края на 24 dp - не дожидаясь отпускания
                            detectHorizontalDragGestures(onDragStart = { sum = 0f }) { c, d ->
                                c.consume(); sum += d
                                if (!rightOpen && com.vibetoon.domain.settings.SidePanelEdge.opens(sum, swipePx, leftHanded)) rightOpen = true
                            }
                        },
                    contentAlignment = edgeAlign
                ) {
                    val hintShown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                    androidx.compose.animation.AnimatedVisibility(
                        visibleState = hintShown,
                        enter = com.vibetoon.ui.motion.Motions.hintEnter(),
                        exit = com.vibetoon.ui.motion.Motions.hintExit()
                    ) {
                        EdgeHandle(open = false, leftHanded = leftHanded, phone = phone) { rightOpen = true }
                    }
                }
                val tallScreen = !spec.modesInTopBar
                val maxHdp = maxHeight.value
                // спринт 60: высота таймлайна по классу окна; холсту всегда остаётся не меньше 30 % высоты (EditorLayout)
                val timelineH = com.vibetoon.domain.ui.EditorLayout.timelineHeight(spec, maxHdp, userTimelineDp).dp
                val tlRange = com.vibetoon.domain.ui.EditorLayout.timelineDragRange(spec)
                // выбор инструмента - один для колонки у края и для строки под холстом (телефон в портрете)
                val pickTool: (ToolId) -> Unit = pick@{ t ->
                    val again = tool == t.name && !boneMode && !mesh
                    if (again && t != ToolId.TEXT) {
                        if (t == ToolId.BRUSH || t == ToolId.ERASER) { toolRowHidden = false; vm.toggleBrushMenu() }
                        else if (t in TOOLS_WITH_ROW) {
                            toolRowHidden = !toolRowHidden
                            vm.showMessage(if (toolRowHidden) "Панель инструмента скрыта: тап по нему ещё раз вернёт её" else "Панель инструмента показана")
                        }
                        return@pick
                    }
                    toolRowHidden = false
                    when (t) {
                        ToolId.TEXT -> add.text()
                        ToolId.TRANSFORM, ToolId.WARP, ToolId.PERSPECTIVE, ToolId.LIQUIFY, ToolId.BRUSH, ToolId.ERASER,
                        ToolId.FILL, ToolId.PIPETTE, ToolId.SELECTION, ToolId.SMUDGE, ToolId.PATH -> tool = t.name
                        else -> {
                            tool = t.name
                            if (again && t in BRUSH_LIKE) stub("Настройки инструмента «${t.title}»") else stub("Инструмент «${t.title}»")
                        }
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    // Спринт 31: при входе/выходе из полного экрана панели уезжают и возвращаются плавно (только сдвиг и прозрачность)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !full,
                        enter = com.vibetoon.ui.motion.Motions.barEnter(fromTop = true),
                        exit = com.vibetoon.ui.motion.Motions.barExit(toTop = true)
                    ) {
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).opaqueToCanvas()) {   // спринт 42: непрозрачно поверх GL-поверхности
                            TopBar(
                                vm, wide, spec.compactHeader, onBack, add, onExport,
                                rightOpen = rightOpen, onToggleRight = { rightOpen = !rightOpen },
                                onSettings = { showSettings = true }, onFullscreen = ::toggleFullscreen,
                                modes = !tallScreen,   // на низком экране режимы живут в верхней строке
                                mesh = mesh, onMesh = { mesh = it }
                            )
                            if (tallScreen) ModeBar(vm, mesh) { mesh = it }
                        }
                    }
                    // Режим левши: Row раскладывается справа налево (панель инструментов справа, слои слева), содержимое каждой части остаётся обычным
                    Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { c -> rowBounds.value = androidx.compose.ui.geometry.Rect(c.positionInRoot(), c.size.toSize()) }) {
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides (if (leftHanded) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr)) {
                    Row(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) { if (!spec.toolbarBottom) androidx.compose.animation.AnimatedVisibility(
                            visible = !full,
                            enter = com.vibetoon.ui.motion.Motions.panelEnter(fromEnd = leftHanded),
                            exit = com.vibetoon.ui.motion.Motions.panelExit(toEnd = leftHanded)
                        ) { Box(Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)).opaqueToCanvas().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))) { LeftToolbar(
                            tool, spec.railDp, boneMode, mesh, boneToolNow, meshToolNow, vm,
                            onPick = pickTool
                        ) } } }
                        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                // спринт 42: видимая область холста (сам холст и слой жестов - ниже по z, на всё окно)
                                Box(Modifier.matchParentSize().onGloballyPositioned { c ->
                                    slotBounds.value = androidx.compose.ui.geometry.Rect(c.positionInRoot(), c.size.toSize())
                                })
                                // спринт 42: язычок «>» у открытой правой панели (только планшет): тап или свайп к краю закрывает её
                                // спринт 57: язычок - когда панель встала (во время выезда край колонки ещё у края экрана)
                                if (com.vibetoon.domain.settings.SidePanelEdge.showOpenTab(phone, rightOpen, full) && sideSettled) Box(
                                    Modifier.align(if (com.vibetoon.domain.settings.SidePanelEdge.atRightEdge(leftHanded)) Alignment.CenterEnd else Alignment.CenterStart)
                                        .pointerInput(leftHanded, swipePx) {
                                            var sum = 0f
                                            detectHorizontalDragGestures(onDragStart = { sum = 0f }) { c, d ->
                                                c.consume(); sum += d
                                                if (rightOpen && com.vibetoon.domain.settings.SidePanelEdge.closes(sum, swipePx, leftHanded)) rightOpen = false
                                            }
                                        }
                                ) { EdgeHandle(open = true, leftHanded = leftHanded, phone = false) { rightOpen = false } }
                                val markerPanel by vm.markerPanel.collectAsStateWithLifecycle()
                                androidx.compose.animation.AnimatedVisibility(visible = markerPanel != null && !full, enter = com.vibetoon.ui.motion.Motions.popEnter(), exit = com.vibetoon.ui.motion.Motions.popExit()) {
                                    val last = remember { mutableStateOf(markerPanel) }
                                    if (markerPanel != null) last.value = markerPanel
                                    last.value?.let { mid -> com.vibetoon.ui.MarkerPanel(vm, mid) { vm.closeMarkerPanel() } }
                                }
                                // индикатор текущего инструмента; спринт 60: подпись сокращается по классу окна, в портрете телефона -
                                // внизу у строки инструментов (сверху живут панели инструментов), никогда не по центру холста
                                if (!full) Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.align(if (spec.toolbarBottom) Alignment.BottomEnd else Alignment.TopEnd).padding(6.dp)
                                ) { Text(com.vibetoon.domain.ui.EditorLayout.shortLabel(com.vibetoon.ui.i18n.L(toolTitle), spec.labelMaxChars), fontSize = if (phone) 11.sp else 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = if (phone) 8.dp else 10.dp, vertical = 2.dp)) }
                                if (full) IconButton(
                                    onClick = ::toggleFullscreen,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(40.dp)
                                        .clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                ) { Icon(Icons.Default.FullscreenExit, com.vibetoon.ui.i18n.L("Выйти из полного экрана")) }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = warpOn && !full && !toolRowHidden, modifier = Modifier.align(Alignment.TopStart),
                                    enter = com.vibetoon.ui.motion.Motions.toolbarEnter(),
                                    exit = com.vibetoon.ui.motion.Motions.toolbarExit()
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { WarpToolRow(vm) }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = liqOn && !full && !toolRowHidden, modifier = Modifier.align(Alignment.TopStart),
                                    enter = com.vibetoon.ui.motion.Motions.toolbarEnter(),
                                    exit = com.vibetoon.ui.motion.Motions.toolbarExit()
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { LiquifyToolRow(vm) }
                                }
                                val brushMenuOpen by vm.brushMenu.collectAsStateWithLifecycle()
                                // спринт 39: файлы кистей - лаунчеры живут вне Popup, чтобы результат выбора файла не терялся
                                val brushesOut = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportBrushesTo(uri) }
                                val brushesIn = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importBrushesFrom(uri) }
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
                                            enter = com.vibetoon.ui.motion.Motions.panelEnter(fromEnd = false)
                                        ) { BrushMenu(vm, onImport = { brushesIn.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, onExport = { brushesOut.launch("vibetoon-brushes.json") }) }
                                    }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = paintOn != null && !full && !boneMode && !mesh && !toolRowHidden, modifier = Modifier.align(Alignment.TopStart),
                                    enter = com.vibetoon.ui.motion.Motions.toolbarEnter(),
                                    exit = com.vibetoon.ui.motion.Motions.toolbarExit()
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { PaintToolRow(vm) { showColor = true } }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = perspOn && !full && !toolRowHidden, modifier = Modifier.align(Alignment.TopStart),
                                    enter = com.vibetoon.ui.motion.Motions.toolbarEnter(),
                                    exit = com.vibetoon.ui.motion.Motions.toolbarExit()
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { PerspToolRow(vm) }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = mesh && !boneMode && !full, modifier = Modifier.align(Alignment.TopStart),
                                    enter = com.vibetoon.ui.motion.Motions.toolbarEnter(),
                                    exit = com.vibetoon.ui.motion.Motions.toolbarExit()
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { MeshToolRow(vm) }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = boneMode && !full, modifier = Modifier.align(Alignment.TopStart),
                                    enter = com.vibetoon.ui.motion.Motions.toolbarEnter(),
                                    exit = com.vibetoon.ui.motion.Motions.toolbarExit()
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(6.dp)) { BoneToolRow(vm) }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = rasterOn != null && !full && !boneMode && !mesh && !toolRowHidden, modifier = Modifier.align(Alignment.TopStart),
                                    enter = com.vibetoon.ui.motion.Motions.toolbarEnter(),
                                    exit = com.vibetoon.ui.motion.Motions.toolbarExit()
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
                                            // спринт 60: на телефоне - короткие подписи, длинное имя символа сокращается
                                            androidx.compose.material3.TextButton(onClick = { vm.exitSymbol() }) { Text(if (phone) "◀" else "◀ На главную сцену", maxLines = 1, softWrap = false) }
                                            Text("Символ: ${com.vibetoon.domain.ui.EditorLayout.shortLabel(vm.symbolName(sid), spec.labelMaxChars)}", fontSize = 13.sp, maxLines = 1, softWrap = false)
                                            androidx.compose.material3.TextButton(onClick = { markerEdit = true }) { Text("Маркеры", maxLines = 1, softWrap = false) }
                                        }
                                    }
                                }
                                Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                                    StatusLine(vm)
                                }
                            }
                            // спринт 60: телефон в портрете - инструменты строкой под холстом (холст получает всю ширину)
                            if (spec.toolbarBottom) androidx.compose.animation.AnimatedVisibility(
                                visible = !full,
                                enter = com.vibetoon.ui.motion.Motions.barEnter(fromTop = false),
                                exit = com.vibetoon.ui.motion.Motions.barExit(toTop = false)
                            ) {
                                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).opaqueToCanvas()) {
                                    // первой - кнопка панели «Слои / свойства / референс» (на телефоне панель выезжает поверх холста и сворачивается)
                                    BottomToolStrip(listOf(RailItem(Icons.Default.Layers, "Слои", rightOpen, dividerAfter = true) { rightOpen = !rightOpen }) +
                                        railItems(tool, boneMode, mesh, boneToolNow, meshToolNow, vm, pickTool), spec)
                                }
                            }
                            // хват на верхней границе таймлайна: тянуть вверх/вниз - меняет высоту панели
                            // Спринт 31: таймлайн уезжает вниз при полном экране и возвращается плавно
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !full,
                                enter = com.vibetoon.ui.motion.Motions.barEnter(fromTop = false),
                                exit = com.vibetoon.ui.motion.Motions.barExit(toTop = false)
                            ) { Column(Modifier.liftAboveKeyboard().fillMaxWidth().background(MaterialTheme.colorScheme.background).opaqueToCanvas()) {   // спринт 42: непрозрачно поверх GL-поверхности; спринт 49: над клавиатурой, пока в нём ввод
                                if (timelineOpen) {
                                    val density = androidx.compose.ui.platform.LocalDensity.current
                                    Box(
                                        Modifier.fillMaxWidth().height(18.dp)
                                            .background(MaterialTheme.colorScheme.surface)
                                            .pointerInput(Unit) {
                                                detectVerticalDragGestures(
                                                    onDragStart = { tlDragging.value = true },
                                                    onDragEnd = { tlDragging.value = false },
                                                    onDragCancel = { tlDragging.value = false }
                                                ) { change, dy ->
                                                    change.consume()
                                                    val cur = if (userTimelineDp > 0f) userTimelineDp else timelineH.value
                                                    userTimelineDp = (cur - dy / density.density).coerceIn(tlRange.start, tlRange.endInclusive)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
                                    }
                                }
                                TimelinePanel(
                                    vm,
                                    modifier = if (timelineOpen) Modifier.height(timelineH) else Modifier,
                                    fill = true, collapsed = !timelineOpen,
                                    onToggleCollapse = { timelineOpen = !timelineOpen }
                                )
                            } }
                        } }
                        // спринт 57: место панели в строке - только у полностью открытой панели (под ней, невидимо)
                        if (sideOpen && sideSettled) {
                            val slotW = with(androidx.compose.ui.platform.LocalDensity.current) { sidePx.intValue.toDp() }
                            androidx.compose.foundation.layout.Spacer(Modifier.width(slotW).fillMaxHeight())
                        }
                    } }
                    // спринт 57: сама панель - слой поверх края строки; сдвиг читается в фазе раскладки (без перекомпоновки)
                    if (sideOpen || sideShown) Box(
                        Modifier.align(if (sideAtRight) androidx.compose.ui.AbsoluteAlignment.CenterRight else androidx.compose.ui.AbsoluteAlignment.CenterLeft)
                            .fillMaxHeight()
                            .offset { androidx.compose.ui.unit.IntOffset(com.vibetoon.domain.settings.SidePanelSlide.offsetPx(sideP.value, sidePx.intValue, sideAtRight), 0) }
                            .onSizeChanged { sz -> if (sz.width > 0) sidePx.intValue = sz.width }
                    ) {
                        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                            Box(Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background).opaqueToCanvas().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))) { RightPanel(
                                vm, add, color, { showColor = true }, { rightOpen = false },
                                Modifier.width(if (wide) 320.dp else 236.dp).fillMaxHeight()
                            ) }
                        }
                    }
                    }
                }
                // телефон: правая панель выезжает поверх холста и закрывается кнопкой в её шапке или в верхней панели
                // тап по холсту рядом с панелью закрывает её
                if (phone && rightOpen && !full) Box(
                    Modifier.fillMaxSize().padding(top = 48.dp).pointerInput(Unit) { detectTapGestures { rightOpen = false } }
                )
                // спринт 42: в режиме левши панель телефона выезжает от левого края (как и на планшете)
                if (phone) androidx.compose.animation.AnimatedVisibility(
                    visible = rightOpen && !full,
                    modifier = Modifier.align(if (leftHanded) Alignment.BottomStart else Alignment.BottomEnd).padding(top = 48.dp),
                    enter = com.vibetoon.ui.motion.Motions.sidePanelEnter(fromEnd = !leftHanded),
                    exit = com.vibetoon.ui.motion.Motions.sidePanelExit(toEnd = !leftHanded)
                ) {
                    Surface(shadowElevation = 12.dp, tonalElevation = 3.dp,
                        shape = if (leftHanded) RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp) else RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)) {
                        RightPanel(
                            vm, add, color, { showColor = true }, { rightOpen = false },
                            Modifier.width(spec.sidePanelDp.dp).fillMaxHeight()   // спринт 60: ширина по классу окна (в ландшафте телефона - не больше 42 %)
                        )
                    }
                }
                }
            }
        }
    }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
    com.vibetoon.ui.timeline.DuplicateAnimationDialog(vm)   // спринт 51: дубль слоя с ключами - с анимацией или без
    if (showColor) com.vibetoon.ui.inspector.ColorPickerDialog(
        initial = color, onColor = { color = it }, onEyedropper = { showColor = false; tool = ToolId.PIPETTE.name; vm.showMessage("Пипетка: коснитесь холста, чтобы взять цвет") }, onDismiss = { showColor = false }
    )
}

/**
 * Спринт 42: панели лежат поверх слоя жестов холста. Пустое место панели (фон) тоже считается попаданием, поэтому касание
 * не проваливается на холст под ней, пока видимая область холста догоняет панель. Ничего не потребляет.
 */
private fun Modifier.opaqueToCanvas(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/**
 * Спринт 42: маленький полупрозрачный язычок со стрелкой у края правой панели. Закрытая панель - «<» у края экрана
 * (тап/свайп открывает), открытая (планшет) - «>» у её внутренней границы (тап/свайп закрывает). В режиме левши всё
 * зеркально. На телефоне язычок меньше, чтобы не загромождать экран.
 */
@Composable
private fun EdgeHandle(open: Boolean, leftHanded: Boolean, phone: Boolean, onClick: () -> Unit) {
    val atRight = com.vibetoon.domain.settings.SidePanelEdge.atRightEdge(leftHanded)
    // закрытая: язычок прижат к краю экрана; открытая: к границе панели с той же стороны - скругление со стороны холста
    val shape = if (atRight) RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp) else RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
    val label = if (open) L("Скрыть панель") else L("Показать панель")
    Box(
        Modifier.width(com.vibetoon.domain.settings.SidePanelEdge.hintWidthDp(phone).dp)
            .height(com.vibetoon.domain.settings.SidePanelEdge.hintHeightDp(phone).dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (phone) 0.45f else 0.55f))
            .clickable(onClickLabel = label) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (com.vibetoon.domain.settings.SidePanelEdge.arrowPointsLeft(open, leftHanded)) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
            null, Modifier.size(if (phone) 12.dp else 14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

// ---------------------------------------------------------------- верхняя панель

/** Рамка находится в том же Popup и layout, что и пункт меню: скролл/поворот не меняет её систему координат. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CoachMenuItem(label: String, highlight: Boolean, onClick: () -> Unit) {
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
        text = { Text(label) }, onClick = onClick,
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
    vm: MainViewModel, wide: Boolean, compact: Boolean, onBack: () -> Unit, add: AddActions, onExport: () -> Unit,
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
    // спринт 57: урок открывает меню «Действия» на шаге с его пунктом и закрывает, когда шаг сменился
    var coachMenu by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(coachTarget) {
        when (com.vibetoon.domain.tutorial.CoachMenuTargets.menuAction(coachTarget, coachMenu)) {
            true -> { actions = true; coachMenu = true }
            false -> { actions = false; coachMenu = false }
            null -> Unit
        }
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
            // спринт 60: компактная шапка (телефон) - библиотека в меню «Ещё», чтобы кнопки не наезжали друг на друга
            if (!compact) BarIcon(Icons.Default.PhotoLibrary, "Библиотека") { showLibrary = true }
            if (showLibrary) AssetLibraryDialog(vm) { showLibrary = false }
            Box {
                BarIcon(Icons.Default.Menu, "Действия") { actions = true }
                DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                    DropdownMenuItem(text = { Text("Экспорт…") }, onClick = { actions = false; onExport() })
                    DropdownMenuItem(text = { Text("Настройки") }, onClick = { actions = false; onSettings() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("PSD-файл") }, onClick = { actions = false; add.psd() })
                    DropdownMenuItem(text = { Text("PSD в текущий проект") }, onClick = { actions = false; add.psdInsert() })
                    DropdownMenuItem(text = { Text("SVG в текущий проект (векторные слои)") }, onClick = { actions = false; add.svgInsert() })
                    DropdownMenuItem(text = { Text("Изображение (PNG, JPG)") }, onClick = { actions = false; add.image() })
                    DropdownMenuItem(text = { Text("Изображение как символ") }, onClick = { actions = false; add.imageAsSymbol() })
                    DropdownMenuItem(text = { Text("Секвенция картинок (покадровый слой)") }, onClick = { actions = false; add.sequence(false) })
                    DropdownMenuItem(text = { Text("Секвенция картинок как символ") }, onClick = { actions = false; add.sequence(true) })
                    DropdownMenuItem(text = { Text("Видео (MP4)") }, onClick = { actions = false; add.video() })
                    DropdownMenuItem(text = { Text("Видео как символ") }, onClick = { actions = false; add.videoAsSymbol() })
                    DropdownMenuItem(text = { Text("Аудио (MP3, WAV)") }, onClick = { actions = false; add.audio() })
                    DropdownMenuItem(text = { Text("Текст") }, onClick = { actions = false; add.text() })
                    DropdownMenuItem(text = { Text("Камера (Pan & Zoom)") }, onClick = { actions = false; vm.addCameraLayer() })
                    // спринт 56: картинка-образец в плавающем окне (в проект и экспорт не попадает)
                    CoachMenuItem("Окно референса…", highlight = coachTarget == "Окно референса…") { actions = false; add.reference() }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Сбросить вид холста") }, onClick = { actions = false; vm.resetCamera() })
                    DropdownMenuItem(text = { Text("Повернуть холст на 90°") }, onClick = { actions = false; vm.rotateCamera90() })
                    DropdownMenuItem(text = { Text("Сбросить поворот холста (0°)") }, onClick = { actions = false; vm.resetCameraRotation() })
                    DropdownMenuItem(text = { Text("Отразить холст по горизонтали") }, onClick = { actions = false; vm.flipCameraX() })
                    DropdownMenuItem(text = { Text("Отразить холст по вертикали") }, onClick = { actions = false; vm.flipCameraY() })
                    CoachMenuItem("Тестовый PSD", highlight = coachTarget == "Тестовый PSD") { actions = false; vm.parseTestPsd() }
                }
            }
            // спринт 59: на низком экране режимы в этой строке; свободное место правее них снимает выделение по тапу
            if (modes) TitleModesRow(vm, name, mesh, onMesh, Modifier.weight(1f))
            else Text(
                name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            if (com.vibetoon.ui.theme.LocalAppSettings.current?.refocusButton == true) {
                Box(Modifier.size(40.dp).combinedClickable(onClick = {}, onDoubleClick = vm::resetCamera), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CropFree, com.vibetoon.ui.i18n.L("Дважды нажмите, чтобы сбросить вид холста"), modifier = Modifier.size(22.dp))
                }
            }
            BarIcon(Icons.Default.AutoAwesome, "FX: вкл — правки пишутся в ключи, выкл — в базовую форму", active = fxOn) { vm.toggleFx() }
            BarIcon(Icons.Default.Undo, "Отменить", enabled = canUndo && !playing) { vm.undo() }
            BarIcon(Icons.Default.Redo, "Повторить", enabled = canRedo && !playing) { vm.redo() }
            if (compact) IconButton(onClick = onExport, modifier = Modifier.size(40.dp).coachTarget("Export")) {
                Icon(Icons.Default.IosShare, com.vibetoon.ui.i18n.L("Экспорт…"), modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            } else Button(onClick = onExport, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(34.dp).coachTarget("Export")) {
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
                        if (compact) DropdownMenuItem(text = { Text("Библиотека") }, onClick = { more = false; showLibrary = true })
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
            icon, com.vibetoon.ui.i18n.L(desc), modifier = Modifier.size(22.dp),
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
        // спринт 59: вся строка правее режимов - зона «тап снимает выделение» (до правого края)
        Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeChips(vm, mesh, onMesh)
            DeselectZone(vm, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * Спринт 59: строка низкого экрана «имя проекта - Объект / Кости / Сетка - свободное место». Режимы не сжимаются,
 * имя сокращается многоточием, а свободному месту (зоне снятия выделения) остаётся не меньше 48 dp.
 */
@Composable
private fun TitleModesRow(vm: MainViewModel, name: String, mesh: Boolean, onMesh: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val minZone = with(androidx.compose.ui.platform.LocalDensity.current) { com.vibetoon.domain.input.TopBarDeselect.MIN_ZONE_DP.dp.roundToPx() }
    androidx.compose.ui.layout.Layout(
        content = {
            Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            ModeChips(vm, mesh, onMesh)
            DeselectZone(vm)
        },
        modifier = modifier.fillMaxHeight()
    ) { ms, c ->
        val h = c.maxHeight
        val chips = ms[1].measure(androidx.compose.ui.unit.Constraints(maxWidth = c.maxWidth, maxHeight = h))
        val titleMax = com.vibetoon.domain.input.TopBarDeselect.titleMax(c.maxWidth, chips.width, minZone)
        val title = ms[0].measure(androidx.compose.ui.unit.Constraints(maxWidth = titleMax, maxHeight = h))
        val zoneW = (c.maxWidth - chips.width - title.width).coerceAtLeast(0)
        val zone = ms[2].measure(androidx.compose.ui.unit.Constraints.fixed(zoneW, h))
        layout(c.maxWidth, h) {
            title.placeRelative(0, (h - title.height) / 2)
            chips.placeRelative(title.width, (h - chips.height) / 2)
            zone.placeRelative(title.width + chips.width, 0)
        }
    }
}

/**
 * Спринт 59: свободное место верхней панели правее режимов. Осознанный тап (один палец, без протяжки и долгого
 * удержания) снимает выделение слоя или кости - как тап в пустоту холста; протяжка, второй палец и долгое нажатие
 * ничего не делают. Кнопки режимов и правые кнопки панели в зону не входят.
 */
@Composable
private fun DeselectZone(vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val label = L("Снять выделение")
    Box(
        modifier
            .semantics { onClick(label) { vm.deselectFromBar() } }
            .pointerInput(vm) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    var pointers = 1
                    var consumed = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        pointers = maxOf(pointers, ev.changes.count { it.pressed })
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.isConsumed) consumed = true
                        if (!c.pressed) {
                            val d = c.position - down.position
                            val tap = com.vibetoon.domain.input.TopBarDeselect.isTap(
                                d.x, d.y, c.uptimeMillis - down.uptimeMillis, viewConfiguration.touchSlop,
                                viewConfiguration.longPressTimeoutMillis, pointers, consumed
                            )
                            if (tap && vm.deselectFromBar()) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            break
                        }
                    }
                }
            }
    )
}

/** Выезжающая панель кистей: список пресетов с превью S-образного мазка; тап выбирает кисть. */
@Composable
private fun BrushMenu(vm: MainViewModel, onImport: () -> Unit, onExport: () -> Unit) {
    val tool by vm.paintTool.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val eraser by vm.eraser.collectAsStateWithLifecycle()
    val own by vm.customBrushes.collectAsStateWithLifecycle()
    val current = if (tool == MainViewModel.PaintTool.ERASER) eraser.presetId else brush.presetId
    val items = remember(tool, own) { vm.presetsForTool() }
    var more by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = Modifier.width(300.dp).heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.78f).dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tool == MainViewModel.PaintTool.ERASER) "Ластики" else "Кисти", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                // спринт 39: свои кисти - сохранить текущую, импорт и экспорт файлом (.json)
                Box {
                    Icon(Icons.Default.MoreVert, com.vibetoon.ui.i18n.L("Свои кисти"), modifier = Modifier.clickable { more = true }.padding(6.dp).size(22.dp))
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("Сохранить текущую как свою") }, leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) }, onClick = { more = false; vm.saveCurrentBrush() })
                        DropdownMenuItem(text = { Text("Импорт кистей…") }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { more = false; onImport() })
                        DropdownMenuItem(text = { Text("Экспорт кистей…") }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { more = false; onExport() })
                    }
                }
                Icon(Icons.Default.Close, com.vibetoon.ui.i18n.L("Закрыть"), modifier = Modifier.clickable { vm.closeBrushMenu() }.padding(6.dp).size(22.dp))
            }
            if (own.isEmpty()) Text("Свои кисти: «⋮» → «Сохранить текущую как свою» или «Импорт кистей…»", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
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
                        Text(pr.title, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        if (vm.isCustomBrush(pr.id)) Icon(Icons.Default.Delete, com.vibetoon.ui.i18n.L("Удалить кисть"), modifier = Modifier.clickable { vm.deleteCustomBrush(pr.id) }.padding(4.dp).size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
    val combine by vm.selCombine.collectAsStateWithLifecycle()
    val transformBox by vm.selTransform.collectAsStateWithLifecycle()
    val transformMode by vm.selTransformMode.collectAsStateWithLifecycle()
    val proportional by vm.selTransformProportional.collectAsStateWithLifecycle()
    var numericOpen by remember { mutableStateOf(false) }
    if (numericOpen) SelectionTransformDialog(onDismiss = { numericOpen = false }, onApply = { n -> numericOpen = false; vm.applySelTransformNumeric(n) })
    // спринт 36: рамка трансформации векторных фигур (наклон / искажение / свободно)
    val vecBox by vm.vecTransform.collectAsStateWithLifecycle()
    var vecNumericOpen by remember { mutableStateOf(false) }
    if (vecNumericOpen) SelectionTransformDialog(onDismiss = { vecNumericOpen = false }, onApply = { n -> vecNumericOpen = false; vm.applyVecTransformNumeric(n) })
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
            RasterTool.SELECT -> if (transformBox != null) {
                // Sprint 33: рамка свободной трансформации
                Text("Трансформ", fontSize = 12.sp, maxLines = 1, softWrap = false)
                for ((m, t) in listOf(com.vibetoon.domain.raster.TransformBox.Mode.FREE to "Свободно", com.vibetoon.domain.raster.TransformBox.Mode.SKEW to "Наклон",
                        com.vibetoon.domain.raster.TransformBox.Mode.DISTORT to "Искажение"))
                    androidx.compose.material3.FilterChip(selected = transformMode == m, onClick = { vm.setSelTransformMode(m) }, label = { Text(t, fontSize = 12.sp, maxLines = 1, softWrap = false) })
                androidx.compose.material3.FilterChip(selected = proportional, onClick = vm::toggleSelTransformProportional, label = { Text("Пропорционально", fontSize = 12.sp, maxLines = 1, softWrap = false) })
                var anchorMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { anchorMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { com.vibetoon.ui.DropText("Опорная точка") }
                    DropdownMenu(expanded = anchorMenu, onDismissRequest = { anchorMenu = false }) {
                        for ((a, t) in listOf(
                            (1 to 1) to "Центр", (0 to 0) to "Левый верхний угол", (1 to 0) to "Верхний край", (2 to 0) to "Правый верхний угол",
                            (0 to 1) to "Левый край", (2 to 1) to "Правый край", (0 to 2) to "Левый нижний угол", (1 to 2) to "Нижний край", (2 to 2) to "Правый нижний угол"
                        )) DropdownMenuItem(text = { Text(t) }, onClick = { anchorMenu = false; vm.setSelTransformAnchor(a.first, a.second) })
                    }
                }
                OutlinedButton(onClick = { numericOpen = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Числа…", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(onClick = vm::applySelectionTransform, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Применить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = vm::cancelSelectionTransform, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Отмена", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            } else {
                for ((m, t) in listOf(SelMode.RECT to "Прямоугольник", SelMode.OVAL to "Овал", SelMode.LASSO to "Лассо", SelMode.WAND to "Палочка"))
                    androidx.compose.material3.FilterChip(selected = mode == m, onClick = { vm.setSelMode(m) }, label = { Text(t, fontSize = 12.sp, maxLines = 1, softWrap = false) })
                if (pendingLasso) OutlinedButton(onClick = vm::cancelPendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Отменить контур", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (mode == SelMode.WAND) com.vibetoon.ui.inspector.ScrubBox("Порог", fp.threshold.toFloat(), 0f, 128f, { v -> vm.setFillParams { it.copy(threshold = v.toInt()) } }, perPx = 0.4f)
                // Sprint 33: как новый контур сочетается с выделением
                var combineMenu by remember { mutableStateOf(false) }
                val combineTitles = listOf(com.vibetoon.domain.raster.SelectionOps.Combine.REPLACE to "Новое выделение", com.vibetoon.domain.raster.SelectionOps.Combine.ADD to "Добавить к выделению",
                    com.vibetoon.domain.raster.SelectionOps.Combine.SUBTRACT to "Вычесть из выделения", com.vibetoon.domain.raster.SelectionOps.Combine.INTERSECT to "Пересечь с выделением")
                Box {
                    OutlinedButton(onClick = { combineMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { com.vibetoon.ui.DropText(combineTitles.first { it.first == combine }.second) }
                    DropdownMenu(expanded = combineMenu, onDismissRequest = { combineMenu = false }) {
                        for ((c, t) in combineTitles) DropdownMenuItem(text = { Text((if (combine == c) "✓ " else "") + com.vibetoon.ui.i18n.L(t)) }, onClick = { combineMenu = false; vm.setSelCombine(c) })
                    }
                }
                OutlinedButton(onClick = vm::selectAllPixels, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Выделить всё", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (has) {
                    OutlinedButton(onClick = vm::beginSelectionTransform, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Трансформ", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.growSelection(2) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Расширить на 2 px", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { vm.growSelection(-2) }, enabled = !pendingLasso, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Сузить на 2 px", fontSize = 12.sp, maxLines = 1, softWrap = false) }
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
            RasterTool.PATH -> if (vecBox != null) {
                Text("Трансформ фигур", fontSize = 12.sp, maxLines = 1, softWrap = false)
                for ((m, t) in listOf(com.vibetoon.domain.raster.TransformBox.Mode.FREE to "Свободно", com.vibetoon.domain.raster.TransformBox.Mode.SKEW to "Наклон",
                        com.vibetoon.domain.raster.TransformBox.Mode.DISTORT to "Искажение"))
                    androidx.compose.material3.FilterChip(selected = transformMode == m, onClick = { vm.setSelTransformMode(m) }, label = { Text(t, fontSize = 12.sp, maxLines = 1, softWrap = false) })
                androidx.compose.material3.FilterChip(selected = proportional, onClick = vm::toggleSelTransformProportional, label = { Text("Пропорционально", fontSize = 12.sp, maxLines = 1, softWrap = false) })
                TransformAnchorMenu { ax, ay -> vm.setVecTransformAnchor(ax, ay) }
                OutlinedButton(onClick = { vecNumericOpen = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Числа…", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(onClick = vm::applyVectorTransform, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Применить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = vm::cancelVectorTransform, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Отмена", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            } else {
                Text("Перо", fontSize = 12.sp, maxLines = 1, softWrap = false)
                androidx.compose.material3.FilterChip(selected = fillOn, onClick = { vm.toggleVecFill() }, label = { Text("Заливка", fontSize = 12.sp, maxLines = 1, softWrap = false) })
                if (pen >= 2) OutlinedButton(onClick = { vm.penFinish(false) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Готово", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen >= 3) OutlinedButton(onClick = { vm.penFinish(true) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Замкнуть", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen >= 1) OutlinedButton(onClick = vm::penUndoPoint, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Убрать точку", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen == 0) OutlinedButton(onClick = vm::restyleSelectedVector, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Цвет и толщина кисти", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen == 0) OutlinedButton(onClick = vm::deleteVectorNode, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Удалить узел", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen == 0) OutlinedButton(onClick = vm::deleteVectorShape, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Удалить примитив", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                // спринт 36: наклон / искажение выбранной фигуры или всех фигур слоя
                if (pen == 0) OutlinedButton(onClick = { vm.beginVectorTransform(false) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Наклон/искажение фигуры", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen == 0) OutlinedButton(onClick = { vm.beginVectorTransform(true) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Все фигуры слоя", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                // пакет 19: узлы Безье контуров пера
                if (pen == 0) for ((a, t) in listOf(0 to "Сгладить узел", 1 to "Острый угол", 3 to "Независимые ручки", 2 to "Сгладить контур"))
                    OutlinedButton(onClick = { vm.bezierVectorNode(a) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text(t, fontSize = 12.sp, maxLines = 1, softWrap = false) }
                // пакет 18: булевы операции выбранной фигуры с фигурой под ней
                if (pen == 0) for ((op, t) in listOf(
                    com.vibetoon.domain.vector.VectorBool.UNION to "Объединить", com.vibetoon.domain.vector.VectorBool.SUBTRACT to "Вычесть",
                    com.vibetoon.domain.vector.VectorBool.INTERSECT to "Пересечь", com.vibetoon.domain.vector.VectorBool.EXCLUDE to "Исключить"
                )) OutlinedButton(onClick = { vm.booleanVector(op) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text(t, fontSize = 12.sp, maxLines = 1, softWrap = false) }
                if (pen == 0) OutlinedButton(onClick = vm::releaseVectorShape, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Разобрать", fontSize = 12.sp, maxLines = 1, softWrap = false) }
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
        // Sprint 33: направляющие рисования и симметрия
        val guides by vm.guides.collectAsStateWithLifecycle()
        val guideEdit by vm.guideEdit.collectAsStateWithLifecycle()
        var guideMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { guideMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                com.vibetoon.ui.DropText("Направляющие")
            }
            androidx.compose.material3.DropdownMenu(expanded = guideMenu, onDismissRequest = { guideMenu = false }) {
                for ((kind, title) in listOf(
                    com.vibetoon.domain.paint.DrawingGuides.Kind.OFF to "Без направляющих",
                    com.vibetoon.domain.paint.DrawingGuides.Kind.ISOMETRIC to "Изометрическая сетка",
                    com.vibetoon.domain.paint.DrawingGuides.Kind.PERSPECTIVE_1 to "Перспектива: 1 точка",
                    com.vibetoon.domain.paint.DrawingGuides.Kind.PERSPECTIVE_2 to "Перспектива: 2 точки",
                    com.vibetoon.domain.paint.DrawingGuides.Kind.PERSPECTIVE_3 to "Перспектива: 3 точки",
                    com.vibetoon.domain.paint.DrawingGuides.Kind.ELLIPSE to "Эллипс и круг"
                )) androidx.compose.material3.DropdownMenuItem(text = { Text((if (guides.kind == kind) "✓ " else "") + com.vibetoon.ui.i18n.L(title)) }, onClick = { guideMenu = false; vm.setGuideKind(kind) })
                HorizontalDivider()
                for ((symmetry, title) in listOf(
                    com.vibetoon.domain.paint.DrawingGuides.Symmetry.OFF to "Без симметрии",
                    com.vibetoon.domain.paint.DrawingGuides.Symmetry.VERTICAL to "Симметрия: вертикальная ось",
                    com.vibetoon.domain.paint.DrawingGuides.Symmetry.HORIZONTAL to "Симметрия: горизонтальная ось",
                    com.vibetoon.domain.paint.DrawingGuides.Symmetry.BOTH to "Симметрия: обе оси",
                    com.vibetoon.domain.paint.DrawingGuides.Symmetry.RADIAL to "Симметрия: радиальная"
                )) androidx.compose.material3.DropdownMenuItem(text = { Text((if (guides.symmetry == symmetry) "✓ " else "") + com.vibetoon.ui.i18n.L(title)) }, onClick = { guideMenu = false; vm.setSymmetry(symmetry) })
            }
        }
        if (guides.active) {
            androidx.compose.material3.FilterChip(selected = guideEdit, onClick = vm::toggleGuideEdit, label = { Text("Править направляющие", fontSize = 12.sp, maxLines = 1, softWrap = false) })
            if (guides.kind != com.vibetoon.domain.paint.DrawingGuides.Kind.OFF) androidx.compose.material3.FilterChip(selected = guides.snap, onClick = vm::toggleGuideSnap, label = { Text("Притяжение", fontSize = 12.sp, maxLines = 1, softWrap = false) })
            if (guides.symmetry == com.vibetoon.domain.paint.DrawingGuides.Symmetry.RADIAL)
                com.vibetoon.ui.inspector.ScrubBox("Лучи", guides.radial.toFloat(), com.vibetoon.domain.paint.DrawingGuides.MIN_RADIAL.toFloat(), com.vibetoon.domain.paint.DrawingGuides.MAX_RADIAL.toFloat(), { v -> vm.setRadialCount(Math.round(v)) }, perPx = 0.05f)
            OutlinedButton(onClick = vm::resetGuides, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { Text("Сбросить направляющие", fontSize = 12.sp, maxLines = 1, softWrap = false) }
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
            // спринт 40: векторные кисти - толщина штриха «Путь» по нажиму/наклону, тушь с сужением, каллиграфия
            if (vk == com.vibetoon.domain.vector.VecKind.PATH) {
                val vb by vm.vecBrush.collectAsStateWithLifecycle()
                for (style in com.vibetoon.domain.vector.VectorBrush.Style.values())
                    androidx.compose.material3.FilterChip(selected = vb == style, onClick = { vm.setVecBrush(style) }, label = { Text(style.ru, fontSize = 12.sp, maxLines = 1, softWrap = false) })
            }
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
        val cur = vm.presetById(p.presetId)   // спринт 39: и свои кисти
        OutlinedButton(onClick = vm::toggleBrushMenu, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(36.dp)) {
            val prev = vm.brushPreviews[p.presetId]
            if (prev != null) androidx.compose.foundation.Image(prev, null, modifier = Modifier.height(24.dp).width(64.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            com.vibetoon.ui.DropText(cur?.title ?: "Кисти", 12.sp, Modifier.padding(start = 6.dp))
        }
        com.vibetoon.ui.inspector.ScrubBox("Размер", p.size, 1f, 1500f, { v -> vm.updatePaint { it.copy(size = v) } }, log = true)
        com.vibetoon.ui.inspector.ScrubBox("Непр.", p.opacity * 100f, 1f, 100f, { v -> vm.updatePaint { it.copy(opacity = v / 100f) } }, perPx = 0.35f)
        com.vibetoon.ui.inspector.ScrubBox("Жёстк.", p.hardness * 100f, 0f, 100f, { v -> vm.updatePaint { it.copy(hardness = v / 100f) } }, perPx = 0.35f)
        // Stylus sprint: стабилизация 1–10 общая для кисти и ластика (хранится в настройках и резервной копии)
        com.vibetoon.ui.theme.LocalAppSettings.current?.let { stab ->
            com.vibetoon.ui.inspector.ScrubBox("Стаб.", stab.stabilization.toFloat(), 1f, 10f, { v ->
                val level = Math.round(v).coerceIn(1, 10)
                if (level != stab.stabilization) { stab.stabilization = level; stab.saveStylus() }
            }, perPx = 0.04f)
        }
        if (!isEraser && p.mode == com.vibetoon.domain.paint.StrokeMode.ACCUMULATE)
            com.vibetoon.ui.inspector.ScrubBox("Поток", p.flow * 100f, 1f, 100f, { v -> vm.updatePaint { it.copy(flow = v / 100f) } }, perPx = 0.35f)
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
        // спринт 36: наклон целого слоя/группы (углы перспективы - искажение, наклон - числами)
        var skewOpen by remember { mutableStateOf<Pair<Float, Float>?>(null) }
        skewOpen?.let { cur -> LayerSkewDialog(cur, onDismiss = { skewOpen = null }, onApply = { kx, ky -> skewOpen = null; vm.skewLayer(kx, ky) }) }
        OutlinedButton(
            onClick = { skewOpen = vm.layerSkewNow() ?: (0f to 0f) }, enabled = overlay.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)
        ) { Text("Наклон…", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        OutlinedButton(
            onClick = vm::straightenLayer, enabled = overlay.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)
        ) { Text("Выпрямить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        OutlinedButton(
            onClick = vm::resetPersp, enabled = overlay.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)
        ) { Text("Сбросить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
    }
}

/** Спринт 36: меню опорной точки рамки трансформации (сетка 3x3). */
@Composable
private fun TransformAnchorMenu(onPick: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) { com.vibetoon.ui.DropText("Опорная точка") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((a, t) in listOf(
                (1 to 1) to "Центр", (0 to 0) to "Левый верхний угол", (1 to 0) to "Верхний край", (2 to 0) to "Правый верхний угол",
                (0 to 1) to "Левый край", (2 to 1) to "Правый край", (0 to 2) to "Левый нижний угол", (1 to 2) to "Нижний край", (2 to 2) to "Правый нижний угол"
            )) DropdownMenuItem(text = { Text(t) }, onClick = { open = false; onPick(a.first, a.second) })
        }
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
    val boneTool by vm.boneTool.collectAsStateWithLifecycle()
    val selectMode by vm.boneSelectMode.collectAsStateWithLifecycle()
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        // спринт 49: как выбирать слои для привязки - обвести область или касаться конкретных слоёв
        if (boneTool == com.vibetoon.ui.BoneTool.LASSO) {
            FilterChip(selected = selectMode == com.vibetoon.domain.input.BoneSelectMode.LASSO,
                onClick = { vm.setBoneSelectMode(com.vibetoon.domain.input.BoneSelectMode.LASSO) },
                label = { Text("Лассо", fontSize = 12.sp, maxLines = 1, softWrap = false) })
            FilterChip(selected = selectMode == com.vibetoon.domain.input.BoneSelectMode.POINT,
                onClick = { vm.setBoneSelectMode(com.vibetoon.domain.input.BoneSelectMode.POINT) },
                label = { Text("Точечный выбор", fontSize = 12.sp, maxLines = 1, softWrap = false) })
        }
        if (lasso.isNotEmpty()) {
            Button(onClick = vm::bindLassoToBone, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                Text("Привязать (${lasso.size})", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = vm::clearLasso, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(32.dp)) {
                Icon(Icons.Default.Close, com.vibetoon.ui.i18n.L("Сбросить"), Modifier.size(16.dp))
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

/** Пакет 12: инструменты с панелью настроек над холстом - повторный тап прячет/показывает её. */
private val TOOLS_WITH_ROW = setOf(ToolId.SELECTION, ToolId.SMUDGE, ToolId.FILL, ToolId.PIPETTE, ToolId.PATH, ToolId.WARP, ToolId.PERSPECTIVE, ToolId.LIQUIFY)

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
        Icon(icon, com.vibetoon.ui.i18n.L(title), modifier = Modifier.size(20.dp), tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(title, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
    }
}

/** Спринт 60: кнопка панели инструментов - одна для колонки у края и для строки под холстом. */
private class RailItem(val icon: ImageVector, val title: String, val on: Boolean, val dividerAfter: Boolean = false, val onClick: () -> Unit)

/** Инструменты текущего режима: контекстные (Кости / Сетка), затем общие. */
private fun railItems(
    selected: String, boneMode: Boolean, mesh: Boolean, boneTool: BoneTool, meshTool: MeshTool, vm: MainViewModel,
    onPick: (ToolId) -> Unit
): List<RailItem> {
    val out = ArrayList<RailItem>()
    // контекстные инструменты режима
    if (boneMode) {
        out += RailItem(Icons.Default.AddCircleOutline, "+Кость", boneTool == BoneTool.ADD) { vm.setBoneTool(BoneTool.ADD) }
        out += RailItem(Icons.Default.OpenWith, "Кости", boneTool == BoneTool.SELECT) { vm.setBoneTool(BoneTool.SELECT) }
        out += RailItem(Icons.Default.Polyline, "Лассо", boneTool == BoneTool.LASSO, dividerAfter = true) { vm.setBoneTool(BoneTool.LASSO) }
    } else if (mesh) {
        out += RailItem(Icons.Default.PinDrop, "+Пин", meshTool == MeshTool.PIN_ADD) { vm.setMeshTool(MeshTool.PIN_ADD) }
        out += RailItem(Icons.Default.OpenWith, "Пин", meshTool == MeshTool.PIN_MOVE, dividerAfter = true) { vm.setMeshTool(MeshTool.PIN_MOVE) }
        out += RailItem(Icons.Default.ContentCut, "Разрез", meshTool == MeshTool.CUT) { vm.setMeshTool(MeshTool.CUT) }
        out += RailItem(Icons.Default.Gesture, "Кисть", meshTool == MeshTool.SMUDGE, dividerAfter = true) { vm.setMeshTool(MeshTool.SMUDGE) }
    }
    for (t in ToolId.values()) out += RailItem(t.icon, t.title, t.name == selected && !boneMode && !mesh) { onPick(t) }
    return out
}

@Composable
private fun LeftToolbar(
    selected: String, railDp: Float, boneMode: Boolean, mesh: Boolean, boneTool: BoneTool, meshTool: MeshTool, vm: MainViewModel,
    onPick: (ToolId) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(
            Modifier.width(railDp.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            for (it in railItems(selected, boneMode, mesh, boneTool, meshTool, vm, onPick)) {
                RailButton(it.icon, it.title, it.on, it.onClick)
                if (it.dividerAfter) HorizontalDivider(Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

/**
 * Спринт 60: строка инструментов под холстом (телефон в портрете). Прокручивается по горизонтали, кнопки не
 * наезжают друг на друга. Режим левши: порядок зеркальный, строка начинается у правого края.
 */
@Composable
private fun BottomToolStrip(items: List<RailItem>, spec: com.vibetoon.domain.ui.EditorLayout.Spec) {
    val ordered = com.vibetoon.domain.ui.EditorLayout.stripOrder(items, spec)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(com.vibetoon.domain.ui.EditorLayout.TOOL_STRIP_DP.dp), contentAlignment = if (spec.toolsAtEnd) Alignment.CenterEnd else Alignment.CenterStart) {
            Row(
                Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = spec.toolsAtEnd).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for ((i, it) in ordered.withIndex()) {
                    // разделитель групп остаётся между теми же кнопками и в зеркальном порядке
                    val divBefore = spec.toolsAtEnd && it.dividerAfter && i > 0
                    if (divBefore) androidx.compose.material3.VerticalDivider(Modifier.height(32.dp).padding(horizontal = 2.dp))
                    Box(Modifier.width(56.dp)) { RailButton(it.icon, it.title, it.on, it.onClick) }
                    if (!spec.toolsAtEnd && it.dividerAfter) androidx.compose.material3.VerticalDivider(Modifier.height(32.dp).padding(horizontal = 2.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- правая панель

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RightPanel(vm: MainViewModel, add: AddActions, color: Long, onColor: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val stub = LocalStub.current
    var tab by rememberSaveable { mutableStateOf(0) }
    // спринт 49: поля инспектора прокручиваются над клавиатурой, холст рядом не сжимается
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = modifier.keyboardInsetPanel()) {
        Column(Modifier.fillMaxSize()) {
            // свайп вправо по шапке панели (вкладки) закрывает её
            SafeTabRow(
                selectedTabIndex = tab, tabCount = 2,
                modifier = Modifier.height(40.dp).pointerInput(Unit) {
                    var sum = 0f
                    detectHorizontalDragGestures(onDragStart = { sum = 0f }, onDragEnd = { if (sum > 80f) onClose() }) { _, d -> sum += d }
                }
            ) {
                Tab(modifier = Modifier.coachTarget("Слои"), selected = tab == 0, onClick = { tab = 0 }, text = { Text("Слои", fontSize = 13.sp) })
                Tab(modifier = Modifier.coachTarget("Свойства"), selected = tab == 1, onClick = { tab = 1 }, text = { Text("Свойства", fontSize = 13.sp) })
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
                IconButton(onClick = { tab = 0 }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Layers, com.vibetoon.ui.i18n.L("Слои"), tint = if (tab == 0) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current) }
                IconButton(onClick = { vm.undo() }, enabled = canUndo && !playing, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Undo, com.vibetoon.ui.i18n.L("Отменить")) }
                IconButton(onClick = { vm.redo() }, enabled = canRedo && !playing, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Redo, com.vibetoon.ui.i18n.L("Повторить")) }
                IconButton(onClick = { vm.quickDelete(deleteDrawing) }, enabled = selection.isNotEmpty() && !playing, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, com.vibetoon.ui.i18n.L("Удалить")) }
                IconButton(onClick = { vm.selectLayer(null) }, enabled = selection.isNotEmpty(), modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Deselect, com.vibetoon.ui.i18n.L("Снять выделение")) }
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
    val listMode by vm.layersList.collectAsStateWithLifecycle()   // спринт 39: вид «Миниатюры» / «Список»
    val opening by vm.opening.collectAsStateWithLifecycle()   // спринт 47: проект ещё открывается - не «пустой проект»
    var multi by remember { mutableStateOf(false) }   // режим галочек: выбрать несколько слоёв и привязать к кости за одно действие
    var boneMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var renameFor by remember { mutableStateOf<Int?>(null) }
    var symbolModeFor by remember { mutableStateOf<Int?>(null) }
    symbolModeFor?.let { sid ->
        com.vibetoon.ui.motion.AlertDialog(
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
    // Пакет 8: автопрокрутка списка при перетаскивании у верхнего/нижнего края (проекты на 60+ слоёв)
    val listScroll = rememberScrollState()
    var listViewportPx by remember { mutableStateOf(0) }
    val rowsNow = androidx.compose.runtime.rememberUpdatedState(rows)
    // строка-цель пересчитывается при каждом движении пальца, но панель (60+ строк) перекомпонуется только при смене цели
    val targetIdx by remember(rowPx) {
        androidx.compose.runtime.derivedStateOf {
            if (dragId == null) -1 else com.vibetoon.domain.input.EdgeAutoScroll.targetIndex(dragStartIdx, dragDy, rowPx, rowsNow.value.size)
        }
    }
    val dragScope = androidx.compose.runtime.rememberCoroutineScope()
    val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    val maxStepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
    renameFor?.let { rid ->
        var nm by remember(rid) { mutableStateOf(vm.layerNameOrNull(rid) ?: "") }
        com.vibetoon.ui.motion.AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Имя слоя") },
            text = { androidx.compose.material3.OutlinedTextField(value = nm, onValueChange = { nm = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ vm.renameLayer(rid, nm); renameFor = null }) { Text("Готово") } },
            dismissButton = { TextButton({ renameFor = null }) { Text("Отмена") } }
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (opening) "Загрузка проекта…" else "Слоёв: ${rows.size}", fontSize = 12.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            // спринт 39: переключатель вида «Миниатюры» / «Список» (запоминается в настройках)
            IconButton(onClick = { vm.setLayersListMode(false) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ViewModule, com.vibetoon.ui.i18n.L("Миниатюры"), tint = if (!listMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            IconButton(onClick = { vm.setLayersListMode(true) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ViewList, com.vibetoon.ui.i18n.L("Список"), tint = if (listMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            IconButton(onClick = { multi = !multi }, modifier = Modifier.size(36.dp).coachTarget("Мультивыбор")) {   // спринт 53: цель уроков
                Icon(Icons.Default.Checklist, com.vibetoon.ui.i18n.L("Мультивыбор"), tint = if (multi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            Box {
                IconButton(onClick = { addMenu = true }, modifier = Modifier.size(36.dp).coachTarget("Добавить слой")) { Icon(Icons.Default.AddCircle, com.vibetoon.ui.i18n.L("Добавить слой")) }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Пиксельный") }, onClick = { addMenu = false; vm.addPaintLayer() })
                    DropdownMenuItem(text = { Text("Анимационный (покадровый)") }, onClick = { addMenu = false; vm.addAnimationLayer() })
                    DropdownMenuItem(text = { Text("Векторный") }, onClick = { addMenu = false; vm.addVectorLayer() })
                    DropdownMenuItem(text = { Text("Группа") }, onClick = { addMenu = false; vm.addEmptyGroup() })
                    DropdownMenuItem(text = { Text("Фон") }, onClick = { addMenu = false; vm.addBackgroundLayer() })
                    DropdownMenuItem(text = { Text("Аудио") }, onClick = { addMenu = false; add.audio() })
                    DropdownMenuItem(text = { Text("Видео") }, onClick = { addMenu = false; add.video() })
                    DropdownMenuItem(text = { Text("Изображение") }, onClick = { addMenu = false; add.image() })
                    DropdownMenuItem(text = { Text("Секвенция картинок") }, onClick = { addMenu = false; add.sequence(false) })
                    DropdownMenuItem(text = { Text("Текст") }, onClick = { addMenu = false; add.text() })
                    DropdownMenuItem(text = { Text("Камера (Pan & Zoom)") }, onClick = { addMenu = false; vm.addCameraLayer() })
                    DropdownMenuItem(text = { Text("Transform Layer (null)") }, onClick = { addMenu = false; vm.addNullLayer() })
                    DropdownMenuItem(text = { Text("Слой-путь (Path)") }, onClick = { addMenu = false; vm.addPathLayer() })      // спринт 38
                    DropdownMenuItem(text = { Text("Слой костей (Bones)") }, onClick = { addMenu = false; vm.addBoneLayer() })
                }
            }
        }
        if (multi && selection.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.deleteSelected() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                com.vibetoon.ui.IconText(Icons.Default.Delete, "Удалить выбранные (${selection.size})", 11.sp)
            }
            // спринт 54: «Слить в один слой» - первым и отдельно от «Сгруппировать (папка)», чтобы не перепутать
            OutlinedButton(onClick = { vm.mergeSelected() }, enabled = selection.size >= 2, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                com.vibetoon.ui.IconText(Icons.Default.MergeType, "Слить в один слой", 11.sp)
            }
            OutlinedButton(onClick = { vm.groupSelected() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                com.vibetoon.ui.IconText(Icons.Default.Folder, "Сгруппировать (папка)", 11.sp)
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
        if (listMode) LayerListBody(
            vm, rows, selection, lprops, bonesList, multi, menuFor, stub, Modifier.weight(1f).fillMaxWidth(),
            onMenu = { menuFor = it }, onMulti = { multi = true }, onRename = { renameFor = it }, onSymbolMode = { symbolModeFor = it }
        ) else Column(Modifier.weight(1f).fillMaxWidth().onSizeChanged { listViewportPx = it.height }.verticalScroll(listScroll)) {
            if (rows.isEmpty() && !opening) Text(
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
                            Icon(Icons.Default.Checklist, com.vibetoon.ui.i18n.L("Мультивыбор"))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Lock, com.vibetoon.ui.i18n.L("Замок")); Icon(Icons.Default.ContentCopy, com.vibetoon.ui.i18n.L("Дублировать")); Icon(Icons.Default.Delete, com.vibetoon.ui.i18n.L("Удалить"))
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
                        if (multi) Icon(if (sel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, com.vibetoon.ui.i18n.L("Выбрать"), tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(end = 4.dp).size(24.dp).clickable { vm.toggleSelect(l.id) })
                        Icon(
                            if (lprops[l.id]?.visible == false) Icons.Default.VisibilityOff else Icons.Default.Visibility, com.vibetoon.ui.i18n.L("Видимость"),
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
                                thumb, com.vibetoon.ui.i18n.L(type), modifier = Modifier.fillMaxSize().thumbnailChecker(com.vibetoon.ui.theme.LocalAppSettings.current?.checkerThumbnails == true), contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            ) else Icon(
                                when (type) {
                                    "Текст" -> Icons.Default.TextFields; "Картинка" -> Icons.Default.Image; "Видео" -> Icons.Default.Videocam
                                    "Группа" -> Icons.Default.Folder; "Путь" -> Icons.Default.Timeline; "Слой костей" -> Icons.Default.AccountTree
                                    else -> Icons.Default.Layers
                                }, com.vibetoon.ui.i18n.L(type), modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (r.isGroup) Icon(if (r.expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(14.dp))
                                Text(l.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val lp = lprops[l.id]
                            val extra = (if (lp == null) "" else buildString {
                                if (lp.blend != "norm") append(" · ${LayerBlend.title(lp.blend)}")
                                if (lp.opacity < 0.995f) append(" · ${(lp.opacity * 100).toInt()}%")
                                if (lp.alphaLock) append(" · Alpha Lock")
                                if (lp.clip) append(" · Clip")
                                // спринт 38: маска слоя и прикрепление к пути видны в строке слоя
                                if (lp.mask >= 0) append(" · " + com.vibetoon.ui.i18n.L(if (lp.maskOff) "Маска выкл." else if (lp.maskInvert) "Маска (инверсия)" else "Маска"))
                                if (lp.path >= 0) append(" · " + com.vibetoon.ui.i18n.L("На пути"))
                            }) + (if (vm.maskTargetOf(l.id) != null) " · " + com.vibetoon.ui.i18n.L("Слой-маска") else "")
                            Text(vm.layerType(l.id) + extra, fontSize = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        Box(
                            Modifier.width(36.dp).fillMaxHeight().pointerInput(l.id) {
                                // Ручка забирает нажатие сразу (consume): родительский combinedClickable не успевает открыть контекстное меню,
                                // перетаскивание Z-порядка начинается с первого касания и работает и без движения до долгого нажатия
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val list = rowsNow.value
                                    dragId = l.id; dragDy = 0f; dragStartIdx = list.indexOfFirst { it.layer.id == l.id }
                                    val downY = down.position.y
                                    // палец у края окна списка - список едет сам, строка остаётся под пальцем
                                    val auto = dragScope.dragEdgeAutoScroll(listScroll, { listViewportPx }, edgePx, maxStepPx,
                                        { com.vibetoon.domain.input.EdgeAutoScroll.contentPos(dragStartIdx, rowPx, downY, dragDy) - listScroll.value }) { used -> dragDy += used }
                                    val lifted = try { drag(down.id) { ch -> dragDy += ch.positionChange().y; ch.consume() } } finally { auto.cancel() }
                                    val t = com.vibetoon.domain.input.EdgeAutoScroll.targetIndex(dragStartIdx, dragDy, rowPx, list.size)
                                    val target = list.getOrNull(t)?.layer?.id
                                    if (lifted && dragStartIdx >= 0 && t != dragStartIdx && target != null && target != l.id) vm.dropLayer(l.id, target)
                                    dragId = null; dragDy = 0f
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) { Text("≡", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) }
                        Icon(
                            if (lprops[l.id]?.locked == true) Icons.Default.Lock else Icons.Default.LockOpen, com.vibetoon.ui.i18n.L("Замок"),
                            modifier = Modifier.size(18.dp).clickable { vm.toggleLocked(l.id) }
                        )
                    }
                    LayerContextMenu(vm, l, menuFor == l.id, selection, stub, onClose = { menuFor = null }, onMulti = { multi = true }, onRename = { renameFor = it }, onSymbolMode = { symbolModeFor = it })
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
 * Спринт 39: вид панели слоёв «Список» - компактные строки без миниатюр, поиск по имени, фильтры по типу,
 * папки (свернуть/развернуть все), маски (вкл/инверсия прямо в строке), слои-пути (сколько слоёв на пути) и
 * кости внутри слоя костей (тап выбирает кость). Порядок меняется стрелками у выбранного слоя или через меню.
 * Спринт 40: и перетаскиванием за ручку ≡ (LayerListDrag): бросок на группу - внутрь, на слой - на его место, у края
 * список прокручивается сам; при поиске/фильтре ручки нет.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerListBody(
    vm: MainViewModel, rows: List<com.vibetoon.domain.model.TreeRow>, selection: Set<Int>,
    lprops: Map<Int, com.vibetoon.domain.model.LayerProps>, bones: List<com.vibetoon.domain.project.Bone>,
    multi: Boolean, menuFor: Int?, stub: (String) -> Unit, modifier: Modifier,
    onMenu: (Int?) -> Unit, onMulti: () -> Unit, onRename: (Int) -> Unit, onSymbolMode: (Int) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val opening by vm.opening.collectAsStateWithLifecycle()   // спринт 47
    var filterName by rememberSaveable { mutableStateOf(com.vibetoon.domain.model.LayerListView.Filter.ALL.name) }
    val filter = com.vibetoon.domain.model.LayerListView.Filter.values().firstOrNull { it.name == filterName } ?: com.vibetoon.domain.model.LayerListView.Filter.ALL
    var bonesCollapsed by remember { mutableStateOf(setOf<Int>()) }
    val full = remember(rows, lprops) { vm.treeRowsExpanded() }
    val infoById = remember(full) { full.associate { it.layer.id to it.layer } }
    fun entryOf(r: com.vibetoon.domain.model.TreeRow): com.vibetoon.domain.model.LayerListView.Entry {
        val id = r.layer.id
        val lp = lprops[id]
        return com.vibetoon.domain.model.LayerListView.Entry(
            id, r.layer.name, r.depth, r.layer.parentId, com.vibetoon.domain.model.LayerListView.categoryOf(vm.layerType(id)),
            isGroup = r.isGroup, mask = (lp != null && lp.mask >= 0) || vm.maskTargetOf(id) != null
        )
    }
    val bonesByLayer = remember(bones) { bones.filter { it.layer != 0 }.groupBy { it.layer }.mapValues { (_, list) -> list.map { it.id to it.name } } }
    val shown = remember(rows, full, lprops, bonesByLayer, bonesCollapsed, filter, query) {
        com.vibetoon.domain.model.LayerListView.apply(
            com.vibetoon.domain.model.LayerListView.withBones(rows.map { entryOf(it) }, bonesByLayer, bonesCollapsed),
            com.vibetoon.domain.model.LayerListView.withBones(full.map { entryOf(it) }, bonesByLayer),
            filter, query
        )
    }
    val searching = com.vibetoon.domain.model.LayerListView.active(filter, query)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    // спринт 40: перетаскивание за ручку ≡ (строка слоя 32 dp, кости 30 dp), автопрокрутка у края списка
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val layerRowPx = with(dens) { 32.dp.toPx() }
    val boneRowPx = with(dens) { 30.dp.toPx() }
    val listEdgePx = with(dens) { 48.dp.toPx() }
    val listStepPx = with(dens) { 16.dp.toPx() }
    var listDragId by remember { mutableStateOf<Int?>(null) }
    var listDragDy by remember { mutableStateOf(0f) }
    var listDragStart by remember { mutableStateOf(-1) }
    val shownNow = androidx.compose.runtime.rememberUpdatedState(shown)
    val listDragScope = androidx.compose.runtime.rememberCoroutineScope()
    val listTarget by remember(layerRowPx, boneRowPx) {
        androidx.compose.runtime.derivedStateOf {
            val id = listDragId ?: return@derivedStateOf -1
            val es = shownNow.value.map { it.entry }
            com.vibetoon.domain.model.LayerListDrag.dropTarget(es, es.map { if (it.bone) boneRowPx else layerRowPx }, listDragStart, listDragDy, id) ?: -1
        }
    }
    Column(modifier) {
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it.take(60) }, singleLine = true,
            placeholder = { Text("Поиск слоя", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, com.vibetoon.ui.i18n.L("Поиск слоя"), modifier = Modifier.size(18.dp)) },
            trailingIcon = { if (query.isNotEmpty()) Icon(Icons.Default.Close, com.vibetoon.ui.i18n.L("Очистить поиск"), modifier = Modifier.size(18.dp).clickable { query = "" }) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 44.dp)
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (f in com.vibetoon.domain.model.LayerListView.Filter.values())
                FilterChip(selected = f == filter, onClick = { filterName = f.name }, label = { Text(f.title, fontSize = 11.sp, maxLines = 1, softWrap = false) }, modifier = Modifier.height(28.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(com.vibetoon.domain.model.LayerListView.countLabel(shown, full.size, filter, query), fontSize = 11.sp, color = dim, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.setAllGroupsCollapsed(true); bonesCollapsed = bonesByLayer.keys }, enabled = !searching, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.UnfoldLess, com.vibetoon.ui.i18n.L("Свернуть все"), modifier = Modifier.size(18.dp)) }
            IconButton(onClick = { vm.setAllGroupsCollapsed(false); bonesCollapsed = emptySet() }, enabled = !searching, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.UnfoldMore, com.vibetoon.ui.i18n.L("Развернуть все"), modifier = Modifier.size(18.dp)) }
        }
        if (shown.isEmpty() && !opening) Text(
            if (searching) "Ничего не найдено: измените поиск или фильтр" else "Слоёв пока нет. Нажмите «+» вверху, чтобы добавить PSD, картинку, видео или текст.",
            fontSize = 12.sp, modifier = Modifier.padding(12.dp), color = dim
        )
        if (searching) Text("Перетаскивание слоёв выключено во время поиска и фильтра", fontSize = 10.sp, color = dim, modifier = Modifier.padding(horizontal = 12.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(shown.size, key = { i -> (if (shown[i].entry.bone) "b" else "l") + shown[i].entry.id }) { i ->
                val sh = shown[i]
                val e = sh.entry
                val sel = e.id in selection
                val isListDrag = !e.bone && listDragId == e.id
                val isListTarget = !e.bone && listDragId != null && listTarget == e.id
                val rowColor = when {
                    isListTarget && e.isGroup -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                    isListTarget -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    sel -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                if (e.bone) {
                    // строка кости: тап - режим «Кости» и выбор этой кости
                    Row(
                        Modifier.fillMaxWidth().height(30.dp).background(rowColor).clickable { vm.selectBoneRow(e.id) }.padding(start = (8 + 12 * e.depth).dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Straighten, com.vibetoon.ui.i18n.L("Кость"), modifier = Modifier.size(14.dp), tint = dim)
                        Text(com.vibetoon.ui.i18n.boneDisplayName(e.name), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp).weight(1f), color = if (sh.matched) MaterialTheme.colorScheme.onSurface else dim)
                    }
                    return@items
                }
                val l = infoById[e.id] ?: rows.firstOrNull { it.layer.id == e.id }?.layer ?: return@items
                val lp = lprops[e.id]
                val type = vm.layerType(e.id)
                Box(Modifier.zIndex(if (isListDrag) 1f else 0f)) {
                    Row(
                        Modifier.fillMaxWidth().height(32.dp)
                            .graphicsLayer { translationY = if (isListDrag) listDragDy else 0f; alpha = if (isListDrag) 0.7f else 1f }
                            .background(if (isListDrag) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
                            .background(rowColor)
                            .combinedClickable(
                                onClick = { if (multi) vm.toggleSelect(e.id) else vm.selectLayer(e.id) },
                                onLongClick = { onMenu(e.id) }
                            )
                            .padding(start = (4 + 12 * e.depth).dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (multi) Icon(if (sel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, com.vibetoon.ui.i18n.L("Выбрать"), tint = if (sel) MaterialTheme.colorScheme.primary else dim, modifier = Modifier.padding(end = 2.dp).size(20.dp).clickable { vm.toggleSelect(e.id) })
                        val foldable = e.isGroup || e.category == com.vibetoon.domain.model.LayerListView.Category.BONES && bonesByLayer.containsKey(e.id)
                        val expanded = if (e.isGroup) rows.firstOrNull { it.layer.id == e.id }?.expanded ?: true else e.id !in bonesCollapsed
                        Box(Modifier.size(20.dp).then(if (foldable && !searching) Modifier.clickable {
                            if (!e.isGroup) bonesCollapsed = if (e.id in bonesCollapsed) bonesCollapsed - e.id else bonesCollapsed + e.id
                            if (e.isGroup) vm.toggleGroup(e.id)
                        } else Modifier), contentAlignment = Alignment.Center) {
                            if (foldable) Icon(if (expanded || searching) Icons.Default.ExpandMore else Icons.Default.ChevronRight, com.vibetoon.ui.i18n.L("Свернуть / развернуть"), modifier = Modifier.size(16.dp))
                        }
                        Icon(
                            when (type) {
                                "Текст" -> Icons.Default.TextFields; "Картинка" -> Icons.Default.Image; "Видео" -> Icons.Default.Videocam; "Аудио" -> Icons.Default.Audiotrack
                                "Группа" -> Icons.Default.Folder; "Символ" -> Icons.Default.Widgets; "Путь" -> Icons.Default.Timeline; "Слой костей" -> Icons.Default.AccountTree
                                "Вектор" -> Icons.Default.Gesture; "Камера" -> Icons.Default.Videocam
                                else -> Icons.Default.Brush
                            }, com.vibetoon.ui.i18n.L(type), modifier = Modifier.padding(horizontal = 4.dp).size(16.dp), tint = dim
                        )
                        Text(
                            l.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            color = if (sh.matched) MaterialTheme.colorScheme.onSurface else dim,
                            fontWeight = if (searching && sh.matched) androidx.compose.ui.text.font.FontWeight.Bold else null
                        )
                        // маска слоя: включить/выключить и инверсия прямо в строке
                        if (lp != null && lp.mask >= 0) {
                            Icon(Icons.Default.Masks, com.vibetoon.ui.i18n.L(if (lp.maskOff) "Маска выкл." else "Маска"), tint = if (lp.maskOff) dim else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp).clickable { vm.setMaskEnabled(e.id, lp.maskOff) }.padding(4.dp))
                            Icon(Icons.Default.InvertColors, com.vibetoon.ui.i18n.L("Маска (инверсия)"), tint = if (lp.maskInvert) MaterialTheme.colorScheme.primary else dim,
                                modifier = Modifier.size(26.dp).clickable { vm.setMaskInvert(e.id, !lp.maskInvert) }.padding(4.dp))
                        }
                        if (vm.maskTargetOf(e.id) != null) Text(com.vibetoon.ui.i18n.L("Слой-маска"), fontSize = 9.sp, color = dim, modifier = Modifier.padding(horizontal = 2.dp))
                        if (lp != null && lp.path >= 0) Text(com.vibetoon.ui.i18n.L("На пути"), fontSize = 9.sp, color = dim, modifier = Modifier.padding(horizontal = 2.dp))
                        if (e.category == com.vibetoon.domain.model.LayerListView.Category.PATH) {
                            val n = lprops.values.count { it.path == e.id }
                            if (n > 0) Text("Слоёв на пути: $n", fontSize = 9.sp, color = dim, modifier = Modifier.padding(horizontal = 2.dp))
                        }
                        if (sel && selection.size == 1 && !multi) {
                            Icon(Icons.Default.ArrowUpward, com.vibetoon.ui.i18n.L("Выше"), modifier = Modifier.size(28.dp).clickable { vm.moveLayerStep(e.id, up = true) }.padding(5.dp))
                            Icon(Icons.Default.ArrowDownward, com.vibetoon.ui.i18n.L("Ниже"), modifier = Modifier.size(28.dp).clickable { vm.moveLayerStep(e.id, up = false) }.padding(5.dp))
                        }
                        Icon(
                            if (lp?.visible == false) Icons.Default.VisibilityOff else Icons.Default.Visibility, com.vibetoon.ui.i18n.L("Видимость"),
                            modifier = Modifier.size(28.dp).clickable { vm.toggleVisible(e.id) }.padding(5.dp)
                        )
                        Icon(
                            if (lp?.locked == true) Icons.Default.Lock else Icons.Default.LockOpen, com.vibetoon.ui.i18n.L("Замок"),
                            modifier = Modifier.size(28.dp).clickable { vm.toggleLocked(e.id) }.padding(5.dp)
                        )
                        // спринт 40: ручка ≡ - перетаскивание слоя (как в виде «Миниатюры»): бросок на группу - внутрь, на слой - на его место
                        val dragDesc = com.vibetoon.ui.i18n.L("Перетащить слой")
                        if (com.vibetoon.domain.model.LayerListDrag.canDrag(e, searching, multi)) Box(
                            Modifier.width(32.dp).fillMaxHeight().semantics { contentDescription = dragDesc }.pointerInput(e.id) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val start = shownNow.value.indexOfFirst { !it.entry.bone && it.entry.id == e.id }
                                    if (start < 0) return@awaitEachGesture
                                    listDragId = e.id; listDragDy = 0f; listDragStart = start
                                    val rowTop = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == start }?.offset?.toFloat() ?: 0f
                                    val downY = down.position.y
                                    var scrolled = 0f
                                    val auto = listDragScope.launch {
                                        while (true) {
                                            kotlinx.coroutines.delay(16)
                                            val pos = rowTop + downY + listDragDy - scrolled
                                            val v = com.vibetoon.domain.input.EdgeAutoScroll.velocity(pos, listState.layoutInfo.viewportSize.height, listEdgePx, listStepPx,
                                                listState.canScrollBackward, listState.canScrollForward)
                                            if (v != 0f) {
                                                val used = listState.scrollBy(v)
                                                if (used != 0f) { scrolled += used; listDragDy += used }
                                            }
                                        }
                                    }
                                    val lifted = try { drag(down.id) { ch -> listDragDy += ch.positionChange().y; ch.consume() } } finally { auto.cancel() }
                                    val es = shownNow.value.map { it.entry }
                                    val target = com.vibetoon.domain.model.LayerListDrag.dropTarget(es, es.map { if (it.bone) boneRowPx else layerRowPx }, start, listDragDy, e.id)
                                    listDragId = null; listDragDy = 0f; listDragStart = -1
                                    if (lifted && target != null) vm.dropLayer(e.id, target)
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) { Text("≡", fontSize = 18.sp, color = dim) }
                    }
                    LayerContextMenu(vm, l, menuFor == e.id, selection, stub, onClose = { onMenu(null) }, onMulti = onMulti, onRename = onRename, onSymbolMode = onSymbolMode)
                }
            }
        }
    }
}

/** Контекстное меню слоя (долгое нажатие): общее для вида «Миниатюры» и «Список» (спринт 39). */
@Composable
private fun LayerContextMenu(
    vm: MainViewModel, l: com.vibetoon.nativebridge.LayerInfo, expanded: Boolean, selection: Set<Int>, stub: (String) -> Unit,
    onClose: () -> Unit, onMulti: () -> Unit, onRename: (Int) -> Unit, onSymbolMode: (Int) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onClose) {
        for (title in listOf(
            "Выбрать несколько", "Переименовать", "Заблокировать / разблокировать", "Копировать", "Вставить выше",
            "Дублировать", "Наверх группы", "Вниз группы", "Удалить", "Очистить", "Маска вкл/выкл",
            "Растрировать", "Слить вниз", "Слить все видимые", "Создать символ"
        ) + (if (vm.isSymbolInstance(l.id)) listOf("Редактировать символ", "Выбрать маркер (фонему)") else emptyList())
          + (if (vm.maskOf(l.id) == null && vm.maskTargetOf(l.id) == null && vm.mediaKindOf(l.id).let { it == null || it == com.vibetoon.domain.project.MediaKind.IMAGE || it == com.vibetoon.domain.project.MediaKind.TEXT || it == com.vibetoon.domain.project.MediaKind.VIDEO || it == com.vibetoon.domain.project.MediaKind.GROUP || it == com.vibetoon.domain.project.MediaKind.SYMBOL }) listOf("Добавить маску слоя") else emptyList())
          + (if (vm.maskOf(l.id) != null) listOf("Удалить маску слоя") else emptyList())) {
            DropdownMenuItem(text = { Text(title) }, onClick = {
                onClose()
                when (title) {
                    "Выбрать несколько" -> { onMulti(); if (l.id !in selection) vm.toggleSelect(l.id) }
                    "Переименовать" -> onRename(l.id)
                    "Заблокировать / разблокировать" -> vm.toggleLocked(l.id)
                    "Копировать" -> vm.copyLayer(l.id)
                    "Вставить выше" -> vm.pasteAbove(l.id)
                    "Дублировать" -> { vm.selectLayer(l.id); vm.duplicateSelected() }
                    "Наверх группы" -> vm.moveLayerToEdge(l.id, top = true)   // пакет 25: PSD и медиа вперемешку
                    "Вниз группы" -> vm.moveLayerToEdge(l.id, top = false)
                    "Удалить" -> { vm.selectLayer(l.id); vm.deleteSelected() }
                    "Очистить" -> vm.clearLayer(l.id)
                    "Маска вкл/выкл" -> vm.toggleClipMask(l.id)
                    "Растрировать" -> vm.rasterizeLayer(l.id)
                    "Слить вниз" -> vm.mergeDown(l.id)
                    "Слить все видимые" -> vm.mergeVisible()
                    "Создать символ" -> if (selection.size >= 2 && l.id in selection) onSymbolMode(l.id) else vm.createSymbol(l.id)
                    "Редактировать символ" -> vm.enterSymbol(l.id)
                    "Выбрать маркер (фонему)" -> vm.openMarkerPanel(l.id)
                    "Добавить маску слоя" -> vm.addLayerMask(l.id)
                    "Удалить маску слоя" -> vm.deleteMask(l.id)
                    else -> stub(title)
                }
            })
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
                    leadingIcon = { Icon(Icons.Default.GridOn, com.vibetoon.ui.i18n.L("Замок прозрачности"), modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(30.dp)
                )
                FilterChip(
                    selected = p.clip, onClick = { vm.updateLayerProps(id) { it.copy(clip = !it.clip) } },
                    label = { Text("Clip", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.SubdirectoryArrowRight, com.vibetoon.ui.i18n.L("Обтравочная маска"), modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(30.dp)
                )
            }
        }
        Sprint38LayerExtras(vm, id, isGroup, p)
    }
}

/**
 * Спринт 38: свойства слоя-пути и слоя костей, маска слоя (вкл/выкл, инверсия, отвязать/удалить) и прикрепление к пути
 * (положение 0..100 % с ключами, ориентация по касательной). Все правки - с Undo/Redo и сохраняются в проекте.
 */
@Composable
private fun Sprint38LayerExtras(vm: MainViewModel, id: Int, isGroup: Boolean, p: com.vibetoon.domain.model.LayerProps) {
    val playing by vm.playing.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val pathRev by vm.pathRevision.collectAsStateWithLifecycle()
    val pathDraw by vm.pathDraw.collectAsStateWithLifecycle()
    val bones by vm.bones.collectAsStateWithLifecycle()
    val kind = vm.mediaKindOf(id)
    val pad = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    val hint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    when (kind) {
        com.vibetoon.domain.project.MediaKind.PATH -> {
            Text("Слой-путь: не рисуется и не экспортируется. Прикрепите слои к пути в их свойствах; трансформация пути двигает их вместе с ним.", fontSize = 10.sp, color = hint)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = pathDraw, enabled = !playing, onClick = { vm.setPathDraw(!pathDraw) }, label = { Text("Нарисовать путь", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.Gesture, null, modifier = Modifier.size(14.dp)) }, modifier = Modifier.height(30.dp))
                for ((key, title) in listOf("line" to "Линия", "arc" to "Дуга", "wave" to "Волна", "circle" to "Круг"))
                    OutlinedButton(onClick = { vm.setPathPreset(key) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text(title, fontSize = 11.sp) }
            }
            return
        }
        com.vibetoon.domain.project.MediaKind.BONES -> {
            val (own, free) = remember(bones, id) { vm.boneLayerCounts(id) }
            Text("Слой костей: хозяин скелета. Сдвиг, поворот и масштаб слоя двигают все его кости и привязанные к ним слои; глаз скрывает кости на холсте.", fontSize = 10.sp, color = hint)
            Text("Костей в слое: $own", fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.editBoneLayer(id) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("Кости этого слоя", fontSize = 11.sp) }
                if (free > 0) OutlinedButton(onClick = { vm.adoptFreeBones(id) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("Собрать свободные кости ($free)", fontSize = 11.sp) }
            }
            return
        }
        com.vibetoon.domain.project.MediaKind.NULLOBJ, com.vibetoon.domain.project.MediaKind.CAMERA, com.vibetoon.domain.project.MediaKind.AUDIO -> return
        else -> {}
    }
    // слой-маска: подсказка и переход к цели
    val target = vm.maskTargetOf(id)
    if (target != null) {
        Text("Маска слоя «${vm.layerName(target)}»: сама не рисуется. Ластик скрывает слой, кисть возвращает (с инверсией - наоборот).", fontSize = 10.sp, color = hint)
        OutlinedButton(onClick = { vm.selectLayer(target) }, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("К слою", fontSize = 11.sp) }
        return
    }
    // маска слоя
    val mask = if (p.mask >= 0) p.mask else null
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (mask == null) {
            OutlinedButton(onClick = { vm.addLayerMask(id) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("Добавить маску слоя", fontSize = 11.sp) }
            OutlinedButton(onClick = { vm.maskFromLayerAbove(id) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("Маска из слоя выше", fontSize = 11.sp) }
        } else {
            FilterChip(selected = !p.maskOff, enabled = !playing, onClick = { vm.setMaskEnabled(id, p.maskOff) }, label = { Text("Маска", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
            FilterChip(selected = p.maskInvert, enabled = !playing, onClick = { vm.setMaskInvert(id, !p.maskInvert) }, label = { Text("Инверсия", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
            OutlinedButton(onClick = { vm.selectLayer(mask) }, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("Править маску", fontSize = 11.sp) }
            OutlinedButton(onClick = { vm.unlinkMask(id) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("Отвязать", fontSize = 11.sp) }
            OutlinedButton(onClick = { vm.deleteMask(id) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("Удалить маску слоя", fontSize = 11.sp) }
        }
    }
    if (isGroup) return
    // прикрепление к пути
    val paths = remember(pathRev, id, p.path) { vm.pathLayers() }
    if (p.path < 0) {
        if (paths.isEmpty()) return
        var menu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("Прикрепить к пути…", fontSize = 11.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for ((pid, name) in paths) DropdownMenuItem(text = { Text(com.vibetoon.ui.i18n.layerDisplayName(name, false)) }, onClick = { menu = false; vm.attachToPath(id, pid) })
            }
        }
        return
    }
    val pos = remember(pathRev, frame, id) { vm.pathProgressOf(id) }
    val keyed = remember(pathRev, frame, id) { vm.pathKeyAt(id) }
    val pathName = paths.firstOrNull { it.first == p.path }?.second ?: "#${p.path}"
    Text("На пути «${com.vibetoon.ui.i18n.layerDisplayName(pathName, false)}»", fontSize = 11.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Полож.", fontSize = 11.sp, modifier = Modifier.width(44.dp))
        androidx.compose.material3.Slider(value = pos.coerceIn(0f, 100f), onValueChange = { vm.setPathProgress(id, it) }, enabled = !playing,
            valueRange = 0f..100f, modifier = Modifier.weight(1f).height(28.dp))
        Text("${pos.toInt()}%", fontSize = 11.sp, modifier = Modifier.width(38.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (keyed) OutlinedButton(onClick = { vm.deletePathKey(id) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("◇ Удалить ключ", fontSize = 11.sp) }
        else OutlinedButton(onClick = { vm.addPathKey(id) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("◆ Добавить ключ", fontSize = 11.sp) }
        FilterChip(selected = p.pathOrient, enabled = !playing, onClick = { vm.setPathOrient(id, !p.pathOrient) }, label = { Text("По касательной", fontSize = 11.sp) }, modifier = Modifier.height(30.dp))
        OutlinedButton(onClick = { vm.detachFromPath(id) }, enabled = !playing, contentPadding = pad, modifier = Modifier.height(30.dp)) { Text("Отцепить", fontSize = 11.sp) }
    }
}

/** Ключи позы кости (пакет 7): добавить/удалить, переход между ключами, плавность и график выделенного ключа. */
@Composable
private fun BoneKeyControls(vm: MainViewModel, bone: com.vibetoon.domain.project.Bone) {
    val animation by vm.animation.collectAsStateWithLifecycle()
    val keySel by vm.keySelection.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val frames = remember(animation, bone.id) { com.vibetoon.domain.anim.BoneKeys.frames(bone.id, animation.tracks) }
    val active = com.vibetoon.domain.anim.BoneKeys.activeFrame(bone.id, frames, keySel, frame)
    val easing = active?.let { animation.easingAt(bone.id, it) }
    val hasCurve = active?.let { animation.curveAt(bone.id, it) } != null
    val pad = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Ключи кости: ${frames.size}", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text(if (active != null) "Выбран ключ: ${com.vibetoon.domain.anim.TimeFormat.format(active, animation.settings.fps)}" else "Ключ не выбран: тапните ◆ на таймлайне", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        val chainKeys = com.vibetoon.ui.theme.LocalAppSettings.current?.boneChainKeys != false
        Text(if (chainKeys) "Ключ и автоключ - всей цепочке кости и другим выбранным костям" else "Ключ - выбранным костям", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.stepBoneKey(-1) }, enabled = !playing && frames.any { it < frame }, contentPadding = pad, modifier = Modifier.height(32.dp)) {
                Text("◀ Предыдущий ключ", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            // спринт 41: ключ ставится и цепочке/другим выбранным костям - кнопка доступна, даже если у этой кости ключ уже есть
            OutlinedButton(onClick = vm::addBoneKey, enabled = !playing, contentPadding = pad, modifier = Modifier.height(32.dp)) {
                Text("◆ Добавить ключ", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = { vm.stepBoneKey(1) }, enabled = !playing && frames.any { it > frame }, contentPadding = pad, modifier = Modifier.height(32.dp)) {
                Text("Следующий ключ ▶", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = vm::deleteBoneKey, enabled = !playing && active != null, contentPadding = pad, modifier = Modifier.height(32.dp)) {
                Text("◇ Удалить ключ", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }
        if (active != null) {
            Text("Переход к следующему ключу", fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((type, title) in listOf(
                    com.vibetoon.domain.anim.EasingType.LINEAR to "Линейно",
                    com.vibetoon.domain.anim.EasingType.EASE_IN to "Плавное начало",
                    com.vibetoon.domain.anim.EasingType.EASE_OUT to "Плавный конец",
                    com.vibetoon.domain.anim.EasingType.EASE_IN_OUT to "Плавно и там и там",
                    com.vibetoon.domain.anim.EasingType.STEP to "Ступенька (Hold)"
                )) androidx.compose.material3.FilterChip(
                    selected = !hasCurve && easing == type, enabled = !playing, onClick = { vm.setBoneKeyEasing(type) },
                    label = { Text(title, fontSize = 11.sp, maxLines = 1, softWrap = false) }, modifier = Modifier.height(30.dp)
                )
                androidx.compose.material3.FilterChip(
                    selected = hasCurve, enabled = !playing, onClick = vm::openBoneKeyCurve,
                    label = { Text("График…", fontSize = 11.sp, maxLines = 1, softWrap = false) }, modifier = Modifier.height(30.dp)
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
    // Пакет 8: имя кости («Предплечье») - видно на таймлайне и в списке костей, сохраняется в проекте, Undo/Redo
    var renaming by remember(bone.id) { mutableStateOf(false) }
    if (renaming) {
        val shown = com.vibetoon.ui.i18n.boneDisplayName(bone.name)
        var nm by remember(bone.id) { mutableStateOf(shown) }
        val ok = com.vibetoon.domain.project.BoneRename.sanitize(nm, bone.name) != null && nm.trim() != shown
        com.vibetoon.ui.motion.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Имя кости") },
            text = {
                androidx.compose.material3.OutlinedTextField(value = nm, onValueChange = { nm = it.take(com.vibetoon.domain.project.BoneRename.MAX_LEN) }, singleLine = true,
                    placeholder = { Text("Например: Предплечье") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton({ vm.renameBone(bone.id, nm); renaming = false }, enabled = ok) { Text("Готово") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Отмена") } }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Кость: ${com.vibetoon.ui.i18n.boneDisplayName(bone.name)}", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            IconButton(onClick = { renaming = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Edit, com.vibetoon.ui.i18n.L("Переименовать кость"), modifier = Modifier.size(18.dp)) }
        }
        Text("Длина: ${bone.length.toInt()} px", fontSize = 12.sp)
        Text("Родитель: $parentName", fontSize = 12.sp)
        Text("Привязано слоёв: ${bound.size}", fontSize = 12.sp)
        BoneKeyControls(vm, bone)
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

/**
 * Библиотека ассетов: папки «Картинки», «Видео», «Аудио», «PSD» (пакет 11) - файлы слоёв проекта и файлы удалённых
 * слоёв (миниатюры, у видео - первый кадр); тап вставляет как новый слой (видео - видео-слоем, аудио - дорожкой,
 * другой PSD - по PSD Import Mode, пакет 23). Кэш скрыт. Пакет 23: пользовательские папки - «+ Папка», долгое нажатие
 * на файл кладёт его в папку, долгое нажатие на папку - переименование и удаление.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AssetLibraryDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val all = remember { vm.assetLibrary() }
    val hidden = remember { vm.assetServiceCount() }
    var folders by remember { mutableStateOf(vm.libraryFolders()) }
    var showUnused by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<com.vibetoon.domain.project.AssetCatalog.Type?>(null) }
    var userFolder by remember { mutableStateOf<String?>(null) }
    var nameDialog by remember { mutableStateOf<String?>(null) }    // "" - новая папка, иначе имя переименуемой
    var folderMenu by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lang = com.vibetoon.ui.theme.LocalAppSettings.current?.effectiveLang
    fun toast(ru: String) = android.widget.Toast.makeText(ctx, com.vibetoon.ui.i18n.translate(ctx, lang, ru), android.widget.Toast.LENGTH_SHORT).show()
    fun save(next: com.vibetoon.domain.project.LibraryFolders) { if (vm.saveLibraryFolders(next)) folders = next else toast("Не удалось сохранить папки библиотеки") }
    val uf = userFolder
    val assets = when {
        uf != null -> folders.filesIn(uf).toSet().let { inF -> all.filter { it.file in inF } }
        folder == null -> all
        else -> com.vibetoon.domain.project.AssetCatalog.folder(all, folder!!)
    }
    val inProject = assets.filter { it.kind == com.vibetoon.domain.project.AssetCatalog.Kind.IN_PROJECT }
    val unused = assets.filter { it.kind == com.vibetoon.domain.project.AssetCatalog.Kind.UNUSED }
    val typeFolders = listOf(
        null to "Все",
        com.vibetoon.domain.project.AssetCatalog.Type.IMAGE to "Картинки",
        com.vibetoon.domain.project.AssetCatalog.Type.VIDEO to "Видео",
        com.vibetoon.domain.project.AssetCatalog.Type.AUDIO to "Аудио",
        com.vibetoon.domain.project.AssetCatalog.Type.PSD to "PSD"
    )
    val pick: (String) -> Unit = { vm.insertAsset(it); onDismiss() }
    val move: (String, String?) -> Unit = { file, to -> save(folders.assign(file, to)) }
    com.vibetoon.ui.motion.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Библиотека ассетов") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((t, title) in typeFolders) {
                            val n = if (t == null) all.size else all.count { it.type == t }
                            androidx.compose.material3.FilterChip(selected = userFolder == null && folder == t, onClick = { folder = t; userFolder = null },
                                label = { Text(L(title) + " ($n)", fontSize = 12.sp, maxLines = 1) })
                        }
                        // пакет 23: пользовательские папки
                        for (name in folders.names) {
                            val n = folders.filesIn(name).count { f -> all.any { it.file == f } }
                            Box {
                                androidx.compose.material3.FilterChip(selected = userFolder == name, onClick = { userFolder = name; folder = null },
                                    leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) },
                                    label = { Text("$name ($n)", fontSize = 12.sp, maxLines = 1, modifier = Modifier.combinedClickable(onClick = { userFolder = name; folder = null }, onLongClick = { folderMenu = name })) })
                                DropdownMenu(expanded = folderMenu == name, onDismissRequest = { folderMenu = null }) {
                                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { folderMenu = null; nameDialog = name })
                                    DropdownMenuItem(text = { Text("Удалить папку (файлы останутся)") }, onClick = {
                                        folderMenu = null; save(folders.delete(name)); if (userFolder == name) userFolder = null
                                    })
                                }
                            }
                        }
                        androidx.compose.material3.AssistChip(onClick = { nameDialog = "" }, label = { Text("+ Папка", fontSize = 12.sp) })
                    }
                }
                if (uf != null) item { Text("Долгое нажатие на папку - переименовать или удалить; на файл - переложить", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else if (folders.names.isNotEmpty()) item { Text("Долгое нажатие на файл - положить в папку", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (assets.isEmpty()) item { Text(if (uf != null) "Папка пуста: долгое нажатие на файл в другой папке кладёт его сюда" else "Пока пусто: импортированные картинки, видео, аудио и рисунки слоёв появятся здесь") }
                if (inProject.isNotEmpty()) item { Text("В проекте", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                // спринт 55: строки по 3 считаются один раз, а не заново для каждой строки (было O(n²) на прокрутке)
                val projectRows = inProject.chunked(3)
                items(projectRows.size) { i -> AssetRow(vm, projectRows[i], folders, pick, move) }
                if (unused.isNotEmpty()) item {
                    androidx.compose.material3.TextButton(onClick = { showUnused = !showUnused }) {
                        Text(if (showUnused) "Скрыть файлы удалённых слоёв (${unused.size})" else "Файлы удалённых слоёв (${unused.size})", fontSize = 12.sp)
                    }
                }
                if (showUnused) { val unusedRows = unused.chunked(3); items(unusedRows.size) { i -> AssetRow(vm, unusedRows[i], folders, pick, move) } }
                if (hidden > 0) item { Text("Скрыто служебных файлов (кэш миниатюр, Liquify, кадры анимации): $hidden", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
    nameDialog?.let { old ->
        var text by remember(old) { mutableStateOf(old) }
        com.vibetoon.ui.motion.AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (old.isEmpty()) "Новая папка" else "Переименовать папку") },
            text = { androidx.compose.material3.OutlinedTextField(text, { text = it.take(com.vibetoon.domain.project.LibraryFolders.MAX_NAME) }, singleLine = true, label = { Text("Название") }) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val next = if (old.isEmpty()) folders.add(text) else folders.rename(old, text)
                    if (next == null) toast("Пустое название или такая папка уже есть")
                    else {
                        save(next); nameDialog = null
                        if (old.isEmpty()) { userFolder = next.names.last(); folder = null }
                        else if (userFolder == old) userFolder = com.vibetoon.domain.project.LibraryFolders.clean(text)
                    }
                }) { Text("Готово") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { nameDialog = null }) { Text("Отмена") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetRow(
    vm: MainViewModel, row: List<com.vibetoon.domain.project.AssetCatalog.Entry>, folders: com.vibetoon.domain.project.LibraryFolders,
    onPick: (String) -> Unit, onMove: (String, String?) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (e in row) {
            val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, e.rel) { value = vm.assetThumb(e.rel) }
            var menu by remember(e.file) { mutableStateOf(false) }
            val inFolder = folders.folderOf(e.file)
            Column(
                Modifier.width(84.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { onPick(e.rel) }, onLongClick = { menu = true }).padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(76.dp).background(androidx.compose.ui.graphics.Color(0xFFEFEFEF), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    val t = thumb
                    if (t != null) androidx.compose.foundation.Image(t, com.vibetoon.ui.i18n.L(e.title), Modifier.size(72.dp))
                    else when (e.type) {
                        com.vibetoon.domain.project.AssetCatalog.Type.AUDIO -> Icon(Icons.Default.MusicNote, L("Аудио"), Modifier.size(36.dp))
                        com.vibetoon.domain.project.AssetCatalog.Type.PSD -> Text("PSD", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        else -> Text("…", fontSize = 12.sp)
                    }
                    if (e.type == com.vibetoon.domain.project.AssetCatalog.Type.VIDEO) Icon(Icons.Default.PlayArrow, L("Видео"), Modifier.align(Alignment.BottomEnd).size(18.dp))
                    if (inFolder != null) Icon(Icons.Default.Folder, com.vibetoon.ui.i18n.L(inFolder), Modifier.align(Alignment.TopEnd).size(14.dp))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (folders.names.isEmpty()) DropdownMenuItem(text = { Text("Сначала создайте папку: «+ Папка»") }, onClick = { menu = false }, enabled = false)
                        for (name in folders.names) DropdownMenuItem(
                            text = { Text(if (name == inFolder) "✓ $name" else name, maxLines = 1) },
                            onClick = { menu = false; if (name != inFolder) onMove(e.file, name) })
                        if (inFolder != null) DropdownMenuItem(text = { Text("Убрать из папки") }, onClick = { menu = false; onMove(e.file, null) })
                    }
                }
                Text(e.title, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
