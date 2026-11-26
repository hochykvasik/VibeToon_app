package com.vibetoon.ui.projects

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.vibetoon.ui.motion.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.domain.project.ProjectMath
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.ui.AppViewModel
import kotlinx.coroutines.launch

/**
 * Библиотека проектов (ландшафт): сверху панель «+ Создать», Импорт, Экспорт, Справка, Новая папка;
 * ниже сетка карточек с превью. У карточки: меню «⋮» и свайп влево - Переименовать, Дублировать, Удалить.
 */
@Composable
fun ProjectsScreen(app: AppViewModel) {
    val allProjects by app.projects.collectAsStateWithLifecycle()
    val sizes by app.sizes.collectAsStateWithLifecycle()
    val storedFolders by app.folders.collectAsStateWithLifecycle()
    val starterId by app.starterId.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searchOn by remember { mutableStateOf(false) }
    // спринт 46: список прочитан (до этого - индикатор, а не «Проектов пока нет»), сортировка и свой порядок сохраняются
    val loaded by app.loaded.collectAsStateWithLifecycle()
    val sortBy by app.sort.collectAsStateWithLifecycle()
    val manual by app.manualOrder.collectAsStateWithLifecycle()
    /** Открытая папка (отдельная вкладка библиотеки); пусто - корень «Мои работы». */
    var folder by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<List<String>?>(null) }
    var backupFor by remember { mutableStateOf<String?>(null) }
    /** Спринт 46: выделение долгим нажатием (удалить / перенести несколько проектов). */
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingMany by remember { mutableStateOf<List<String>?>(null) }
    val folderNames = remember(storedFolders, allProjects) {
        com.vibetoon.domain.project.ProjectListMath.folders(storedFolders, allProjects)
    }
    // поиск - и по исходному имени, и по показанному (переведённому): «New project 2» находится по «new»
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lang = com.vibetoon.ui.theme.LocalAppSettings.current?.effectiveLang
    val projects = remember(allProjects, query, folder, sortBy, sizes, manual, lang) {
        com.vibetoon.domain.project.ProjectListMath.sort(
            com.vibetoon.domain.project.ProjectListMath.visible(allProjects, query, folder) { com.vibetoon.ui.i18n.translate(ctx, lang, it.name) },
            sortBy, sizes, manual
        )
    }
    val searching = query.isNotBlank()
    // папка исчезла (удалена) - назад в корень; выделение без удалённых проектов
    androidx.compose.runtime.LaunchedEffect(folderNames, folder) { if (folder.isNotEmpty() && folder !in folderNames) folder = "" }
    androidx.compose.runtime.LaunchedEffect(allProjects) { selected = com.vibetoon.domain.project.ProjectListMath.pruneSelection(selected, allProjects) }
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty() || folder.isNotEmpty()) {
        if (selected.isNotEmpty()) selected = emptySet() else folder = ""
    }
    // ---- спринт 46: перетаскивание карточек (свой порядок, перенос на карточку папки)
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var dragMoved by remember { mutableStateOf(false) }
    var dropFolder by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf<List<String>?>(null) }
    var expectSlot by remember { mutableStateOf<androidx.compose.ui.unit.IntOffset?>(null) }
    val byId = remember(allProjects) { allProjects.associateBy { it.id } }
    // спринт 52: счётчики и превью папок - один проход при изменении списка, а не сортировка на каждую папку в каждой
    // перерисовке сетки (перетаскивание карточки перерисовывает сетку на каждом кадре)
    val folderCounts = remember(allProjects) { com.vibetoon.domain.project.ProjectListMath.folderCounts(allProjects) }
    val folderPreviews = remember(allProjects, sortBy, sizes, manual) { com.vibetoon.domain.project.ProjectListMath.folderPreviews(allProjects, sortBy, sizes, manual) }
    val shown = live?.mapNotNull { byId[it] } ?: projects
    androidx.compose.runtime.LaunchedEffect(projects) { if (dragId == null) live = null }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // обработчик жеста создаётся один раз на карточку: текущий список и поиск - через rememberUpdatedState
    val projectsNow by androidx.compose.runtime.rememberUpdatedState(projects)
    val searchingNow by androidx.compose.runtime.rememberUpdatedState(searching)
    fun hover() {
        val id = dragId ?: return
        val info = grid.layoutInfo.visibleItemsInfo
        val me = info.firstOrNull { it.key == id } ?: return
        val slot = expectSlot
        if (slot != null) {   // ждём раскладку после прошлой перестановки
            if (me.offset != slot) return
            expectSlot = null
        }
        val cx = me.offset.x + me.size.width / 2f + dragOffset.x
        val cy = me.offset.y + me.size.height / 2f + dragOffset.y
        val hit = info.firstOrNull { it.key != id && cx >= it.offset.x && cx < it.offset.x + it.size.width && cy >= it.offset.y && cy < it.offset.y + it.size.height }
        val key = hit?.key as? String
        dropFolder = key?.takeIf { it.startsWith(FOLDER_KEY) }?.removePrefix(FOLDER_KEY)
        // спринт 47: внутри папки шапка («Мои работы / папка») - зона «убрать из папки»: карточку можно унести вверх
        if (folder.isNotEmpty()) {
            val head = info.firstOrNull { it.key == HEADER_KEY }
            val overHead = com.vibetoon.domain.project.ProjectListMath.overTopDropZone(cy, head?.offset?.y, head?.size?.height)
            if (overHead) { dropFolder = ""; return }
        }
        if (hit == null || key == null || dropFolder != null || searchingNow) return
        val cur = live ?: return
        if (key !in cur) return
        val next = com.vibetoon.domain.project.ProjectListMath.move(cur, id, key)
        if (next == cur) return
        // карточка встаёт на место цели: смещение пальца пересчитывается, чтобы она не прыгала
        dragOffset += androidx.compose.ui.geometry.Offset((me.offset.x - hit.offset.x).toFloat(), (me.offset.y - hit.offset.y).toFloat())
        expectSlot = hit.offset
        live = next
    }
    fun endDrag(cancel: Boolean) {
        val id = dragId ?: return
        val target = dropFolder
        val order = live
        dragId = null; dropFolder = null; expectSlot = null; dragOffset = androidx.compose.ui.geometry.Offset.Zero
        when {
            cancel -> live = null
            !dragMoved -> { live = null; selected = com.vibetoon.domain.project.ProjectListMath.toggle(selected, id) }   // долгое нажатие без движения - выделение
            target != null -> {
                live = null
                val ids = if (id in selected) selected.toList() else listOf(id)
                app.moveToFolder(ids, target)
                selected = emptySet()
            }
            order != null && order != projectsNow.map { it.id } -> app.reorder(order)   // live сбросится, когда придёт новый порядок
            else -> live = null
        }
    }
    val dragHandlers: (String) -> Modifier = { id ->
        Modifier.pointerInput(id) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    dragId = id; dragMoved = false; dragOffset = androidx.compose.ui.geometry.Offset.Zero
                    live = projectsNow.map { it.id }
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragOffset += amount
                    if (!dragMoved && dragOffset.getDistance() > 12f * density) dragMoved = true
                    if (dragMoved) hover()
                },
                onDragEnd = { endDrag(false) },
                onDragCancel = { endDrag(true) }
            )
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.importBackup(uri)
    }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = backupFor
        if (uri != null && id != null) app.exportBackup(id, uri)
        backupFor = null
    }
    var showNew by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) com.vibetoon.ui.shell.SettingsDialog(null) { showSettings = false }
    // пакет 13: окно импорта проекта - ход, итог (открыть / отменить импорт) или понятная ошибка
    val importState by app.importState.collectAsStateWithLifecycle()
    when (val st = importState) {
        com.vibetoon.ui.ImportUi.Busy -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Импорт проекта…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Файл распаковывается и проверяется. Существующие проекты не меняются.", fontSize = 13.sp)
                }
            },
            confirmButton = {}
        )
        is com.vibetoon.ui.ImportUi.Done -> AlertDialog(
            onDismissRequest = { app.dismissImport() },
            title = { Text("Проект импортирован") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text("«" + st.name + "»", fontWeight = FontWeight.Bold)
                    if (st.app.isNotEmpty()) Text(com.vibetoon.ui.i18n.L("Импортировано из: ${com.vibetoon.ui.i18n.L(st.app)}"), fontSize = 13.sp)
                    if (st.renamed) Text("Проект с таким именем уже есть - импортированный назван по-другому, ваш проект не изменён.", fontSize = 13.sp)
                    if (st.missing.isEmpty()) Text("Все файлы проекта на месте.", fontSize = 13.sp)
                    else {
                        Text(com.vibetoon.ui.i18n.L("Не найдено файлов: ${st.missing.size}. Эти слои будут пустыми; данные (ключи, кости, эффекты) сохранены."), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        Text(st.missing.take(6).joinToString("\n") { it.substringAfterLast('/') } + if (st.missing.size > 6) "\n…" else "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    if (st.notes.isNotEmpty()) {
                        Text("Не перенесено или упрощено:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(st.notes.take(12).map { "• " + com.vibetoon.ui.i18n.L(it) }.joinToString("\n") + if (st.notes.size > 12) "\n…" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                    }
                    if (st.fontsRestored > 0) Text(com.vibetoon.ui.i18n.L("Добавлено шрифтов: ${st.fontsRestored}"), fontSize = 13.sp)
                    if (st.fontsMissing.isNotEmpty()) Text(com.vibetoon.ui.i18n.L("Нет шрифтов: ${st.fontsMissing.joinToString()} - текст покажется шрифтом по умолчанию"), fontSize = 13.sp)
                    Text("Отменить импорт - удалить этот проект; остальные проекты не затрагиваются.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            },
            confirmButton = { TextButton({ app.openImported(st.id) }) { Text("Открыть") } },
            dismissButton = {
                Row {
                    TextButton({ app.undoImport(st.id) }) { Text("Отменить импорт") }
                    TextButton({ app.dismissImport() }) { Text("Закрыть") }
                }
            }
        )
        is com.vibetoon.ui.ImportUi.Failed -> AlertDialog(
            onDismissRequest = { app.dismissImport() },
            title = { Text("Импорт не удался") },
            text = { Text(com.vibetoon.ui.i18n.L(st.message) + "\n\n" + com.vibetoon.ui.i18n.L("Существующие проекты не изменены.")) },
            confirmButton = { TextButton({ app.dismissImport(); importLauncher.launch(arrayOf("*/*")) }) { Text("Выбрать другой файл") } },
            dismissButton = { TextButton({ app.dismissImport() }) { Text("Закрыть") } }
        )
        null -> {}
    }
    var renaming by remember { mutableStateOf<ProjectMeta?>(null) }
    var deleting by remember { mutableStateOf<ProjectMeta?>(null) }
    var article by remember { mutableStateOf<Tutorial?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { app.note.collect { m -> snack.currentSnackbarData?.dismiss(); snack.showSnackbar(m) } }
    val stub: (String) -> Unit = { what ->
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar("$what — в разработке")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) { data -> androidx.compose.material3.Snackbar { Text(data.visuals.message) } } },
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (selected.isNotEmpty()) {
                        // спринт 46: режим выделения (долгое нажатие): перенос в папку и удаление нескольких проектов
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, com.vibetoon.ui.i18n.L("Снять выделение")) }
                        Text(com.vibetoon.ui.i18n.L("Выбрано: ${selected.size}"), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton({ selected = selected + shown.map { it.id } }) { Text("Выбрать все", maxLines = 1) }
                        TextButton({ moving = selected.toList() }) {
                            Icon(Icons.Default.Folder, null, Modifier.size(18.dp)); Text("В папку", Modifier.padding(start = 4.dp), maxLines = 1)
                        }
                        // спринт 47: внутри папки - сразу вынести выбранные проекты в «Мои работы»
                        if (folder.isNotEmpty()) TextButton({ app.moveToFolder(selected.toList(), ""); selected = emptySet() }) { Text("Убрать из папки", maxLines = 1) }
                        TextButton({ deletingMany = selected.toList() }) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Text("Удалить", Modifier.padding(start = 4.dp), maxLines = 1, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                    Text("Ваши проекты", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showNew = true }) { Icon(Icons.Default.Add, com.vibetoon.ui.i18n.L("Создать проект")) }
                    if (searchOn) androidx.compose.material3.OutlinedTextField(
                        query, { query = it }, singleLine = true, placeholder = { Text("Поиск") },
                        modifier = Modifier.width(220.dp).height(52.dp)
                    )
                    IconButton(onClick = { searchOn = !searchOn; if (!searchOn) query = "" }) { Icon(Icons.Default.Search, com.vibetoon.ui.i18n.L("Поиск")) }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.SwapVert, com.vibetoon.ui.i18n.L("Сортировка")) }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            // спринт 46: дата и размер в обе стороны, имя, свой порядок (перетаскивание карточек)
                            for (s in com.vibetoon.domain.project.ProjectSort.values())
                                DropdownMenuItem(text = { Text((if (sortBy == s) "✓ " else "") + com.vibetoon.ui.i18n.L(com.vibetoon.domain.project.ProjectListMath.sortLabel(s))) }, onClick = { app.setSort(s); sortMenu = false })
                        }
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Download, com.vibetoon.ui.i18n.L("Импорт")) }
                    Box {
                        IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.Upload, com.vibetoon.ui.i18n.L("Экспорт")) }
                        DropdownMenu(exportMenu, { exportMenu = false }) {
                            if (allProjects.isEmpty()) DropdownMenuItem(text = { Text("Проектов пока нет") }, onClick = { exportMenu = false })
                            for (p in allProjects) DropdownMenuItem(text = { Text("Копия .tsproj: ${p.name}", maxLines = 1) }, onClick = { exportMenu = false; backupFor = p.id; exportLauncher.launch("${p.name}.tsproj") })
                            DropdownMenuItem(text = { Text("Очистить кэш и лишние файлы") }, onClick = { exportMenu = false; app.cleanUp() })
                        }
                    }
                    IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, com.vibetoon.ui.i18n.L("Создать папку")) }
                    IconButton(onClick = { article = TUTORIALS.firstOrNull() }) { Icon(Icons.Default.HelpOutline, com.vibetoon.ui.i18n.L("Справка")) }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, com.vibetoon.ui.i18n.L("Настройки")) }
                    }
                }
            }
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 200.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Явная рекомендация не зависит от поиска; спринт 46: пока список читается, баннер не прячется
                val starterShown = starterId != null && (!loaded || allProjects.any { it.id == starterId })
                if (folder.isEmpty() && starterShown) item(span = { GridItemSpan(maxLineSpan) }, key = "starter") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Рекомендуем начать с учебного проекта", fontWeight = FontWeight.SemiBold)
                                Text("Готовые фигуры и подсказки помогут освоить редактор.", fontSize = 12.sp)
                            }
                            androidx.compose.material3.Button(onClick = { app.startLesson(WELCOME_TOUR.coachSession()) }) { Text("Начать урок") }
                        }
                    }
                }
                // раздел «Обучение»: карточки статей (открываются на весь экран)
                if (folder.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "tutorials") { TutorialRow(onOpen = { article = it }) }
                item(span = { GridItemSpan(maxLineSpan) }, key = HEADER_KEY) {
                    if (folder.isEmpty()) Text("Мои работы", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    else Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // спринт 46: папка открыта отдельной вкладкой: назад, имя, число проектов, удаление папки
                        IconButton(onClick = { folder = ""; selected = emptySet() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, com.vibetoon.ui.i18n.L("Назад")) }
                        Text("Мои работы", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.clickable { folder = ""; selected = emptySet() })
                        Text("  /  ", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Icon(Icons.Default.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(folder, Modifier.padding(start = 4.dp).weight(1f, fill = false), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("  (${projects.size})", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Box(Modifier.weight(1f))
                        TextButton({ app.deleteFolder(folder); folder = "" }) { Text("Удалить папку") }
                    }
                    // спринт 47: во время переноса карточки шапка подсвечивается как зона «убрать из папки» (поверх, без сдвига сетки)
                    if (dragId != null && dragMoved) {
                        val hot = dropFolder == ""
                        Surface(
                            Modifier.matchParentSize(), shape = RoundedCornerShape(10.dp),
                            color = if (hot) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                            border = androidx.compose.foundation.BorderStroke(if (hot) 2.dp else 1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (hot) 1f else 0.5f))
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp).graphicsLayer { rotationZ = 90f })
                                Text(if (hot) "Отпустите - проект вернётся в «Мои работы»" else "Перетащите сюда, чтобы убрать из папки", Modifier.padding(start = 6.dp), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    }
                }
                // спринт 46: папки - карточки с превью проектов (как папки iOS); на карточку можно перетащить проект
                if (folder.isEmpty() && !searching) items(folderNames, key = { FOLDER_KEY + it }) { f ->
                    FolderCard(
                        f, folderCounts[f] ?: 0,
                        folderPreviews[f].orEmpty(),
                        app, dropTarget = dropFolder == f,
                        onOpen = { folder = f; selected = emptySet() },
                        onDelete = { app.deleteFolder(f) },
                        modifier = Modifier.animateItem()
                    )
                }
                if (!loaded) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                } else if (shown.isEmpty() && (folder.isNotEmpty() || searching || folderNames.isEmpty())) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        Text(
                            when {
                                searching -> "Ничего не найдено"
                                folder.isNotEmpty() -> "Папка пуста. Перетащите сюда проект (долгое нажатие и перенос на папку) или выделите проекты и нажмите «В папку»."
                                else -> "Проектов пока нет. Нажмите «+» сверху, чтобы создать первый."
                            },
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 14.sp
                        )
                    }
                }
                items(shown, key = { it.id }) { p ->
                    val dragging = dragId == p.id
                    val itemMod = if (dragging) Modifier.zIndex(1f).graphicsLayer {
                        translationX = dragOffset.x; translationY = dragOffset.y; scaleX = 1.04f; scaleY = 1.04f; alpha = 0.92f
                    } else Modifier.animateItem()
                    ProjectCard(
                        p, app,
                        onOpen = { if (selected.isNotEmpty()) selected = com.vibetoon.domain.project.ProjectListMath.toggle(selected, p.id) else app.open(p.id) },
                        onRename = { renaming = p },
                        onDuplicate = { app.duplicate(p.id) },
                        onDelete = { deleting = p },
                        onMove = { moving = listOf(p.id) },
                        onUnfolder = if (p.folder.isNotEmpty()) ({ app.moveToFolder(listOf(p.id), "") }) else null,
                        onBackup = { backupFor = p.id; exportLauncher.launch("${p.name}.tsproj") },
                        sizeText = sizes[p.id]?.let { com.vibetoon.domain.project.ProjectListMath.sizeLabel(it) },
                        selected = p.id in selected, selectionMode = selected.isNotEmpty(),
                        modifier = itemMod, cardModifier = dragHandlers(p.id),
                        showFolder = searching && folder.isEmpty()
                    )
                }
            }
        }
    }
    article?.let { t ->
        TutorialDetailScreen(t, onClose = { article = null }, onShowInEditor = {
            val p = allProjects.firstOrNull()
            article = null
            if (t.lesson || p == null) {
                // Даже при пустой библиотеке любой урок можно пройти на готовом учебном проекте.
                app.startLesson(t.coachSession())
            } else {
                com.vibetoon.ui.shell.Coach.start(t.coachSession())
                app.open(p.id)
            }
        })
    }

    if (showNew) {
        NewProjectSheet(
            defaultName = "Новый проект ${allProjects.size + 1}",
            onDismiss = { showNew = false },
            onCreate = { spec -> showNew = false; app.create(spec) }
        )
    }
    if (newFolder) {
        var fname by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("Создать папку") },
            text = { OutlinedTextField(fname, { fname = it }, singleLine = true, label = { Text("Название") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { app.addFolder(fname); newFolder = false })) },
            confirmButton = { TextButton({ app.addFolder(fname); newFolder = false }) { Text("Создать") } },
            dismissButton = { TextButton({ newFolder = false }) { Text("Отмена") } }
        )
    }
    moving?.let { ids ->
        // спринт 46: перенос одного или нескольких проектов; новую папку можно создать прямо здесь
        var fresh by remember(ids) { mutableStateOf("") }
        val done = { f: String -> app.moveToFolder(ids, f); moving = null; selected = emptySet() }
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text(if (ids.size == 1) "В папку" else com.vibetoon.ui.i18n.L("В папку (проектов: ${ids.size})")) },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    TextButton({ done("") }) { Text("Без папки") }
                    for (f in folderNames) TextButton({ done(f) }) { Icon(Icons.Default.Folder, null, Modifier.size(18.dp)); Text(f, Modifier.padding(start = 4.dp)) }
                    OutlinedTextField(fresh, { fresh = it }, singleLine = true, label = { Text("Новая папка") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                            val n = com.vibetoon.domain.project.ProjectListMath.cleanFolderName(fresh); if (n.isNotEmpty()) done(n)
                        }))
                }
            },
            confirmButton = {
                TextButton({ val n = com.vibetoon.domain.project.ProjectListMath.cleanFolderName(fresh); if (n.isNotEmpty()) done(n) }, enabled = fresh.isNotBlank()) { Text("Создать и перенести") }
            },
            dismissButton = { TextButton({ moving = null }) { Text("Отмена") } }
        )
    }
    deletingMany?.let { ids ->
        AlertDialog(
            onDismissRequest = { deletingMany = null },
            title = { Text(com.vibetoon.ui.i18n.L("Удалить проекты (${ids.size})?")) },
            text = { Text("Выбранные проекты и все их файлы будут удалены без возможности восстановления. Сначала сохраните нужные копии .tsproj.") },
            confirmButton = { TextButton({ app.deleteMany(ids); deletingMany = null; selected = emptySet() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deletingMany = null }) { Text("Отмена") } }
        )
    }
    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Переименовать проект") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ app.rename(p.id, text.trim()); renaming = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Отмена") } }
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить проект?") },
            text = { Text("«${p.name}» и все его файлы будут удалены без возможности восстановления.") },
            confirmButton = { TextButton({ app.delete(p.id); deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Отмена") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCard(
    p: ProjectMeta, app: AppViewModel,
    onOpen: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
    onMove: () -> Unit = {}, onBackup: () -> Unit = {}, sizeText: String? = null, onUnfolder: (() -> Unit)? = null,
    selected: Boolean = false, selectionMode: Boolean = false,
    modifier: Modifier = Modifier, cardModifier: Modifier = Modifier, showFolder: Boolean = false
) {
    var menu by remember { mutableStateOf(false) }
    // Декодирование и обращение к диску не должны задерживать открытие списка на UI-потоке.
    val thumb = rememberThumb(app, p)
    // Свайп влево открывает меню действий, карточка возвращается на место
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) menu = true
        false
    })
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Edit, com.vibetoon.ui.i18n.L("Переименовать"))
                Icon(Icons.Default.ContentCopy, com.vibetoon.ui.i18n.L("Дублировать"))
                Icon(Icons.Default.Delete, com.vibetoon.ui.i18n.L("Удалить"))
            }
        }
    ) {
        Card(
            // спринт 46: долгое нажатие - выделение, долгое нажатие и перенос - порядок или папка (обработчик глубже клика)
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).then(cardModifier),
            colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
        ) {
            Column {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumb != null) {
                        Image(thumb, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("Нет превью", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    if (selectionMode) Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        com.vibetoon.ui.i18n.L(if (selected) "Выбран" else "Не выбран"),
                        Modifier.align(Alignment.TopStart).padding(6.dp).size(26.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Row(Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${ProjectMath.ratioLabel(p.width, p.height)} · ${p.fps} fps · %.1f c".format(ProjectMath.durationSeconds(p.durationFrames, p.fps)) + (sizeText?.let { " · $it" } ?: "") + (if (showFolder && p.folder.isNotEmpty()) " · ${p.folder}" else ""),
                            fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, com.vibetoon.ui.i18n.L("Действия")) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Дублировать") }, onClick = { menu = false; onDuplicate() })
                            DropdownMenuItem(text = { Text("В папку") }, onClick = { menu = false; onMove() })
                            if (onUnfolder != null) DropdownMenuItem(text = { Text("Убрать из папки") }, onClick = { menu = false; onUnfolder() })   // спринт 47
                            DropdownMenuItem(text = { Text("Резервная копия .tsproj") }, onClick = { menu = false; onBackup() })
                            DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
        }
    }
}

/** Спринт 46: ключ карточки папки в сетке (id проектов - 8 символов, с префиксом не совпадают). */
private const val FOLDER_KEY = "folder:"
private const val HEADER_KEY = "header"

/** Превью проекта: декодируется на IO, не задерживает список. [sample] - уменьшение (2 - для мини-превью в папке). */
@Composable
private fun rememberThumb(app: AppViewModel, p: ProjectMeta, sample: Int = 1): androidx.compose.ui.graphics.ImageBitmap? {
    // спринт 52: превью из памяти приложения - карточка, переехавшая в папку или обратно, показывается сразу, без
    // повторного декодирования PNG; с диска - только первый раз (на IO)
    val key = "${p.id}:${p.updatedAt}:$sample"
    return androidx.compose.runtime.produceState(app.cachedThumb(key), key, app) {
        if (value != null) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val f = app.thumbnailFile(p.id)
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() else null
            }.getOrNull()
        }?.also { app.putThumb(key, it) }
    }.value
}

/**
 * Спринт 46: карточка папки как папка iOS: превью до 4 проектов сеткой 2×2, имя и число проектов. Тап открывает папку
 * отдельной вкладкой; на карточку можно перетащить проект (подсветка - [dropTarget]).
 */
@Composable
private fun FolderCard(
    name: String, count: Int, preview: List<ProjectMeta>, app: AppViewModel, dropTarget: Boolean,
    onOpen: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = if (dropTarget) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        border = if (dropTarget) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column {
            Column(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(8.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (row in 0 until 2) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (col in 0 until 2) {
                        val p = preview.getOrNull(row * 2 + col)
                        Box(
                            Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(6.dp))
                                .background(if (p != null) MaterialTheme.colorScheme.surface else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            if (p != null) {
                                val t = rememberThumb(app, p, sample = 2)
                                if (t != null) Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(start = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(com.vibetoon.ui.i18n.L("Проектов: $count"), fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, com.vibetoon.ui.i18n.L("Действия")) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Открыть") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Удалить папку (проекты останутся)") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
