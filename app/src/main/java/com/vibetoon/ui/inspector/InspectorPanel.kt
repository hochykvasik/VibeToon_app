package com.vibetoon.ui.inspector

import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Link
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.data.FontEntry
import com.vibetoon.domain.project.Clip
import com.vibetoon.ui.ColorAdjust
import com.vibetoon.ui.InspectorState
import com.vibetoon.ui.MAX_GRADIENT_STOPS
import com.vibetoon.ui.MainViewModel
import com.vibetoon.ui.Prop
import com.vibetoon.ui.TextState
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

private const val TAB_TRANSFORM = "Трансформация"
private const val TAB_TEXT = "Текст"
private const val TAB_CLIP = "Клип"
private const val TAB_COLOR = "Цветокоррекция"
private const val TAB_EFFECTS = "Эффекты"
private const val TAB_TIME = "Время"
private const val TAB_VOLUME = "Громкость"
private val STUB_TABS = listOf("Обрезка", TAB_EFFECTS, "Рисование")

private val EFFECTS = listOf(
    "Gaussian Blur", "Adjustments", "Pixelate", "Chroma Key", "Sharpness", "Halftone", "Turbulent Displace", "Bones",
    "Mesh", "Perspective", "Warp", "Stroke", "Drop Shadow", "Noise", "Orient Along Motion Path", "Spin", "Parallax"
)

/** Заготовка вкладки эффектов: список пуст, «+» предлагает эффекты (пока заглушки). */
@Composable
private fun EffectsTab(vm: MainViewModel) {
    val stub = com.vibetoon.ui.shell.LocalStub.current
    var menu by remember { mutableStateOf(false) }
    val turb by vm.turb.collectAsStateWithLifecycle()
    val boneStrength by vm.boneStrength.collectAsStateWithLifecycle()
    val layerFx by vm.layerEffects.collectAsStateWithLifecycle()
    val groupFxInfo by vm.groupFxInfo.collectAsStateWithLifecycle()
    val groupFxBlocked by vm.groupFxBlocked.collectAsStateWithLifecycle()
    val fxLibrary by vm.fxLibrary.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        boneStrength?.let { s ->
            TurbSlider("Strength кости (вес при скининге)", s, 0f..1f, "%.2f".format(s)) { vm.setBoneStrength(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Turbulent Displace", fontSize = 13.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = turb != null, onCheckedChange = { vm.toggleTurb() })
        }
        turb?.let { t ->
            TurbSlider("Intensity, %", t.intensity, 0f..30f, "%.1f".format(t.intensity)) { vm.setTurbParam(0, it) }
            TurbSlider("Size", t.size, 1f..40f, "%.0f".format(t.size)) { vm.setTurbParam(1, it) }
            TurbSlider("Complexity", t.complexity.toFloat(), 1f..6f, t.complexity.toString()) { vm.setTurbParam(2, it) }
            TurbSlider("Line Boil, кадров (0 - выкл)", t.boil.toFloat(), 0f..24f, t.boil.toString()) { vm.setTurbParam(3, it) }
        }
        if (groupFxInfo.isNotEmpty()) Text(groupFxInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        if (groupFxInfo.isNotEmpty()) androidx.compose.material3.OutlinedButton(
            onClick = vm::keyGroupEffects, enabled = !groupFxBlocked && layerFx.isNotEmpty(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) { Text("Ключи эффектов на вложенных слоях", fontSize = 12.sp) }
        if (groupFxInfo.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = { vm.moveGroupEffectKeys(-1) }, enabled = !groupFxBlocked) { Text("Ключ −1", fontSize = 12.sp) }
            androidx.compose.material3.OutlinedButton(onClick = { vm.moveGroupEffectKeys(1) }, enabled = !groupFxBlocked) { Text("Ключ +1", fontSize = 12.sp) }
            androidx.compose.material3.OutlinedButton(onClick = vm::deleteGroupEffectKeys, enabled = !groupFxBlocked) { Text("Удалить ключи", fontSize = 12.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Эффекты слоя", fontSize = 13.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Button(
                onClick = { vm.openFxLibrary(true) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("+ Добавить эффект", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        if (layerFx.isEmpty() && !groupFxBlocked) Text(
            "Эффектов нет. Нажмите «+ Добавить эффект»: Хромакей, Размытие, Цветокоррекция, Пикселизация, Свечение, Тень и другие. Цепочка применяется сверху вниз.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        layerFx.forEachIndexed { idx, e ->
            val def = com.vibetoon.domain.anim.EffectMath.def(e.type) ?: return@forEachIndexed
            androidx.compose.material3.Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${idx + 1}. ${com.vibetoon.ui.i18n.L(def.title)}", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        androidx.compose.material3.IconButton(onClick = { vm.moveEffect(idx, -1) }, enabled = idx > 0, modifier = Modifier.size(32.dp)) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowUp, com.vibetoon.ui.i18n.L("Выше"))
                        }
                        androidx.compose.material3.IconButton(onClick = { vm.moveEffect(idx, 1) }, enabled = idx < layerFx.lastIndex, modifier = Modifier.size(32.dp)) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowDown, com.vibetoon.ui.i18n.L("Ниже"))
                        }
                        androidx.compose.material3.IconButton(onClick = { vm.removeEffect(idx) }, modifier = Modifier.size(32.dp)) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Delete, com.vibetoon.ui.i18n.L("Удалить эффект"))
                        }
                    }
                    if (def.colorAt >= 0) {
                        val at = def.colorAt
                        val col = Color(e.p[at], e.p[at + 1], e.p[at + 2])
                        var wheel by remember(idx) { mutableStateOf(false) }
                        if (wheel) ColorPickerDialog(
                            initial = 0xFF000000L or (((e.p[at] * 255f).toInt().toLong() and 0xFF) shl 16) or (((e.p[at + 1] * 255f).toInt().toLong() and 0xFF) shl 8) or ((e.p[at + 2] * 255f).toInt().toLong() and 0xFF),
                            onColor = { c -> vm.setEffectColor(idx, c) },
                            onEyedropper = { wheel = false; vm.pickEffectColor(idx) },
                            onDismiss = { wheel = false }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(28.dp).background(col, androidx.compose.foundation.shape.CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape))
                            androidx.compose.material3.OutlinedButton(
                                onClick = { wheel = true },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(30.dp)
                            ) { Text("Круг", fontSize = 12.sp) }
                            androidx.compose.material3.OutlinedButton(
                                onClick = { vm.pickEffectColor(idx) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(30.dp)
                            ) { Text("Пипетка", fontSize = 12.sp) }
                            for (sw in listOf(0xFF00C853L, 0xFF2962FFL, 0xFFFFFFFFL, 0xFF000000L, 0xFFFF1744L)) {
                                Box(
                                    Modifier.size(24.dp).background(Color(sw.toInt()), androidx.compose.foundation.shape.CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)
                                        .clickable { vm.setEffectColor(idx, sw) }
                                )
                            }
                        }
                    }
                    def.params.forEachIndexed { pi, p ->
                        val isColor = def.colorAt >= 0 && pi in def.colorAt..def.colorAt + 2
                        if (isColor) Unit
                        else if (p.options.isNotEmpty()) {
                            // дискретный выбор (волна Swing): чипы вместо ползунка
                            val cur = kotlin.math.round(e.p[pi]).toInt()
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(p.name, fontSize = 12.sp)
                                p.options.forEachIndexed { oi, label ->
                                    androidx.compose.material3.FilterChip(selected = cur == oi, onClick = { vm.setEffectParam(idx, pi, oi.toFloat()) },
                                        label = { Text(label, fontSize = 12.sp) })
                                }
                            }
                        } else TurbSlider(p.name, e.p[pi], p.min..p.max, if (p.name.contains("°")) "%.1f°".format(e.p[pi]) else "%.2f".format(e.p[pi])) { vm.setEffectParam(idx, pi, it) }
                    }
                }
            }
        }
    }
    if (fxLibrary) EffectLibraryDialog(onPick = { vm.addEffect(it) }, onDismiss = { vm.openFxLibrary(false) })
}

@Composable
private fun TurbSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onValue: (Float) -> Unit) {
    ScrubSlider(title, value, range, label, onValue)
}

/** Цвета текста для быстрого выбора (ARGB). */
private val TEXT_COLORS = listOf(
    0xFF000000L, 0xFFFFFFFFL, 0xFFE53935L, 0xFFFB8C00L, 0xFFFDD835L,
    0xFF43A047L, 0xFF00ACC1L, 0xFF1E88E5L, 0xFF8E24AAL, 0xFFEC407AL
)

/**
 * Панель "Инспектор" под таймлайном. Вкладки: "Трансформация" (X, Y, Scale X/Y с цепочкой, Rotation,
 * Opacity), "Текст" (появляется, когда выделен текстовый слой) и заглушки "Обрезка", "Эффекты",
 * "Рисование" под будущие этапы. Числа в полях и положение слоя на холсте синхронизированы в обе стороны.
 */
@Composable
fun InspectorPanel(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.inspector.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val uniform by vm.uniformScale.collectAsStateWithLifecycle()
    val textState by vm.textState.collectAsStateWithLifecycle()
    val fontList by vm.fontList.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val clips by vm.clips.collectAsStateWithLifecycle()
    val colors by vm.colors.collectAsStateWithLifecycle()
    val animation by vm.animation.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(TAB_TRANSFORM) }

    val isSymbol = selectedId?.let { vm.isSymbolInstance(it) } == true
    val isAudio = selectedId?.let { vm.hasSound(it) } == true   // пакет 11: и у видео - его звук
    val tabs = buildList {
        add(TAB_TRANSFORM)
        if (isSymbol) add(TAB_TIME)
        if (isAudio) add(TAB_VOLUME)
        if (textState != null) add(TAB_TEXT)
        add(TAB_CLIP)
        add(TAB_COLOR)
        addAll(STUB_TABS)
    }
    // Выделили текст - открываем вкладку "Текст"; сняли выделение - возвращаемся к трансформации
    LaunchedEffect(textState?.id) {
        tabName = if (textState != null) TAB_TEXT else if (tabName == TAB_TEXT) TAB_TRANSFORM else tabName
    }
    // выбрали экземпляр символа - открывается «Время»; ушли с него - обратно к трансформации
    LaunchedEffect(isSymbol) { tabName = if (isSymbol) TAB_TIME else if (tabName == TAB_TIME) TAB_TRANSFORM else tabName }
    val selectedIndex = tabs.indexOf(tabName).coerceAtLeast(0)

    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFont(uri)
    }

    Column(modifier.fillMaxWidth()) {
        // ScrollableTabRow: названия вкладок целиком, даже на узком телефоне
        ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 0.dp) {
            tabs.forEachIndexed { i, title ->
                Tab(
                    selected = selectedIndex == i,
                    onClick = { tabName = title },
                    text = { Text(title, fontSize = 12.sp, maxLines = 1, softWrap = false) }
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            when (tabs[selectedIndex]) {
                TAB_TRANSFORM -> TransformTab(state, playing, uniform, vm)
                TAB_TIME -> selectedId?.let { com.vibetoon.ui.SymbolTimeTab(vm, it) }
                TAB_VOLUME -> selectedId?.let { VolumeTab(vm, it, vm.frame.collectAsStateWithLifecycle().value) }
                TAB_TEXT -> textState?.let { TextTab(it, fontList, vm) { pickFont.launch(arrayOf("*/*")) } }
                TAB_CLIP -> ClipTab(vm, selectedId, clips, animation.settings.durationFrames, !playing)
                TAB_COLOR -> ColorTab(vm, selectedId, colors)
                TAB_EFFECTS -> EffectsTab(vm)
                else -> Text(
                    "«${com.vibetoon.ui.i18n.L(tabs[selectedIndex])}» появится на одном из следующих этапов",   // имя вкладки тоже переводится (раньше в EN оставалось «Рисование»)
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun TransformTab(s: InspectorState?, playing: Boolean, uniform: Boolean, vm: MainViewModel) {
    if (s == null) {
        Text(
            "Выберите слой или группу на холсте или в списке слоёв",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(8.dp)
        )
        return
    }
    val on = !playing
    var showParent by remember { mutableStateOf(false) }
    val links by vm.links.collectAsStateWithLifecycle()
    val parent = links[s.id]
    val bones by vm.bones.collectAsStateWithLifecycle()
    val parentBone = bones.firstOrNull { it.id == parent }
    val parentName = if (parentBone != null) com.vibetoon.ui.i18n.boneDisplayName(parentBone.name)
        else parent?.let { vm.layerName(it) }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (s.count > 1) "Выбрано: ${s.count} (правка применяется ко всем)" else if (s.isGroup) "Группа: ${s.name}" else "Слой: ${s.name}",
            fontSize = 12.sp, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField("X", s.x, "px", on, { vm.setProperty(Prop.X, it) }, Modifier.weight(1f), perPx = 1f)
            NumberField("Y", s.y, "px", on, { vm.setProperty(Prop.Y, it) }, Modifier.weight(1f), perPx = 1f)
            NumberField("Rotation", s.rotationDeg, "°", on, { vm.setProperty(Prop.ROTATION, it) }, Modifier.weight(1f), perPx = 0.5f)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            NumberField("Scale X", s.scaleXPct, "%", on, { vm.setProperty(Prop.SCALE_X, it) }, Modifier.weight(1f), perPx = 0.5f, min = 1f)
            // Цепочка: пропорциональный масштаб или ScaleX и ScaleY отдельно
            FilterChip(
                selected = uniform,
                onClick = vm::toggleUniformScale,
                label = { Icon(if (uniform) Icons.Default.Link else Icons.Default.LinkOff, com.vibetoon.ui.i18n.L("Пропорции"), Modifier.size(18.dp)) }
            )
            NumberField("Scale Y", s.scaleYPct, "%", on, { vm.setProperty(Prop.SCALE_Y, it) }, Modifier.weight(1f), perPx = 0.5f, min = 1f)
            NumberField("Opacity", s.opacityPct, "%", on, { vm.setProperty(Prop.OPACITY, it) }, Modifier.weight(1f), perPx = 0.3f, min = 0f, max = 100f)
        }
        // глубина для параллакса слоя-камеры: 0 - на плоскости камеры, больше - дальше
        if (s.count == 1) {
            val depth = vm.layerPropsOf(s.id).depth
            NumberField("Глубина (параллакс)", depth, "", on, { vm.setDepth(s.id, it) }, Modifier.fillMaxWidth(), perPx = 0.02f, min = -0.5f, max = 5f)
        }
        // Опорная точка (пивот): перетаскивается на холсте, здесь - координаты и быстрые действия
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField("Пивот X", s.pivotX, "px", on, { vm.setPivot(s.id, it, s.pivotY) }, Modifier.weight(1f), perPx = 1f)
            NumberField("Пивот Y", s.pivotY, "px", on, { vm.setPivot(s.id, s.pivotX, it) }, Modifier.weight(1f), perPx = 1f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = vm::pivotToCenter, enabled = on, modifier = Modifier.weight(1f).height(36.dp)) {
                Text("Пивот в центр", fontSize = 12.sp, maxLines = 1)
            }
            OutlinedButton(onClick = vm::pivotReset, enabled = on, modifier = Modifier.weight(1f).height(36.dp)) {
                Text("Пивот по умолчанию", fontSize = 12.sp, maxLines = 1)
            }
        }
        Text(
            "Совет: зажмите число и потяните вправо или влево",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        if (parent != null && s.count == 1) OutlinedButton(onClick = { vm.setParent(null) }, modifier = Modifier.fillMaxWidth().height(36.dp)) {
            Text("Без родителя (Unparent)", fontSize = 13.sp, maxLines = 1)
        }
        OutlinedButton(onClick = { showParent = true }, enabled = on, modifier = Modifier.fillMaxWidth().height(40.dp)) {
            Text(
                if (parent != null && s.count == 1) "Родитель: $parentName" else "Привязать к родителю (Parent)",
                fontSize = 13.sp, maxLines = 1
            )
        }
    }
    if (showParent) {
        val candidates = remember(s.id, s.count) { vm.parentCandidates() }
        AlertDialog(
            onDismissRequest = { showParent = false },
            title = { Text("Родитель") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Слой повторяет сдвиг, поворот и масштаб родителя (глаза двигаются вместе с головой).",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    if (parent != null) {
                        TextButton({ vm.setParent(null); showParent = false }) { Text("Отвязать") }
                    }
                    for ((id, name) in candidates) {
                        androidx.compose.material3.Text(
                            bones.firstOrNull { it.id == id }?.let { com.vibetoon.ui.i18n.boneDisplayName(it.name) } ?: name,
                            fontSize = 14.sp, maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { vm.setParent(id); showParent = false }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton({ showParent = false }) { Text("Закрыть") } }
        )
    }
}

/**
 * Вкладка "Клип": границы клипа на таймлайне (то же, что перетаскивание краёв полоски) и ретайминг:
 * скорость, реверс, стоп-кадр. Настройки ретайминга сохраняются в проекте и применятся к видео и
 * аудио, когда появится их воспроизведение внутри приложения.
 */
@Composable
private fun ClipTab(vm: MainViewModel, id: Int?, clips: Map<Int, Clip>, duration: Int, enabled: Boolean) {
    if (id == null) {
        Text("Выберите слой или группу", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(8.dp))
        return
    }
    val clip = clips[id] ?: vm.clipOf(id)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField("Начало (кадр)", clip.start.toFloat(), "fr", enabled, { vm.setClip(id, it.toInt(), clip.end) }, Modifier.weight(1f), perPx = 0.2f, min = 0f, max = (duration - 1).toFloat())
            NumberField("Конец (кадр)", clip.end.toFloat(), "fr", enabled, { vm.setClip(id, clip.start, it.toInt()) }, Modifier.weight(1f), perPx = 0.2f, min = 1f, max = duration.toFloat())
        }
        Text("Скорость", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (sp in Clip.SPEEDS) {
                FilterChip(
                    selected = clip.speed == sp, onClick = { vm.setClipSpeed(sp) }, enabled = enabled,
                    label = { Text(String.format(Locale.US, "%.2gx", sp)) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = clip.reverse, onClick = vm::toggleClipReverse, enabled = enabled, label = { Text("⏪ Реверс") })
            FilterChip(selected = clip.freeze, onClick = vm::toggleClipFreeze, enabled = enabled, label = { com.vibetoon.ui.IconText(Icons.Default.AcUnit, "Стоп-кадр", 14.sp) })
            FilterChip(selected = clip.loop, onClick = vm::toggleClipLoop, enabled = enabled, label = { com.vibetoon.ui.IconText(Icons.Default.Repeat, "Цикл", 14.sp) })
        }
        Text(
            "Края клипа тянутся пальцем на таймлайне, выбранный клип можно перетащить за середину. " +
                "Скорость, реверс и Цикл работают для звука и видео; Цикл повторяет клип до конца проекта или сцены. " +
                "Скорость и Цикл меняют и время ключей анимации слоя.",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

/** Вкладка "Цветокоррекция": ползунки экспозиции, контраста и насыщенности (шейдер будет позже). */
@Composable
private fun ColorTab(vm: MainViewModel, id: Int?, colors: Map<Int, ColorAdjust>) {
    if (id == null) {
        Text("Выберите слой или группу", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(8.dp))
        return
    }
    val c = colors[id] ?: ColorAdjust()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ColorSlider("Экспозиция", c.exposure) { vm.setColor(0, it) }
        ColorSlider("Контраст", c.contrast) { vm.setColor(1, it) }
        ColorSlider("Насыщенность", c.saturation) { vm.setColor(2, it) }
        ColorSlider("Тон (Hue)", c.hue) { vm.setColor(3, it) }
        TextButton(onClick = vm::resetColor, enabled = !c.isIdentity) { Text("Сбросить цвет") }
        Text(
            "Применяется шейдером эффектов (как эффект «Цветокоррекция» перед остальными). Сохраняется в проекте.",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ColorSlider(title: String, value: Float, onValue: (Float) -> Unit) {
    ScrubSlider(title, value, -100f..100f, value.toInt().toString(), onValue)
}

/**
 * Свойства текстового слоя: сам текст, цвет, шрифт (системные и импортированные TTF/OTF) и размер.
 * Любая правка перерисовывает Bitmap текста (с короткой задержкой) и обновляет текстуру на холсте.
 */
/** Громкость аудио по ключам: на текущем кадре выбирается значение и ставится ключ, между ключами громкость меняется линейно. */
@Composable
private fun VolumeTab(vm: MainViewModel, id: Int, frame: Int) {
    val cur = vm.volumeAt(id)
    val keys = vm.volumeKeyCount(id)
    var muted by remember(id) { mutableStateOf(vm.isMuted(id)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Без звука", fontSize = 14.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = muted, onCheckedChange = { muted = it; vm.setMuted(id, it) })
        }
        if (vm.videoWithoutSound(id)) Text("В этом видео нет звуковой дорожки", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        NumberField("Громкость", cur * 100f, "%", true, { vm.setVolumeKey(id, it / 100f) }, Modifier.fillMaxWidth(), perPx = 0.4f, min = 0f, max = 100f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.setVolumeKey(id, cur) }) { Text(if (vm.hasVolumeKeyHere(id)) "Ключ стоит" else "Поставить ключ", fontSize = 13.sp, maxLines = 1) }
            OutlinedButton(onClick = { vm.removeVolumeKey(id) }, enabled = vm.hasVolumeKeyHere(id)) { Text("Убрать ключ", fontSize = 13.sp, maxLines = 1) }
        }
        Text("Ключей: $keys. Громкость меняется линейно между ключами; до первого и после последнего держится. Работает при воспроизведении, прослушивании линейки, в WAV и в звуке MP4 (у видео - его звуковая дорожка).", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun TextTab(t: TextState, fonts: List<FontEntry>, vm: MainViewModel, onImportFont: () -> Unit) {
    // Текст в поле хранится локально (а не берётся из модели каждый раз), чтобы курсор не прыгал
    var text by remember(t.id) { mutableStateOf(t.text) }
    var fontMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; vm.setText(it) },
            label = { Text("Текст") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.weight(1.4f)) {
                OutlinedButton(onClick = { fontMenu = true }, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                    Text(t.fontTitle, fontSize = 13.sp, maxLines = 1)
                }
                DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                    for (f in fonts) {
                        DropdownMenuItem(
                            text = { Text(if (f.imported) "${f.title}  (свой)" else f.title) },
                            leadingIcon = { if (f.key == t.font) Icon(Icons.Default.Check, null) },
                            onClick = { vm.setFont(f.key); fontMenu = false }
                        )
                    }
                }
            }
            NumberField("Размер", t.size, "px", true, { vm.setTextSize(it) }, Modifier.weight(1f), perPx = 0.5f, min = 8f)
        }
        // выравнивание строк и интервалы (работают без текстовых эффектов)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            for ((a, title) in listOf(0 to "Слева", 1 to "По центру", 2 to "Справа"))
                FilterChip(selected = t.align == a, onClick = { vm.setTextAlign(a) }, label = { Text(title, fontSize = 12.sp, maxLines = 1, softWrap = false) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField("Межстрочный", t.lineSpacing * 100f, "%", true, { vm.setLineSpacing(it / 100f) }, Modifier.weight(1f), perPx = 0.5f, min = 50f, max = 300f)
            NumberField("Межбуквенный", t.letterSpacing * 100f, "%", true, { vm.setLetterSpacing(it / 100f) }, Modifier.weight(1f), perPx = 0.3f, min = -20f, max = 100f)
        }
        FillSection(t, vm)
        StrokeSection(t, vm)
        TextFxSection(t, vm)
        OutlinedButton(onClick = onImportFont, modifier = Modifier.fillMaxWidth()) {
            Text("Импорт шрифта (TTF, OTF)", fontSize = 13.sp)
        }
    }
}

/**
 * Заливка текста: сплошной цвет (круг цвета HSV, быстрая палитра, пипетка) или градиент из 2-5 цветов
 * с углом. Любая правка сразу перерисовывает текст на холсте.
 */
@Composable
private fun FillSection(t: TextState, vm: MainViewModel) {
    val gradient = t.gradient.size >= 2
    var target by remember { mutableStateOf<Int?>(null) }   // -1 основной цвет, иначе номер цвета градиента
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Заливка", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            FilterChip(selected = !gradient, onClick = { vm.setTextGradient(emptyList()) }, label = { Text("Сплошной") })
            FilterChip(
                selected = gradient,
                onClick = { if (!gradient) vm.setTextGradient(listOf(t.color, 0xFF1E88E5L)) },
                label = { Text("Градиент") }
            )
        }
        if (!gradient) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                // большой круг открывает круг цвета; рядом быстрая палитра
                ColorDot(t.color, chosen = true, size = 40) { target = -1 }
                OutlinedButton(onClick = { target = -1 }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.height(36.dp)) { Text("Круг цвета", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                for (c in TEXT_COLORS) ColorDot(c, chosen = c == t.color, size = 30) { vm.setTextColor(c) }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                t.gradient.forEachIndexed { i, c -> ColorDot(c, chosen = false, size = 40) { target = i } }
                OutlinedButton(
                    onClick = { vm.setTextGradient(t.gradient + t.gradient.last()) },
                    enabled = t.gradient.size < MAX_GRADIENT_STOPS
                ) { Text("＋") }
                OutlinedButton(
                    onClick = { vm.setTextGradient(t.gradient.dropLast(1)) },
                    enabled = t.gradient.size > 2
                ) { Text("−") }
            }
            Text("Нажмите на цветной кружок, чтобы выбрать цвет на круге HSV", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            ScrubSlider("Угол", t.gradientAngle, 0f..360f, "${t.gradientAngle.toInt()}°") { vm.setTextGradientAngle(it) }
        }
    }
    target?.let { tg ->
        val initial = if (tg < 0) t.color else t.gradient.getOrElse(tg) { t.color }
        ColorPickerDialog(
            initial = initial,
            onColor = { c ->
                if (tg < 0) vm.setTextColor(c)
                else if (tg < t.gradient.size) vm.setTextGradient(t.gradient.toMutableList().also { it[tg] = c })
            },
            onEyedropper = { vm.startEyedropper(tg) },
            onDismiss = { target = null }
        )
    }
}

@Composable
private fun ColorDot(color: Long, chosen: Boolean, size: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(color.toInt()))
            .border(
                BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                CircleShape
            )
            .clickable(onClick = onClick)
    )
}

private val numberFormat = DecimalFormat("0.#", DecimalFormatSymbols(Locale.US))

/**
 * Числовое поле. Каждое корректное число сразу уходит в [onValue] (слой двигается на холсте);
 * пока поле в фокусе, пользовательский текст не перезаписывается; без фокуса поле показывает
 * актуальное значение модели, поэтому жесты и Play обновляют цифры.
 */
@Composable
private fun NumberField(
    label: String,
    value: Float,
    suffix: String,
    enabled: Boolean,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** Свайп по полю: на сколько меняется значение за один пиксель сдвига пальца. */
    perPx: Float = 1f,
    min: Float = -100000f,
    max: Float = 100000f
) {
    var focused by remember { mutableStateOf(false) }
    val curValue by rememberUpdatedState(value)
    val curOnValue by rememberUpdatedState(onValue)
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(numberFormat.format(value)) }
    LaunchedEffect(value, focused) {
        if (!focused) text = numberFormat.format(value)
    }
    val border = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(modifier) {
        Text(label, fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(BorderStroke(1.dp, border), RoundedCornerShape(8.dp))
                // Drag-to-change: горизонтальный свайп по полю плавно меняет число. Жест перехватывается
                // раньше поля ввода только после выхода за touch slop, поэтому обычный тап по-прежнему
                // открывает клавиатуру.
                .pointerInput(enabled, perPx, min, max) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var total = 0f
                        var scrubbing = false
                        var start = curValue
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            total += c.positionChange().x
                            if (!scrubbing && abs(total) > viewConfiguration.touchSlop) {
                                scrubbing = true
                                focusManager.clearFocus()
                                start = curValue
                                total = 0f
                            }
                            if (scrubbing) {
                                c.consume()
                                curOnValue((start + total * perPx).coerceIn(min, max))
                            }
                        }
                    }
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = text,
                onValueChange = { v ->
                    val clean = v.filter { it.isDigit() || it == '.' || it == '-' || it == ',' }.take(9)
                    text = clean
                    clean.replace(',', '.').toFloatOrNull()?.let(onValue)
                },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }
            )
            Text(suffix, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/** «Эффекты текста»: печать, счётчик, тряска, контур, толщина, рандомизатор. Время считается от начала проекта. */
@Composable
private fun TextFxSection(t: TextState, vm: MainViewModel) {
    val fx = t.fx
    fun sec(v: Float) = "${"%.1f".format(v)} с"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Эффекты текста", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        FilterChip(selected = fx.progress, onClick = { vm.setTextFx { it.copy(progress = !it.progress) } }, label = { Text("Печать (по буквам)") })
        if (fx.progress) ScrubSlider("Время печати", fx.progressDur, 0.2f..10f, sec(fx.progressDur)) { v -> vm.setTextFx { it.copy(progressDur = v) } }
        FilterChip(selected = fx.counter, onClick = { vm.setTextFx { it.copy(counter = !it.counter) } }, label = { Text("Счётчик / таймкод") })
        if (fx.counter) {
            FilterChip(selected = fx.timecode, onClick = { vm.setTextFx { it.copy(timecode = !it.timecode) } }, label = { Text("Таймкод ММ:СС:КК") })
            ScrubSlider("От", fx.countFrom, 0f..100000f, "%,.0f".format(fx.countFrom)) { v -> vm.setTextFx { it.copy(countFrom = v) } }
            ScrubSlider("До", fx.countTo, 0f..100000f, "%,.0f".format(fx.countTo)) { v -> vm.setTextFx { it.copy(countTo = v) } }
            ScrubSlider("Время", fx.countDur, 0.2f..10f, sec(fx.countDur)) { v -> vm.setTextFx { it.copy(countDur = v) } }
            Text("Текст слоя заменяется числом; «#» в тексте - место числа (например «$#»).", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
        FilterChip(selected = fx.shake > 0f, onClick = { vm.setTextFx { it.copy(shake = if (it.shake > 0f) 0f else 0.3f) } }, label = { Text("Тряска") })
        if (fx.shake > 0f) {
            ScrubSlider("Сила", fx.shake, 0.02f..1f, "${(fx.shake * 100).toInt()}%") { v -> vm.setTextFx { it.copy(shake = v) } }
            ScrubSlider("Частота", fx.shakeHz, 1f..30f, "${fx.shakeHz.toInt()} Гц") { v -> vm.setTextFx { it.copy(shakeHz = v) } }
        }
        FilterChip(selected = fx.pathBend != 0f, onClick = { vm.setTextFx { it.copy(pathBend = if (it.pathBend != 0f) 0f else 40f) } }, label = { Text("Текст по контуру") })
        if (fx.pathBend != 0f) ScrubSlider("Изгиб дуги", fx.pathBend, -100f..100f, "${fx.pathBend.toInt()}") { v -> vm.setTextFx { it.copy(pathBend = if (v == 0f) 1f else v) } }
        FilterChip(selected = fx.weight, onClick = { vm.setTextFx { it.copy(weight = !it.weight) } }, label = { Text("Толщина шрифта") })
        if (fx.weight) {
            ScrubSlider("От", fx.weightFrom.toFloat(), 300f..800f, "${fx.weightFrom}") { v -> vm.setTextFx { it.copy(weightFrom = v.toInt()) } }
            ScrubSlider("До", fx.weightTo.toFloat(), 300f..800f, "${fx.weightTo}") { v -> vm.setTextFx { it.copy(weightTo = v.toInt()) } }
            ScrubSlider("Время", fx.weightDur, 0.2f..10f, sec(fx.weightDur)) { v -> vm.setTextFx { it.copy(weightDur = v) } }
        }
        FilterChip(selected = fx.random, onClick = { vm.setTextFx { it.copy(random = !it.random) } }, label = { Text("Рандомизатор символов") })
        if (fx.random) ScrubSlider("Время проявления", fx.randomDur, 0.2f..10f, sec(fx.randomDur)) { v -> vm.setTextFx { it.copy(randomDur = v) } }
    }
}

/** «Обводка»: переключатель, толщина 0..50 px и цвет контура (рисуется под заливкой). */
@Composable
private fun StrokeSection(t: TextState, vm: MainViewModel) {
    var pick by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Обводка", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = t.stroke, onCheckedChange = { vm.setTextStroke(it) })
        }
        if (t.stroke) {
            ScrubSlider("Толщина обводки", t.strokeWidth, 0f..50f, "${t.strokeWidth.toInt()} px") { vm.setTextStrokeWidth(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text("Цвет", fontSize = 12.sp)
                ColorDot(t.strokeColor, chosen = true, size = 36) { pick = true }
                for (c in listOf(0xFF000000L, 0xFFFFFFFFL, 0xFFE53935L, 0xFFFDD835L, 0xFF1E88E5L)) ColorDot(c, chosen = c == t.strokeColor, size = 28) { vm.setTextStrokeColor(c) }
            }
        }
    }
    if (pick) ColorPickerDialog(initial = t.strokeColor, onColor = { vm.setTextStrokeColor(it) }, onEyedropper = {}, onDismiss = { pick = false })
}
