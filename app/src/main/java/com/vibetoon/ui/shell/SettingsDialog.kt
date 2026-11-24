package com.vibetoon.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vibetoon.ui.motion.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    "Compatible Video Export" to "Совместимый экспорт видео",
    "Performance HUD" to "Панель метрик производительности",
    "Quick Layer Delete: рисунок (иначе анимация)" to "Быстрое удаление: рисунок (иначе анимация)",
    "Follow Camera" to "Следовать за камерой",
    "Reset Frame After Pause" to "Вернуть кадр после паузы",
    "Forward/Backward: кадры (иначе рисунки)" to "Вперёд/назад: кадры (иначе рисунки)",
    "Vector Workflow" to "Векторный режим работы",
    "Auto-Add Keyframes" to "Автодобавление ключей",
    "Layer Pivot Editing" to "Правка опорной точки слоя",
    "Temporary Pivots" to "Временные опорные точки",
    "Show Motion Path" to "Показывать траекторию движения",
    "Quick Selection" to "Быстрый выбор слоя касанием",
    "Auto Pixel Edit Mode" to "Авто-режим правки пикселей",
    "Manually Close Selections" to "Замыкать выделение вручную",
    "Cursor Enabled" to "Курсор включён",
    "Cursor Hover Preview" to "Предпросмотр под курсором",
    "Only Erase/Smudge Selected Path Layers" to "Стирать/размазывать только выбранные слои-контуры",
    "Import Images as Symbols" to "Импортировать картинки как символы",
    "Insert Images into Existing Drawing" to "Вставлять картинки в существующий рисунок",
    "PSD Import Mode" to "Режим импорта PSD",
    "Resolution Limit" to "Ограничение разрешения",
    "Auto-Extend Project for Media" to "Продлевать проект под аудио и видео",
    "Background Import/Export" to "Импорт и экспорт в фоне",
    "Bone Chain Keys" to "Ключи всей цепочке костей",
    "Import Layer Locked States" to "Переносить замки слоёв при импорте",
    "Import Text as Pixel Layer" to "Импортировать текст как пиксельный слой",
    "Left Handed" to "Режим левши",
    "Show Grid" to "Показывать сетку",
    "Show Symbol Context" to "Показывать контекст символа",
    "Symbol Context Opacity" to "Прозрачность контекста символа",
    "Checker Pattern in Thumbnails" to "Шахматка в миниатюрах",
    "Selection Mask Visibility" to "Видимость маски выделения",
    "Bones Colors" to "Цвета костей",
    "Smooth UI Animations" to "Плавные анимации интерфейса",
    "Reset" to "Сбросить",
    "Finger Drawing" to "Рисование пальцем",
    "Rotate Canvas 2 Fingers" to "Поворот холста двумя пальцами",
    "Pinch to Refocus" to "Щипок для смены фокуса",
    "Double Tap & Drag for Multi-select" to "Двойное касание и перетаскивание: мультивыбор",
    "Select Layers on Canvas" to "Выбор слоёв на холсте",
    "Touch Detection" to "Определение касаний",
    "Stylus" to "Стилус",
    "2 Finger Tap Undo" to "Касание двумя пальцами: отмена",
    "3 Finger Tap Redo" to "Касание тремя пальцами: повтор",
    "Pipette (Long Press)" to "Пипетка (долгое нажатие)",
    "Shape Assist" to "Помощь с фигурами",
    "Import Backup" to "Импортировать копию",
    "Manual Backup" to "Резервная копия вручную"
)
private fun ru(t: String) = RU[t] ?: t

private val TABS: List<Pair<String, List<Item>>> = listOf(
    APPEARANCE to emptyList(),   // рабочая вкладка: язык, тема, акцентный цвет (остальные пункты - заготовки)
    "General" to toggles(
        "Flip Canvas (горизонтально)", "Flip Canvas (вертикально)", "Show Grid", "Play Audio While Scrubbing", "Compatible Video Export", "Performance HUD",
        "Quick Layer Delete: рисунок (иначе анимация)", "Follow Camera", "Reset Frame After Pause",
        "Forward/Backward: кадры (иначе рисунки)", "Vector Workflow", "Auto-Add Keyframes", "Bone Chain Keys",
        "Background Import/Export"
    ),
    "Tools" to toggles(
        "Layer Pivot Editing", "Temporary Pivots", "Show Motion Path", "Quick Selection", "Auto Pixel Edit Mode",
        "Manually Close Selections", "Cursor Enabled", "Cursor Hover Preview", "Only Erase/Smudge Selected Path Layers"
    ),
    "Import" to toggles(
        "Import Images as Symbols", "Insert Images into Existing Drawing",
        "Resolution Limit", "Import Layer Locked States", "Import Text as Pixel Layer", "Auto-Extend Project for Media"
    ),
    "Display" to listOf<Item>(
        Toggle("Left Handed"), Toggle("Show Grid"), Toggle("Show Symbol Context"),
        Toggle("Symbol Context Opacity"), Toggle("Checker Pattern in Thumbnails"), Toggle("Selection Mask Visibility"),
        Toggle("Bones Colors"), Toggle("Smooth UI Animations"), Action("Reset")
    ),
    "Gestures" to toggles(
        "Finger Drawing", "Rotate Canvas 2 Fingers", "Pinch to Refocus", "Double Tap & Drag for Multi-select",
        "Select Layers on Canvas", "Touch Detection", "2 Finger Tap Undo", "3 Finger Tap Redo",
        "Pipette (Long Press)", "Shape Assist"
    ),
    "Stylus" to emptyList(),   // Stylus sprint: кривая нажима, стабилизация 1–10, ластик на обратном конце пера
    "Keyboard" to emptyList(),
    "Backup" to listOf<Item>(Action("Import Backup"), Action("Manual Backup")),
    "Project Info" to emptyList()
)

/**
 * Окно настроек (каркас): вкладки General, Tools, Import, Appearance, Gestures, Keyboard, Backup, Project Info.
 * Значения пока не сохраняются и ничего не меняют: каждый пункт - заготовка под будущую логику.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(vm: MainViewModel?, onDismiss: () -> Unit) {
    val stub = LocalStub.current
    var tab by rememberSaveable { mutableStateOf(0) }
    // пакет 10: сброс отображения и резервная копия настроек
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val appSt = com.vibetoon.ui.theme.LocalAppSettings.current
    fun notify(ru: String) {
        android.widget.Toast.makeText(ctx, com.vibetoon.ui.i18n.translate(ctx, appSt?.effectiveLang, ru), android.widget.Toast.LENGTH_SHORT).show()
    }
    val backupOut = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && appSt != null) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(appSt.backupJson().toByteArray()) } != null }.getOrDefault(false)
            notify(if (ok) "Копия настроек сохранена" else "Не удалось сохранить копию настроек")
        }
    }
    val backupIn = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && appSt != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            notify(if (text != null && appSt.importBackup(text)) "Настройки восстановлены из копии" else "Это не файл настроек VibeToon")
        }
    }
    var canvasDialog by remember { mutableStateOf(0) }
    if (vm != null && canvasDialog != 0) com.vibetoon.ui.CanvasDialog(vm, canvasDialog == 2) { canvasDialog = 0 }
    // спринт 41: адаптивное окно - по высоте экрана телефона/планшета, вкладки сверху, список прокручивается
    com.vibetoon.ui.shell.AdaptiveDialog(
        onDismissRequest = onDismiss, title = "Настройки", maxWidth = 600.dp, scrollable = false,
        buttons = { TextButton(onDismiss) { Text("Готово") } }
    ) {
            Column(Modifier.weight(1f, fill = false)) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    TABS.forEachIndexed { i, (title, _) -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(ru(title), fontSize = 13.sp, maxLines = 1, softWrap = false) }) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (TABS[tab].first == APPEARANCE) {
                        com.vibetoon.ui.theme.LocalAppSettings.current?.let { st ->
                            com.vibetoon.ui.theme.AppearanceControls(st)
                            OutlinedButton(onClick = { st.reset() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Сбросить внешний вид", fontSize = 13.sp) }
                            Text("Язык интерфейса меняется сразу.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(top = 4.dp))
                        }
                        return@Column
                    }
                    if (TABS[tab].first == "Stylus") {
                        appSt?.let { st -> StylusSettingsSection(st) }
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
                                val st = com.vibetoon.ui.theme.LocalAppSettings.current
                                val available = (vm != null && item.title.startsWith("Flip Canvas")) || st != null && item.title in setOf("Left Handed", "Show Grid", "Reset Frame After Pause", "Checker Pattern in Thumbnails", "Play Audio While Scrubbing", "2 Finger Tap Undo", "3 Finger Tap Redo", "Forward/Backward: кадры (иначе рисунки)", "Quick Layer Delete: рисунок (иначе анимация)", "Selection Mask Visibility", "Bones Colors", "Auto-Add Keyframes", "Finger Drawing", "Cursor Enabled", "Cursor Hover Preview", "Select Layers on Canvas", "Pipette (Long Press)", "Pinch to Refocus", "Follow Camera", "Show Motion Path", "Manually Close Selections", "Double Tap & Drag for Multi-select", "Touch Detection", "Shape Assist", "Quick Selection", "Compatible Video Export", "Performance HUD", "Vector Workflow", "Auto Pixel Edit Mode", "Only Erase/Smudge Selected Path Layers", "Temporary Pivots", "Layer Pivot Editing", "Rotate Canvas 2 Fingers", "Import Images as Symbols", "Resolution Limit", "Import Layer Locked States", "Import Text as Pixel Layer", "Insert Images into Existing Drawing", "Show Symbol Context", "Symbol Context Opacity", "Smooth UI Animations", "Auto-Extend Project for Media", "Background Import/Export", "Bone Chain Keys")
                                Column(Modifier.weight(1f)) {
                                    Text(ru(item.title), fontSize = 14.sp)
                                    if (!available) Text("В разработке", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    else when (item.title) {
                                        "Quick Selection" -> Text("Касание выбирает слой под пальцем сразу, минуя группы; выделение пикселей не меняется", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Auto-Add Keyframes" -> Text("Слои, кости в режиме анимации и эффекты: первая правка на кадре > 0 ставит исходный ключ на кадре 0. Деформации Mesh/Warp/перспективы/пинов ключуются всегда, когда включена анимация (FX); ретайминг не поддерживается", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Bone Chain Keys" -> Text("«Добавить ключ» и автоключ кости фиксируют позу всей цепочки (например, плечо, предплечье и кисть) и других выбранных костей", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Background Import/Export" -> Text("Импорт и экспорт видео продолжаются, если свернуть приложение: идёт служба с уведомлением о ходе работы", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Auto-Extend Project for Media" -> Text("Если добавленное аудио или видео длиннее проекта, проект продлевается до его конца (не дольше предела длительности); Undo отменяет и продление", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Shape Assist" -> Text("Частично: прямые, овалы, прямоугольники и треугольники после удержания; без предпросмотра", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Touch Detection" -> Text("Защита от ладони: касания пальцем игнорируются во время касания пера и 1,2 секунды после него; наведение не блокирует палец", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Double Tap & Drag for Multi-select" -> Text("Transform: двойной тап и протяжка выбирают видимые незаблокированные слои по центру рамки; без костей", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Manually Close Selections" -> Text("Для растрового лассо: замкните у начала или продолжайте от конца; остальные способы выделения не меняются", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Show Motion Path" -> Text("Только корневой слой или кость: без родителя, камеры проекта и ретайминга; не экспортируется", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Pinch to Refocus" -> Text("Двойной тап по кнопке фокуса на верхней панели; жест щипка не меняется", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Follow Camera" -> Text("При воспроизведении центрирует выбранный слой или кость; не меняет экспорт", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Pipette (Long Press)" -> Text("Удерживайте неподвижно в режиме Transform; при рисовании и деформации жест отключён", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Finger Drawing" -> Text("Кисть и ластик: при выключении рисует только перо; остальные растровые инструменты не меняются", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Cursor Enabled" -> Text("Круг кисти и инструментов деформации во время касания", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Cursor Hover Preview" -> Text("Превью пера над выбранным растровым слоем; зависит от поддержки наведения устройством", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Select Layers on Canvas" -> Text("Выбор слоёв касанием холста; кости и пипетка не затрагиваются", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Vector Workflow" -> Text("Кисть на векторном слое рисует примитивы (путь, линия, прямоугольник, эллипс); на растровом - пиксели", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Auto Pixel Edit Mode" -> Text("При выборе кисти/ластика слой выбирается или создаётся автоматически", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Only Erase/Smudge Selected Path Layers" -> Text("Ластик и смаз действуют только на выбранных слоях-контурах", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Resolution Limit" -> Text("Картинки при импорте уменьшаются до длинной стороны холста (512-2048 px): меньше памяти и файлов проекта; видео не меняется", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Temporary Pivots" -> Text("Пивот действует только на время перетаскивания и не сохраняется", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Rotate Canvas 2 Fingers" -> Text("Поворот после 8° двумя пальцами, снап к 0/90/180/270°; только вид, экспорт не меняется", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Performance HUD" -> Text("Время сборки кадра (UI) и отрисовки (GL), доля медленных кадров, слои вне экрана и проходы эффектов", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Insert Images into Existing Drawing" -> Text("Выбран слой-картинка: «+ Изображение» вписывает картинку в его рисунок на плейхеде (пропорции, по центру) вместо нового слоя; Undo возвращает рисунок", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Import Layer Locked States" -> Text("Новый PSD: замок слоя (пиксели, положение, весь слой) - замок, замок прозрачности - «Замок прозрачности»; уже открытые проекты не меняются", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Import Text as Pixel Layer" -> Text("Выкл.: текстовые слои нового PSD становятся редактируемым текстом (текст, размер, цвет, выравнивание; шрифт по умолчанию, поверх слоёв PSD)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Show Symbol Context" -> Text("При редактировании символа слои главной сцены видны полупрозрачно вокруг него, выровненные по экземпляру; не выбираются и не экспортируются", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Symbol Context Opacity" -> Text("Насколько видна главная сцена вокруг открытого символа (5-100%)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Import Images as Symbols" -> Text("«+ Изображение» сразу создаёт символ: картинка - определение, на холсте - экземпляр", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Flip Canvas (горизонтально)", "Flip Canvas (вертикально)" -> Text("Отражение только вида холста; экспорт не меняется", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Smooth UI Animations" -> Text("Панели и окна появляются плавно; учитывается «Масштаб длительности анимации» Android (0 - без анимации)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        "Layer Pivot Editing" -> Text("Опорная точка слоя перетаскивается на холсте; «Пивот в центр» в инспекторе", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (item.title == "Flip Canvas (горизонтально)" && vm != null) { var fx by remember { mutableStateOf(vm.cameraFlipped().first) }; Switch(checked = fx, onCheckedChange = { fx = it; vm.setCameraFlip(true, it) }) }
                                else if (item.title == "Flip Canvas (вертикально)" && vm != null) { var fy by remember { mutableStateOf(vm.cameraFlipped().second) }; Switch(checked = fy, onCheckedChange = { fy = it; vm.setCameraFlip(false, it) }) }
                                else if (item.title == "Smooth UI Animations" && st != null) Switch(checked = st.uiAnimations, onCheckedChange = { st.uiAnimations = it; st.saveMotion() })
                                else if (item.title == "Left Handed" && st != null) Switch(checked = st.leftHanded, onCheckedChange = { st.leftHanded = it; st.save() })
                                else if (item.title == "Show Grid" && st != null) Switch(checked = st.showGrid, onCheckedChange = { st.showGrid = it; st.saveGrid() })
                                else if (item.title == "Play Audio While Scrubbing" && st != null) Switch(checked = st.audioWhileScrubbing, onCheckedChange = { st.audioWhileScrubbing = it; st.savePlaybackAndDisplay(); if (!it) vm?.stopScrubPreview() })
                                else if (item.title == "Performance HUD" && st != null) Switch(checked = st.perfHud, onCheckedChange = { st.perfHud = it; st.saveExport(); vm?.setPerfHud(it) })
                                else if (item.title == "Compatible Video Export" && st != null) Switch(checked = st.compatibleVideoExport, onCheckedChange = { st.compatibleVideoExport = it; st.saveExport() })
                                else if (item.title == "Reset Frame After Pause" && st != null) Switch(checked = st.resetFrameAfterPause, onCheckedChange = { st.resetFrameAfterPause = it; st.savePlaybackAndDisplay() })
                                else if (item.title == "Checker Pattern in Thumbnails" && st != null) Switch(checked = st.checkerThumbnails, onCheckedChange = { st.checkerThumbnails = it; st.savePlaybackAndDisplay() })
                                else if (item.title == "Auto-Add Keyframes" && st != null) Switch(checked = st.autoKeyframes, onCheckedChange = { st.autoKeyframes = it; st.saveSprint67() })
                                else if (item.title == "Shape Assist" && st != null) Switch(checked = st.shapeAssist, onCheckedChange = { st.shapeAssist = it; st.saveSprint68() })
                                else if (item.title == "Touch Detection" && st != null) Switch(checked = st.touchDetection, onCheckedChange = { st.touchDetection = it; st.saveSprint68() })
                                else if (item.title == "Double Tap & Drag for Multi-select" && st != null) Switch(checked = st.doubleTapMultiSelect, onCheckedChange = { st.doubleTapMultiSelect = it; st.saveSprint68() })
                                else if (item.title == "Manually Close Selections" && st != null) Switch(checked = st.manualSelectionClose, onCheckedChange = { st.manualSelectionClose = it; st.saveSprint68() })
                                else if (item.title == "Show Motion Path" && st != null) Switch(checked = st.showMotionPath, onCheckedChange = { st.showMotionPath = it; st.saveSprint68() })
                                else if (item.title == "Pinch to Refocus" && st != null) Switch(checked = st.refocusButton, onCheckedChange = { st.refocusButton = it; st.saveSprint68() })
                                else if (item.title == "Follow Camera" && st != null) Switch(checked = st.followCamera, onCheckedChange = { st.followCamera = it; st.saveSprint68() })
                                else if (item.title == "Pipette (Long Press)" && st != null) Switch(checked = st.longPressPicker, onCheckedChange = { st.longPressPicker = it; st.saveSprint68() })
                                else if (item.title == "Finger Drawing" && st != null) Switch(checked = st.fingerDrawing, onCheckedChange = { st.fingerDrawing = it; st.saveSprint68() })
                                else if (item.title == "Cursor Enabled" && st != null) Switch(checked = st.cursorEnabled, onCheckedChange = { st.cursorEnabled = it; st.saveSprint68() })
                                else if (item.title == "Cursor Hover Preview" && st != null) Switch(checked = st.cursorHoverPreview, onCheckedChange = { st.cursorHoverPreview = it; st.saveSprint68() })
                                else if (item.title == "Quick Selection" && st != null) Switch(checked = st.quickSelection, onCheckedChange = { st.quickSelection = it; st.saveSprint68() })
                                else if (item.title == "Select Layers on Canvas" && st != null) Switch(checked = st.selectLayersOnCanvas, onCheckedChange = { st.selectLayersOnCanvas = it; st.saveSprint68() })
                                else if (item.title == "Forward/Backward: кадры (иначе рисунки)" && st != null) Switch(checked = st.stepFrames, onCheckedChange = { st.stepFrames = it; st.saveSprint67() })
                                else if (item.title == "Quick Layer Delete: рисунок (иначе анимация)" && st != null) Switch(checked = st.quickDeleteDrawing, onCheckedChange = { st.quickDeleteDrawing = it; st.saveSprint67() })
                                else if (item.title == "Selection Mask Visibility" && st != null) Switch(checked = st.selectionMaskVisible, onCheckedChange = { st.selectionMaskVisible = it; st.saveSprint67() })
                                else if (item.title == "Bones Colors" && st != null) Switch(checked = st.boneColors, onCheckedChange = { st.boneColors = it; st.saveSprint67() })
                                else if (item.title == "Vector Workflow" && st != null) Switch(checked = st.vectorWorkflow, onCheckedChange = { st.vectorWorkflow = it; st.savePixelModes() })
                                else if (item.title == "Auto Pixel Edit Mode" && st != null) Switch(checked = st.autoPixelEdit, onCheckedChange = { st.autoPixelEdit = it; st.savePixelModes() })
                                else if (item.title == "Only Erase/Smudge Selected Path Layers" && st != null) Switch(checked = st.onlyEraseSelectedPaths, onCheckedChange = { st.onlyEraseSelectedPaths = it; st.savePixelModes() })
                                else if (item.title == "Temporary Pivots" && st != null) Switch(checked = st.temporaryPivots, onCheckedChange = { st.temporaryPivots = it; st.savePixelModes() })
                                else if (item.title == "Rotate Canvas 2 Fingers" && st != null) Switch(checked = st.rotateCanvas, onCheckedChange = { st.rotateCanvas = it; st.savePixelModes() })
                                else if (item.title == "Layer Pivot Editing" && st != null) Switch(checked = st.pivotEditing, onCheckedChange = { st.pivotEditing = it; st.savePixelModes() })
                                else if (item.title == "Auto-Extend Project for Media" && st != null) Switch(checked = st.autoExtendMedia, onCheckedChange = { st.autoExtendMedia = it; st.saveSprint41() })
                                else if (item.title == "Background Import/Export" && st != null) Switch(checked = st.backgroundWork, onCheckedChange = { st.backgroundWork = it; st.saveSprint41() })
                                else if (item.title == "Bone Chain Keys" && st != null) Switch(checked = st.boneChainKeys, onCheckedChange = { st.boneChainKeys = it; st.saveSprint41() })
                                else if (item.title == "Resolution Limit" && st != null) Switch(checked = st.resolutionLimit, onCheckedChange = { st.resolutionLimit = it; st.saveImport() })
                                else if (item.title == "Show Symbol Context" && st != null) Switch(checked = st.symbolContext, onCheckedChange = { st.symbolContext = it; st.saveSymbolContext(); vm?.refreshSymbolContext() })
                                else if (item.title == "Symbol Context Opacity" && st != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${st.symbolContextOpacity}%", fontSize = 12.sp)
                                    androidx.compose.material3.Slider(
                                        value = st.symbolContextOpacity.toFloat(), valueRange = com.vibetoon.domain.render.SymbolContext.MIN_OPACITY_PERCENT.toFloat()..100f,
                                        onValueChange = { st.symbolContextOpacity = it.roundToInt() },
                                        onValueChangeFinished = { st.saveSymbolContext(); vm?.refreshSymbolContext() },
                                        enabled = st.symbolContext, modifier = Modifier.width(140.dp)
                                    )
                                }
                                else if (item.title == "Insert Images into Existing Drawing" && st != null) Switch(checked = st.importIntoDrawing, onCheckedChange = { st.importIntoDrawing = it; st.saveImport() })
                                else if (item.title == "Import Layer Locked States" && st != null) Switch(checked = st.importLayerLocks, onCheckedChange = { st.importLayerLocks = it; st.saveImport() })
                                else if (item.title == "Import Text as Pixel Layer" && st != null) Switch(checked = st.importTextAsPixels, onCheckedChange = { st.importTextAsPixels = it; st.saveImport() })
                                else if (item.title == "Import Images as Symbols" && st != null) Switch(checked = st.importImagesAsSymbols, onCheckedChange = { st.importImagesAsSymbols = it; st.saveImport() })
                                else if (item.title == "2 Finger Tap Undo" && st != null) Switch(checked = st.tap2Undo, onCheckedChange = { st.tap2Undo = it; st.saveGestures() })
                                else if (item.title == "3 Finger Tap Redo" && st != null) Switch(checked = st.tap3Redo, onCheckedChange = { st.tap3Redo = it; st.saveGestures() })
                                else Switch(checked = false, onCheckedChange = null, enabled = false)
                            }
                            is Action -> OutlinedButton(onClick = {
                                when (item.title) {
                                    "Reset" -> if (appSt != null) { appSt.resetDisplay(); notify("Настройки отображения сброшены") } else stub(item.title)
                                    "Manual Backup" -> if (appSt != null) backupOut.launch("vibetoon-settings.json") else stub(item.title)
                                    "Import Backup" -> if (appSt != null) backupIn.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) else stub(item.title)
                                    else -> stub(item.title)
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(ru(item.title), fontSize = 13.sp) }
                            is Info -> Row(Modifier.fillMaxWidth()) {
                                Text(ru(item.title), fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text(item.value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                    val gridSettings = com.vibetoon.ui.theme.LocalAppSettings.current
                    // пакет 23: PSD Import Mode - как раскладывается PSD, вставленный в текущий проект
                    if (TABS[tab].first == "Import" && gridSettings != null) {
                        Text(ru("PSD Import Mode"), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        Text("Для PSD, вставленного в текущий проект (+ → «PSD в текущий проект» или библиотека). PSD нового проекта всегда раскладывается слоями как в файле", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            com.vibetoon.domain.project.PsdInsertPlan.Mode.entries.forEach { m ->
                                androidx.compose.material3.FilterChip(
                                    selected = gridSettings.psdImportMode == m.code,
                                    onClick = { gridSettings.psdImportMode = m.code; gridSettings.savePsdImportMode() },
                                    label = { Text(m.title, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                    if (TABS[tab].first in setOf("General", "Display") && gridSettings?.showGrid == true) {
                        Text("Ячеек по ширине холста", fontSize = 13.sp)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            com.vibetoon.domain.render.CanvasGrid.densityOptions.forEach { count ->
                                androidx.compose.material3.FilterChip(
                                    selected = gridSettings.gridCells == count,
                                    onClick = { gridSettings.gridCells = count; gridSettings.saveGrid() },
                                    label = { Text(count.toString(), fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                    Text(
                        "Недоступные пункты помечены как находящиеся в разработке.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
    }
}
