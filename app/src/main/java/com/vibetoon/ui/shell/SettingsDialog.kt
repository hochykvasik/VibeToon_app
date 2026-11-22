package com.vibetoon.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetoon.ui.MainViewModel

/** Тип пункта настроек: переключатель, кнопка-действие или строка с информацией. */
private sealed interface Item { val title: String }
private class Toggle(override val title: String) : Item
private class Action(override val title: String) : Item
private class Info(override val title: String, val value: String) : Item

private fun toggles(vararg t: String): List<Item> = t.map { Toggle(it) }

private val APPEARANCE = "Внешний вид"

/** Русские подписи пунктов (ключи с английскими именами нужны логике); английский вариант возвращает таблица перевода. */
private val RU: Map<String, String> = mapOf(
    "General" to "Общие",
    "Tools" to "Инструменты",
    "Import" to "Импорт",
    "Display" to "Экран",
    "Gestures" to "Жесты",
    "Keyboard" to "Клавиатура",
    "Backup" to "Резервные копии",
    "Project Info" to "О проекте",
    "Flip Canvas (горизонтально)" to "Отразить холст по горизонтали",
    "Flip Canvas (вертикально)" to "Отразить холст по вертикали",
    "Play Audio While Scrubbing" to "Звук при протяжке по линейке",
    "Quick Layer Delete: рисунок (иначе анимация)" to "Быстрое удаление: рисунок (иначе анимация)",
    "Follow Camera" to "Следовать за камерой",
    "Reset Frame After Pause" to "Вернуть кадр после паузы",
    "Forward/Backward: кадры (иначе рисунки)" to "Вперёд/назад: кадры (иначе рисунки)",
    "Vector Workflow" to "Векторный режим работы",
    "Auto-Add Keyframes" to "Автодобавление ключей",
    "Layer Pivot Editing" to "Правка опорной точки слоя",
    "Temporary Pivots" to "Временные опорные точки",
    "Show Motion Path" to "Показывать траекторию движения",
    "Quick Selection" to "Быстрое выделение",
    "Auto Pixel Edit Mode" to "Авто-режим правки пикселей",
    "Manually Close Selections" to "Замыкать выделение вручную",
    "Cursor Enabled" to "Курсор включён",
    "Cursor Hover Preview" to "Предпросмотр под курсором",
    "Only Erase/Smudge Selected Path Layers" to "Стирать/размазывать только выбранные слои-контуры",
    "Import Images as Symbols" to "Импортировать картинки как символы",
    "Compatible Video Export" to "Совместимый экспорт видео (если в видео полосы)",
    "Insert Images into Existing Drawing" to "Вставлять картинки в существующий рисунок",
    "PSD Import Mode: слои как в файле" to "Импорт PSD: слои как в файле",
    "Resolution Limit" to "Ограничение разрешения",
    "Import Layer Locked States" to "Переносить замки слоёв при импорте",
    "Import Text as Pixel Layer" to "Импортировать текст как пиксельный слой",
    "Left Handed" to "Режим левши",
    "Show Grid" to "Показывать сетку",
    "Show Symbol Context" to "Показывать контекст символа",
    "Symbol Context Opacity" to "Прозрачность контекста символа",
    "Checker Pattern in Thumbnails" to "Шахматка в миниатюрах",
    "Selection Mask Visibility" to "Видимость маски выделения",
    "Bones Colors" to "Цвета костей",
    "Reset" to "Сбросить",
    "Finger Drawing" to "Рисование пальцем",
    "Rotate Canvas 2 Fingers" to "Поворот холста двумя пальцами",
    "Pinch to Refocus" to "Щипок для смены фокуса",
    "Double Tap & Drag for Multi-select" to "Двойное касание и перетаскивание: мультивыбор",
    "Select Layers on Canvas" to "Выбор слоёв на холсте",
    "Touch Detection" to "Определение касаний",
    "2 Finger Tap Undo" to "Касание двумя пальцами: отмена",
    "3 Finger Tap Redo" to "Касание тремя пальцами: повтор",
    "Pipette (Long Press)" to "Пипетка (долгое нажатие)",
    "Shape Assist" to "Помощь с фигурами",
    "Import Backup" to "Импортировать копию",
    "Manual Backup" to "Резервная копия вручную"
)
private fun ru(t: String) = RU[t] ?: t

/** Переключатели, подключённые к логике (Sprint 6.8/6.9); остальные помечены «В разработке». */
private val WIRED = setOf(
    "Rotate Canvas 2 Fingers", "Layer Pivot Editing", "Temporary Pivots", "Auto Pixel Edit Mode",
    "Only Erase/Smudge Selected Path Layers", "Vector Workflow", "Import Images as Symbols",
    "Flip Canvas (горизонтально)", "Flip Canvas (вертикально)", "Compatible Video Export"
)

private val TABS: List<Pair<String, List<Item>>> = listOf(
    APPEARANCE to emptyList(),   // рабочая вкладка: язык, тема, акцентный цвет (остальные пункты - заготовки)
    "General" to toggles(
        "Flip Canvas (горизонтально)", "Flip Canvas (вертикально)", "Play Audio While Scrubbing",
        "Quick Layer Delete: рисунок (иначе анимация)", "Follow Camera", "Reset Frame After Pause",
        "Forward/Backward: кадры (иначе рисунки)", "Vector Workflow", "Auto-Add Keyframes", "Compatible Video Export"
    ),
    "Tools" to toggles(
        "Layer Pivot Editing", "Temporary Pivots", "Show Motion Path", "Quick Selection", "Auto Pixel Edit Mode",
        "Manually Close Selections", "Cursor Enabled", "Cursor Hover Preview", "Only Erase/Smudge Selected Path Layers"
    ),
    "Import" to toggles(
        "Import Images as Symbols", "Insert Images into Existing Drawing", "PSD Import Mode: слои как в файле",
        "Resolution Limit", "Import Layer Locked States", "Import Text as Pixel Layer"
    ),
    "Display" to listOf<Item>(
        Toggle("Left Handed"), Toggle("Show Grid"), Toggle("Show Symbol Context"),
        Toggle("Symbol Context Opacity"), Toggle("Checker Pattern in Thumbnails"), Toggle("Selection Mask Visibility"),
        Toggle("Bones Colors"), Action("Reset")
    ),
    "Gestures" to toggles(
        "Finger Drawing", "Rotate Canvas 2 Fingers", "Pinch to Refocus", "Double Tap & Drag for Multi-select",
        "Select Layers on Canvas", "Touch Detection", "2 Finger Tap Undo", "3 Finger Tap Redo",
        "Pipette (Long Press)", "Shape Assist"
    ),
    "Keyboard" to emptyList(),
    "Backup" to listOf<Item>(Action("Import Backup"), Action("Manual Backup")),
    "Project Info" to emptyList()
)

/**
 * Окно настроек: вкладки General, Tools, Import, Appearance, Gestures, Keyboard, Backup, Project Info.
 * Подключённые пункты ([WIRED], левша, жесты Undo/Redo) сохраняются в AppSettings; прочие - «В разработке».
 */
@Composable
fun SettingsDialog(vm: MainViewModel?, onDismiss: () -> Unit) {
    val stub = LocalStub.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val state = remember { mutableStateMapOf<String, Boolean>() }
    var canvasDialog by remember { mutableStateOf(0) }
    val flip = vm?.viewFlip?.collectAsState()?.value ?: (false to false)
    if (vm != null && canvasDialog != 0) com.vibetoon.ui.CanvasDialog(vm, canvasDialog == 2) { canvasDialog = 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.9f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Настройки") },
        text = {
            Column(Modifier.heightIn(max = 340.dp)) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    TABS.forEachIndexed { i, (title, _) -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(ru(title), fontSize = 13.sp, maxLines = 1, softWrap = false) }) }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (TABS[tab].first == APPEARANCE) {
                        com.vibetoon.ui.theme.LocalAppSettings.current?.let { st ->
                            com.vibetoon.ui.theme.AppearanceControls(st)
                            OutlinedButton(onClick = { st.reset() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Сбросить внешний вид", fontSize = 13.sp) }
                            Text("Язык интерфейса меняется сразу. Туториалы и подсказки пока на русском.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(top = 4.dp))
                        }
                        return@Column
                    }
                    if (TABS[tab].first == "Keyboard") {
                        com.vibetoon.ui.theme.LocalAppSettings.current?.let { st -> HotkeyEditor(st) }
                        return@Column
                    }
                    if (TABS[tab].first == "Project Info" && vm != null) {
                        val (cw, ch) = vm.canvasSizeNow()
                        Row(Modifier.fillMaxWidth()) {
                            Text("Разрешение", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("$cw × $ch", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        OutlinedButton(onClick = { canvasDialog = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Изменить размер холста (Resize)", fontSize = 13.sp) }
                        OutlinedButton(onClick = { canvasDialog = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Обрезать холст (Crop)", fontSize = 13.sp) }
                        Text("Resize масштабирует слои вместе с холстом, Crop оставляет выбранную рамку. Эти действия нельзя отменить через Undo.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                    for (item in TABS[tab].second) {
                        when (item) {
                            is Toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(ru(item.title), fontSize = 14.sp, modifier = Modifier.weight(1f))
                                val st = com.vibetoon.ui.theme.LocalAppSettings.current
                                if (item.title == "Left Handed" && st != null) Switch(checked = st.leftHanded, onCheckedChange = { st.leftHanded = it; st.save() })
                                else if (item.title == "2 Finger Tap Undo" && st != null) Switch(checked = st.tap2Undo, onCheckedChange = { st.tap2Undo = it; st.saveGestures() })
                                else if (item.title == "3 Finger Tap Redo" && st != null) Switch(checked = st.tap3Redo, onCheckedChange = { st.tap3Redo = it; st.saveGestures() })
                                else if (st != null && item.title in WIRED) {
                                    val on = when (item.title) {
                                        "Rotate Canvas 2 Fingers" -> st.rotateCanvas
                                        "Layer Pivot Editing" -> st.pivotEditing
                                        "Temporary Pivots" -> st.temporaryPivots
                                        "Auto Pixel Edit Mode" -> st.autoPixelEdit
                                        "Only Erase/Smudge Selected Path Layers" -> st.onlySelectedPath
                                        "Vector Workflow" -> st.vectorWorkflow
                                        "Import Images as Symbols" -> st.importAsSymbols
                                        "Compatible Video Export" -> st.compatExport
                                        "Flip Canvas (горизонтально)" -> flip.first
                                        "Flip Canvas (вертикально)" -> flip.second
                                        else -> false
                                    }
                                    Switch(checked = on, onCheckedChange = { v ->
                                        when (item.title) {
                                            "Rotate Canvas 2 Fingers" -> st.rotateCanvas = v
                                            "Layer Pivot Editing" -> st.pivotEditing = v
                                            "Temporary Pivots" -> st.temporaryPivots = v
                                            "Auto Pixel Edit Mode" -> st.autoPixelEdit = v
                                            "Only Erase/Smudge Selected Path Layers" -> st.onlySelectedPath = v
                                            "Vector Workflow" -> st.vectorWorkflow = v
                                            "Import Images as Symbols" -> st.importAsSymbols = v
                                            "Compatible Video Export" -> st.compatExport = v
                                            "Flip Canvas (горизонтально)" -> vm?.toggleViewFlipX()
                                            "Flip Canvas (вертикально)" -> vm?.toggleViewFlipY()
                                        }
                                        st.saveEditor()
                                    })
                                }
                                else Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("В разработке", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), modifier = Modifier.padding(end = 6.dp))
                                    Switch(checked = state[item.title] == true, onCheckedChange = { state[item.title] = it }, enabled = false)
                                }
                            }
                            is Action -> OutlinedButton(onClick = { stub(item.title) }, modifier = Modifier.fillMaxWidth()) { Text(ru(item.title), fontSize = 13.sp) }
                            is Info -> Row(Modifier.fillMaxWidth()) {
                                Text(ru(item.title), fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text(item.value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                    Text(
                        "Пункты с пометкой «В разработке» пока не действуют; остальные сохраняются и работают сразу.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Готово") } }
    )
}
