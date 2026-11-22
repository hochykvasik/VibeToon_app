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
    /** Касание двумя пальцами - Undo, тремя - Redo (по умолчанию выключено, чтобы не мешать жестам камеры). */
    var tap2Undo by mutableStateOf(p.getBoolean("tap2", false))
    var tap3Redo by mutableStateOf(p.getBoolean("tap3", false))
    fun saveGestures() { p.edit().putBoolean("tap2", tap2Undo).putBoolean("tap3", tap3Redo).apply() }
    /** Поворот холста двумя пальцами (только вид, на экспорт не влияет). */
    var rotateCanvas by mutableStateOf(p.getBoolean("rot2", true))
    /** Layer Pivot Editing: на выделенном слое видна опорная точка, её можно перетащить. */
    var pivotEditing by mutableStateOf(p.getBoolean("pivEdit", true))
    /** Temporary Pivots: масштаб/поворот двумя пальцами вокруг точки между пальцами, а не вокруг опорной точки слоя. */
    var temporaryPivots by mutableStateOf(p.getBoolean("pivTemp", false))
    /** Auto Pixel Edit Mode: выбор кисти/ластика сам переключает на пиксельный слой (выбирает или создаёт). */
    var autoPixelEdit by mutableStateOf(p.getBoolean("autoPx", true))
    /** Only Erase/Smudge Selected Path Layers: ластик/палец векторного режима трогают только выделенные контуры. */
    var onlySelectedPath by mutableStateOf(p.getBoolean("onlySelPath", false))
    /** Vector Workflow: Auto Pixel Edit Mode для кисти выбирает/создаёт векторный слой, а не пиксельный. */
    var vectorWorkflow by mutableStateOf(p.getBoolean("vecFlow", false))
    /** Import Images as Symbols: импортированная картинка сразу становится символом (префабом). */
    var importAsSymbols by mutableStateOf(p.getBoolean("impSym", false))
    fun saveEditor() {
        p.edit().putBoolean("rot2", rotateCanvas).putBoolean("pivEdit", pivotEditing).putBoolean("pivTemp", temporaryPivots)
            .putBoolean("autoPx", autoPixelEdit).putBoolean("onlySelPath", onlySelectedPath)
            .putBoolean("vecFlow", vectorWorkflow).putBoolean("impSym", importAsSymbols).apply()
    }
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
