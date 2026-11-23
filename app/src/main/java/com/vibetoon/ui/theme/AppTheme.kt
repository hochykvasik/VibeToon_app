package com.vibetoon.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.lerp
import java.util.Locale

/** Один из 8 акцентных цветов: ключ имени (русский текст, переводится таблицей) и цвет. */
class Accent(val name: String, val color: Color)

/** Фиксированный список акцентов (как на референсе): индиго, красный, зелёный, фиолетовый, оранжевый, розовый, голубой, графитовый. */
val ACCENTS: List<Accent> = listOf(
    Accent("Индиго", Color(0xFF4558E8)),
    Accent("Красный", Color(0xFFE84747)),
    Accent("Зелёный", Color(0xFF2FBF62)),
    Accent("Фиолетовый", Color(0xFFAA4BE8)),
    Accent("Оранжевый", Color(0xFFEB9438)),
    Accent("Розовый", Color(0xFFE848C8)),
    Accent("Голубой", Color(0xFF3FA6EA)),
    Accent("Графитовый", Color(0xFF70757D))
)

/** Что выбрано в настройках внешнего вида; хранится в SharedPreferences и мгновенно применяется (все поля - состояние Compose). */
class AppSettings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("vt_app", Context.MODE_PRIVATE)

    /** "ru", "en" или пусто (ещё не выбран - берётся язык системы). */
    var lang by mutableStateOf(p.getString("lang", "") ?: "")
    /** "light", "dark" или "system". */
    var theme by mutableStateOf(p.getString("theme", "system") ?: "system")
    var accent by mutableIntStateOf(p.getInt("accent", 0).coerceIn(0, ACCENTS.size - 1))
    var onboarded by mutableStateOf(p.getBoolean("onboarded", false))
    /** Режим левши: панель инструментов уезжает вправо, панель слоёв влево. */
    var leftHanded by mutableStateOf(p.getBoolean("left", false))
    /** Вспомогательная сетка редактора; не влияет на содержимое проекта и экспорт. */
    var showGrid by mutableStateOf(p.getBoolean("show_grid", false))
    var gridCells by mutableIntStateOf(com.vibetoon.domain.render.CanvasGrid.density(p.getInt("grid_cells", 16)))
    fun saveGrid() { p.edit().putBoolean("show_grid", showGrid).putInt("grid_cells", gridCells).apply() }
    var resetFrameAfterPause by mutableStateOf(p.getBoolean("reset_frame_after_pause", false))
    var compatibleVideoExport by mutableStateOf(p.getBoolean("compatible_video_export", false))
    fun saveExport() { p.edit().putBoolean("compatible_video_export", compatibleVideoExport).putBoolean("perf_hud", perfHud).apply() }
    /** Панель метрик производительности поверх холста (время кадра, отсечённые слои, проходы эффектов). */
    var perfHud by mutableStateOf(p.getBoolean("perf_hud", false))
    /** Векторный режим работы: кисть/ластик автоматически выбирают или создают слой по типу инструмента. */
    var vectorWorkflow by mutableStateOf(p.getBoolean("vector_workflow", false))
    /** Авто-режим пиксельного редактирования: при выборе кисти/ластика слой выбирается или создаётся сам. */
    var autoPixelEdit by mutableStateOf(p.getBoolean("auto_pixel_edit", false))
    /** Ластик и смаз (smudge) действуют только на выбранных слоях с путями (векторных). */
    var onlyEraseSelectedPaths by mutableStateOf(p.getBoolean("only_erase_selected_paths", false))
    /** Временные опорные точки: пивот действует только на время жеста и не сохраняется в проект. */
    var temporaryPivots by mutableStateOf(p.getBoolean("temporary_pivots", false))
    /** Правка опорной точки слоя включена (пивот перетаскивается на холсте). */
    var pivotEditing by mutableStateOf(p.getBoolean("pivot_editing", true))
    var rotateCanvas by mutableStateOf(p.getBoolean("rotate_canvas_2f", true))
    fun savePixelModes() { p.edit().putBoolean("rotate_canvas_2f", rotateCanvas).putBoolean("vector_workflow", vectorWorkflow).putBoolean("auto_pixel_edit", autoPixelEdit).putBoolean("only_erase_selected_paths", onlyEraseSelectedPaths).putBoolean("temporary_pivots", temporaryPivots).putBoolean("pivot_editing", pivotEditing).apply() }
    var checkerThumbnails by mutableStateOf(p.getBoolean("checker_thumbnails", false))
    // До появления настройки звук при скраббинге был включён всегда.
    var audioWhileScrubbing by mutableStateOf(p.getBoolean("audio_while_scrubbing", true))
    fun savePlaybackAndDisplay() { p.edit().putBoolean("reset_frame_after_pause", resetFrameAfterPause).putBoolean("checker_thumbnails", checkerThumbnails).putBoolean("audio_while_scrubbing", audioWhileScrubbing).apply() }
    // Значения по умолчанию сохраняют прежнее поведение редактора.
    var autoKeyframes by mutableStateOf(p.getBoolean("auto_keyframes", false))
    var shapeAssist by mutableStateOf(p.getBoolean("shape_assist", false))
    var touchDetection by mutableStateOf(p.getBoolean("touch_detection", false))
    var doubleTapMultiSelect by mutableStateOf(p.getBoolean("double_tap_multi_select", false))
    var manualSelectionClose by mutableStateOf(p.getBoolean("manual_selection_close", false))
    var showMotionPath by mutableStateOf(p.getBoolean("show_motion_path", false))
    var refocusButton by mutableStateOf(p.getBoolean("refocus_button", false))
    var followCamera by mutableStateOf(p.getBoolean("follow_camera", false))
    var longPressPicker by mutableStateOf(p.getBoolean("long_press_picker", false))
    var fingerDrawing by mutableStateOf(p.getBoolean("finger_drawing", true))
    var cursorEnabled by mutableStateOf(p.getBoolean("cursor_enabled", true))
    var cursorHoverPreview by mutableStateOf(p.getBoolean("cursor_hover_preview", false))
    var quickSelection by mutableStateOf(p.getBoolean("quick_selection", false))
    var selectLayersOnCanvas by mutableStateOf(p.getBoolean("select_layers_canvas", true))
    fun saveSprint68() { p.edit().putBoolean("shape_assist", shapeAssist).putBoolean("touch_detection", touchDetection).putBoolean("double_tap_multi_select", doubleTapMultiSelect).putBoolean("manual_selection_close", manualSelectionClose).putBoolean("show_motion_path", showMotionPath).putBoolean("refocus_button", refocusButton).putBoolean("follow_camera", followCamera).putBoolean("long_press_picker", longPressPicker).putBoolean("finger_drawing", fingerDrawing).putBoolean("cursor_enabled", cursorEnabled).putBoolean("cursor_hover_preview", cursorHoverPreview).putBoolean("select_layers_canvas", selectLayersOnCanvas).putBoolean("quick_selection", quickSelection).apply() }
    var stepFrames by mutableStateOf(p.getBoolean("step_frames", true))
    var quickDeleteDrawing by mutableStateOf(p.getBoolean("quick_delete_drawing", false))
    var selectionMaskVisible by mutableStateOf(p.getBoolean("selection_mask_visible", true))
    var boneColors by mutableStateOf(p.getBoolean("bone_colors", false))
    fun saveSprint67() { p.edit().putBoolean("auto_keyframes", autoKeyframes).putBoolean("step_frames", stepFrames).putBoolean("quick_delete_drawing", quickDeleteDrawing).putBoolean("selection_mask_visible", selectionMaskVisible).putBoolean("bone_colors", boneColors).apply() }
    /** Касание двумя пальцами - Undo, тремя - Redo (по умолчанию выключено, чтобы не мешать жестам камеры). */
    /** Пакет 10: «Import Images as Symbols» - «+ Изображение» сразу создаёт символ с экземпляром. */
    var importImagesAsSymbols by mutableStateOf(p.getBoolean("import_images_as_symbols", false))
    /** Пакет 12: Resolution Limit - картинки при импорте уменьшаются до длинной стороны холста. */
    var resolutionLimit by mutableStateOf(p.getBoolean("import_resolution_limit", false))
    fun saveImport() { p.edit().putBoolean("import_images_as_symbols", importImagesAsSymbols).putBoolean("import_resolution_limit", resolutionLimit).apply() }
    var tap2Undo by mutableStateOf(p.getBoolean("tap2", false))
    var tap3Redo by mutableStateOf(p.getBoolean("tap3", false))
    fun saveGestures() { p.edit().putBoolean("tap2", tap2Undo).putBoolean("tap3", tap3Redo).apply() }
    /** Свои клавиши: действие -> код клавиши Android (только отличающиеся от умолчания). */
    val hotkeys = androidx.compose.runtime.mutableStateMapOf<com.vibetoon.domain.HotkeyAction, Int>().also { m ->
        for (a in com.vibetoon.domain.HotkeyAction.values()) p.getInt("hk_" + a.name, -1).takeIf { it >= 0 }?.let { m[a] = it }
    }
    fun setHotkey(a: com.vibetoon.domain.HotkeyAction, code: Int) {
        if (code == com.vibetoon.domain.Hotkeys.DEFAULTS.getValue(a)) { hotkeys.remove(a); p.edit().remove("hk_" + a.name).apply() }
        else { hotkeys[a] = code; p.edit().putInt("hk_" + a.name, code).apply() }
    }
    fun resetHotkeys() { hotkeys.clear(); val e = p.edit(); for (a in com.vibetoon.domain.HotkeyAction.values()) e.remove("hk_" + a.name); e.apply() }

    val effectiveLang: String get() = lang.ifEmpty { if (Locale.getDefault().language == "ru") "ru" else "en" }

    fun save() {
        p.edit().putString("lang", lang).putString("theme", theme).putInt("accent", accent).putBoolean("onboarded", onboarded).putBoolean("left", leftHanded).apply()
    }

    fun reset() { lang = ""; theme = "system"; accent = 0; save() }

    /** Пакет 10: «Reset» во вкладке «Отображение» - значения отображения по умолчанию. */
    fun resetDisplay() {
        leftHanded = false; showGrid = false; gridCells = com.vibetoon.domain.render.CanvasGrid.density(16)
        checkerThumbnails = false; selectionMaskVisible = true; boneColors = false
        save(); saveGrid(); savePlaybackAndDisplay(); saveSprint67()
    }

    /** Пакет 10: резервная копия всех настроек (JSON) для «Manual Backup». */
    fun backupJson(): String = com.vibetoon.domain.settings.SettingsBackup.encode(p.all)

    /** Пакет 10: «Import Backup» - значения записываются с типами текущих настроек и сразу применяются. */
    fun importBackup(text: String): Boolean {
        val values = com.vibetoon.domain.settings.SettingsBackup.decode(text) ?: return false
        val cur = p.all
        val e = p.edit()
        for ((k, v) in values) {
            when (cur[k]) {
                is Float -> (v as? Number)?.let { e.putFloat(k, it.toFloat()) }
                is Long -> (v as? Number)?.let { e.putLong(k, it.toLong()) }
                is Int -> (v as? Number)?.let { e.putInt(k, it.toInt()) }
                is Boolean -> (v as? Boolean)?.let { e.putBoolean(k, it) }
                is String -> (v as? String)?.let { e.putString(k, it) }
                else -> when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Float -> e.putFloat(k, v); is String -> e.putString(k, v) }
            }
        }
        e.commit()
        reload()
        return true
    }

    /** Перечитать все значения из настроек (после импорта копии). */
    private fun reload() {
        lang = p.getString("lang", "") ?: ""
        theme = p.getString("theme", "system") ?: "system"
        accent = p.getInt("accent", 0).coerceIn(0, ACCENTS.size - 1)
        onboarded = p.getBoolean("onboarded", false)
        leftHanded = p.getBoolean("left", false)
        showGrid = p.getBoolean("show_grid", false)
        gridCells = com.vibetoon.domain.render.CanvasGrid.density(p.getInt("grid_cells", 16))
        resetFrameAfterPause = p.getBoolean("reset_frame_after_pause", false)
        compatibleVideoExport = p.getBoolean("compatible_video_export", false)
        perfHud = p.getBoolean("perf_hud", false)
        vectorWorkflow = p.getBoolean("vector_workflow", false)
        autoPixelEdit = p.getBoolean("auto_pixel_edit", false)
        onlyEraseSelectedPaths = p.getBoolean("only_erase_selected_paths", false)
        temporaryPivots = p.getBoolean("temporary_pivots", false)
        pivotEditing = p.getBoolean("pivot_editing", true)
        rotateCanvas = p.getBoolean("rotate_canvas_2f", true)
        checkerThumbnails = p.getBoolean("checker_thumbnails", false)
        audioWhileScrubbing = p.getBoolean("audio_while_scrubbing", true)
        autoKeyframes = p.getBoolean("auto_keyframes", false)
        shapeAssist = p.getBoolean("shape_assist", false)
        touchDetection = p.getBoolean("touch_detection", false)
        doubleTapMultiSelect = p.getBoolean("double_tap_multi_select", false)
        manualSelectionClose = p.getBoolean("manual_selection_close", false)
        showMotionPath = p.getBoolean("show_motion_path", false)
        refocusButton = p.getBoolean("refocus_button", false)
        followCamera = p.getBoolean("follow_camera", false)
        longPressPicker = p.getBoolean("long_press_picker", false)
        fingerDrawing = p.getBoolean("finger_drawing", true)
        cursorEnabled = p.getBoolean("cursor_enabled", true)
        cursorHoverPreview = p.getBoolean("cursor_hover_preview", false)
        quickSelection = p.getBoolean("quick_selection", false)
        selectLayersOnCanvas = p.getBoolean("select_layers_canvas", true)
        stepFrames = p.getBoolean("step_frames", true)
        quickDeleteDrawing = p.getBoolean("quick_delete_drawing", false)
        selectionMaskVisible = p.getBoolean("selection_mask_visible", true)
        boneColors = p.getBoolean("bone_colors", false)
        importImagesAsSymbols = p.getBoolean("import_images_as_symbols", false)
        resolutionLimit = p.getBoolean("import_resolution_limit", false)
        tap2Undo = p.getBoolean("tap2", false)
        tap3Redo = p.getBoolean("tap3", false)
        hotkeys.clear()
        for (a in com.vibetoon.domain.HotkeyAction.values()) p.getInt("hk_" + a.name, -1).takeIf { it >= 0 }?.let { hotkeys[a] = it }
    }
}

val LocalAppSettings = compositionLocalOf<AppSettings?> { null }

private fun mix(a: Color, b: Color, t: Float) = lerp(a, b, t)

/** Цветовая схема M3 из акцентного цвета: светлая (светлые панели, тёмный текст) или тёмная (глубокие серые панели, светлый текст). */
fun schemeFor(accent: Color, dark: Boolean): ColorScheme {
    val white = Color.White
    val black = Color.Black
    return if (!dark) lightColorScheme(
        primary = accent,
        onPrimary = if (accent.luminance() > 0.55f) black else white,
        primaryContainer = mix(accent, white, 0.82f),
        onPrimaryContainer = mix(accent, black, 0.55f),
        secondary = mix(accent, Color(0xFF5F6368), 0.55f),
        onSecondary = white,
        secondaryContainer = mix(accent, white, 0.88f),
        onSecondaryContainer = mix(accent, black, 0.6f),
        tertiary = mix(accent, Color(0xFF6D4C41), 0.4f),
        background = mix(white, accent, 0.025f),
        onBackground = Color(0xFF1B1B1F),
        surface = mix(white, accent, 0.03f),
        onSurface = Color(0xFF1B1B1F),
        surfaceVariant = mix(white, accent, 0.11f),
        onSurfaceVariant = Color(0xFF45464F),
        outline = Color(0xFF767680)
    ) else darkColorScheme(
        primary = mix(accent, white, 0.3f),
        onPrimary = mix(accent, black, 0.75f),
        primaryContainer = mix(accent, black, 0.5f),
        onPrimaryContainer = mix(accent, white, 0.82f),
        secondary = mix(accent, white, 0.55f),
        onSecondary = mix(accent, black, 0.8f),
        secondaryContainer = mix(accent, black, 0.62f),
        onSecondaryContainer = mix(accent, white, 0.85f),
        tertiary = mix(accent, white, 0.5f),
        background = mix(Color(0xFF111113), accent, 0.04f),
        onBackground = Color(0xFFE6E6EA),
        surface = mix(Color(0xFF17171A), accent, 0.04f),
        onSurface = Color(0xFFE6E6EA),
        surfaceVariant = mix(Color(0xFF2A2A2F), accent, 0.08f),
        onSurfaceVariant = Color(0xFFC6C6D0),
        outline = Color(0xFF8E8E98)
    )
}

/** Единый премиальный стиль скруглений: панели и диалоги 16-24 dp. */
val VibeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun isDarkNow(settings: AppSettings): Boolean = when (settings.theme) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun VibeTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDarkNow(settings)
    MaterialTheme(colorScheme = schemeFor(ACCENTS[settings.accent].color, dark), shapes = VibeShapes, content = content)
}
