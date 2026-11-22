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
import androidx.compose.material3.AlertDialog
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
    var query by remember { mutableStateOf("") }
    var searchOn by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(com.vibetoon.domain.project.ProjectSort.DATE) }
    var folder by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<ProjectMeta?>(null) }
    var backupFor by remember { mutableStateOf<String?>(null) }
    val folderNames = com.vibetoon.domain.project.ProjectListMath.folders(storedFolders, allProjects)
    val projects = com.vibetoon.domain.project.ProjectListMath.sort(
        com.vibetoon.domain.project.ProjectListMath.filter(allProjects, query, folder), sortBy, sizes
    )
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
        snackbarHost = { SnackbarHost(snack) },
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
                    Text("Ваши проекты", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showNew = true }) { Icon(Icons.Default.Add, "Создать проект") }
                    if (searchOn) androidx.compose.material3.OutlinedTextField(
                        query, { query = it }, singleLine = true, placeholder = { Text("Поиск") },
                        modifier = Modifier.width(220.dp).height(52.dp)
                    )
                    IconButton(onClick = { searchOn = !searchOn; if (!searchOn) query = "" }) { Icon(Icons.Default.Search, "Поиск") }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.SwapVert, "Сортировка") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            for ((s, t) in listOf(com.vibetoon.domain.project.ProjectSort.DATE to "По дате", com.vibetoon.domain.project.ProjectSort.NAME to "По имени", com.vibetoon.domain.project.ProjectSort.SIZE to "По размеру"))
                                DropdownMenuItem(text = { Text((if (sortBy == s) "✓ " else "") + t) }, onClick = { sortBy = s; sortMenu = false })
                        }
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Download, "Импорт") }
                    Box {
                        IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.Upload, "Экспорт") }
                        DropdownMenu(exportMenu, { exportMenu = false }) {
                            if (allProjects.isEmpty()) DropdownMenuItem(text = { Text("Проектов пока нет") }, onClick = { exportMenu = false })
                            for (p in allProjects) DropdownMenuItem(text = { Text("Копия .tsproj: ${p.name}", maxLines = 1) }, onClick = { exportMenu = false; backupFor = p.id; exportLauncher.launch("${p.name}.tsproj") })
                            DropdownMenuItem(text = { Text("Очистить кэш и лишние файлы") }, onClick = { exportMenu = false; app.cleanUp() })
                        }
                    }
                    IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, "Создать папку") }
                    IconButton(onClick = { stub("Справка") }) { Icon(Icons.Default.HelpOutline, "Справка") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Настройки") }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 200.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // раздел «Обучение»: карточки статей (открываются на весь экран)
                item(span = { GridItemSpan(maxLineSpan) }) { TutorialRow(onOpen = { article = it }) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Мои работы", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                if (folderNames.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(selected = folder.isEmpty(), onClick = { folder = "" }, label = { Text("Все") })
                        for (f in folderNames) androidx.compose.material3.FilterChip(
                            selected = folder == f, onClick = { folder = if (folder == f) "" else f },
                            label = { Text(f, maxLines = 1) },
                            trailingIcon = if (folder == f) { { Icon(Icons.Default.Close, "Удалить папку", Modifier.size(16.dp).clickable { app.deleteFolder(f); folder = "" }) } } else null
                        )
                    }
                }
                if (projects.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Проектов пока нет. Нажмите «+» сверху, чтобы создать первый.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 14.sp
                        )
                    }
                }
                items(projects, key = { it.id }) { p ->
                    ProjectCard(
                        p, app,
                        onOpen = { app.open(p.id) },
                        onRename = { renaming = p },
                        onDuplicate = { app.duplicate(p.id) },
                        onDelete = { deleting = p },
                        onMove = { moving = p },
                        onBackup = { backupFor = p.id; exportLauncher.launch("${p.name}.tsproj") },
                        sizeText = sizes[p.id]?.let { com.vibetoon.domain.project.ProjectListMath.sizeLabel(it) }
                    )
                }
            }
        }
    }
    article?.let { t ->
        TutorialDetailScreen(t, onClose = { article = null }, onShowInEditor = {
            val p = projects.firstOrNull()
            if (p == null) {
                scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar("Сначала создайте проект («+» сверху), затем нажмите «Пройти в редакторе»") }
            } else {
                if (t.lesson) {
                    article = null
                    app.startLesson(t.coachSession())   // свой учебный проект с фигурами
                } else {
                    com.vibetoon.ui.shell.Coach.start(t.coachSession())
                    article = null
                    app.open(p.id)
                }
            }
        })
    }

    if (showNew) {
        NewProjectSheet(
            defaultName = "Новый проект ${projects.size + 1}",
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
    moving?.let { p ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("В папку") },
            text = {
                Column {
                    TextButton({ app.moveToFolder(p.id, ""); moving = null }) { Text("Без папки") }
                    for (f in folderNames) TextButton({ app.moveToFolder(p.id, f); moving = null }) { Text(f) }
                    if (folderNames.isEmpty()) Text("Папок пока нет: создайте её кнопкой сверху.", fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton({ moving = null }) { Text("Отмена") } }
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
    onMove: () -> Unit = {}, onBackup: () -> Unit = {}, sizeText: String? = null
) {
    var menu by remember { mutableStateOf(false) }
    // Миниатюра перечитывается, когда проект меняется (updatedAt)
    val thumb = remember(p.id, p.updatedAt) {
        runCatching {
            val f = app.thumbnailFile(p.id)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
        }.getOrNull()
    }
    // Свайп влево открывает меню действий, карточка возвращается на место
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) menu = true
        false
    })
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Edit, "Переименовать")
                Icon(Icons.Default.ContentCopy, "Дублировать")
                Icon(Icons.Default.Delete, "Удалить")
            }
        }
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                }
                Row(Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${ProjectMath.ratioLabel(p.width, p.height)} · ${p.fps} fps · %.1f c".format(ProjectMath.durationSeconds(p.durationFrames, p.fps)) + (sizeText?.let { " · $it" } ?: "") + (if (p.folder.isNotEmpty()) " · ${p.folder}" else ""),
                            fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "Действия") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Дублировать") }, onClick = { menu = false; onDuplicate() })
                            DropdownMenuItem(text = { Text("В папку") }, onClick = { menu = false; onMove() })
                            DropdownMenuItem(text = { Text("Резервная копия .tsproj") }, onClick = { menu = false; onBackup() })
                            DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
        }
    }
}
