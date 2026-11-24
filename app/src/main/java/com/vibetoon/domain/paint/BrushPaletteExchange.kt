package com.vibetoon.domain.paint

import com.vibetoon.domain.project.MiniJson

/**
 * Спринт 39: обмен своими кистями и палитрами через файлы (и хранение их в настройках «vt_app»,
 * поэтому они попадают в резервную копию настроек). Чистые функции - unit-тест.
 *
 * Кисти: `{"format":"vibetoon-brushes","version":1,"brushes":[{"id","title","params":{...}}]}`.
 * Палитры: `{"format":"vibetoon-palettes","version":1,"palettes":[{"name","colors":["#RRGGBB"|"#AARRGGBB"]}]}`,
 * при импорте также принимаются палитры GIMP (.gpl) и простой список HEX-цветов.
 *
 * Проверка файла: размер, JSON, формат, версия, каждая запись отдельно. Повреждённые записи пропускаются
 * (их число попадает в отчёт), числа приводятся к допустимым диапазонам, неизвестные режимы - ошибка записи.
 */
enum class ExchangeError(val ru: String) {
    EMPTY("Файл пустой"),
    TOO_BIG("Файл слишком большой (больше 1 МБ)"),
    NOT_JSON("Файл повреждён: это не JSON"),
    NOT_BRUSHES("Это не файл кистей VibeToon"),
    NOT_PALETTE("Это не файл палитры (нужен .json VibeToon, .gpl GIMP или список HEX)"),
    PALETTE_IN_BRUSHES("Это файл палитры: импортируйте его в окне цвета"),
    BRUSHES_IN_PALETTE("Это файл кистей: импортируйте его в меню кистей"),
    NEWER_VERSION("Файл из более новой версии VibeToon: обновите приложение"),
    NO_BRUSHES("В файле нет ни одной исправной кисти"),
    NO_COLORS("В файле нет ни одного цвета"),
    NOTHING_TO_EXPORT("Нечего экспортировать")
}

/** Итог импорта: значения (пусто при ошибке), сколько записей пропущено и ошибка файла целиком. */
data class ExchangeResult<T>(val items: List<T>, val skipped: Int = 0, val error: ExchangeError? = null) {
    val ok: Boolean get() = error == null && items.isNotEmpty()
}

/** Своя палитра: имя и цвета ARGB (без повторов, не больше [PaletteExchange.MAX_COLORS]). */
data class UserPalette(val name: String, val colors: List<Long>)

object BrushExchange {
    const val FORMAT = "vibetoon-brushes"
    const val VERSION = 1
    const val MAX_BYTES = 1 shl 20
    const val MAX_BRUSHES = 100
    const val MAX_TITLE = 40
    /** Ключ «vt_app»: свои кисти в том же формате, что и файл экспорта. */
    const val PREF_KEY = "custom_brushes"
    const val ID_PREFIX = "user_"

    fun isCustom(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** Чтение файла не больше [MAX_BYTES] (UTF-8, BOM убирается); null - файл слишком большой. */
    fun readLimited(input: java.io.InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > MAX_BYTES) return null
        }
        return buf.toByteArray().decodeToString().trimStart('\uFEFF')
    }

    fun paramsToMap(p: BrushParams): Map<String, Any?> = linkedMapOf(
        "size" to p.size.toDouble(), "spacing" to p.spacing.toDouble(), "minSize" to p.minSizePercent.toDouble(),
        "flow" to p.flow.toDouble(), "opacity" to p.opacity.toDouble(), "hardness" to p.hardness.toDouble(),
        "mode" to p.mode.name, "shape" to p.shape.name,
        "grain" to p.grain.name, "grainMode" to p.grainMode.name, "grainScale" to p.grainScale.toDouble(), "grainContrast" to p.grainContrast.toDouble(),
        "roundness" to p.roundness.toDouble(), "curve" to p.curve, "rotation" to p.rotation.name,
        "taper" to p.taperPx.toDouble(), "pixelSnap" to p.pixelSnap
    )

    private inline fun <reified E : Enum<E>> enumOf(v: Any?, def: E): E? =
        if (v == null) def else (v as? String)?.let { s -> enumValues<E>().firstOrNull { it.name.equals(s, ignoreCase = true) } }

    private fun num(o: Map<*, *>, key: String, def: Float, min: Float, max: Float): Float? {
        val v = o[key] ?: return def
        val d = (v as? Number)?.toDouble() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return d.toFloat().coerceIn(min, max)
    }

    /** Настройки кисти из JSON; null - запись повреждена (не числа, неизвестный режим/форма/зерно). Цвет не переносится. */
    fun paramsFromMap(o: Map<*, *>, presetId: String): BrushParams? {
        val d = BrushParams()
        return BrushParams(
            size = num(o, "size", d.size, BrushMath.MIN_SIZE, BrushMath.MAX_SIZE) ?: return null,
            spacing = num(o, "spacing", d.spacing, 0.01f, 3f) ?: return null,
            minSizePercent = num(o, "minSize", d.minSizePercent, 0f, 1f) ?: return null,
            flow = num(o, "flow", d.flow, 0.01f, 1f) ?: return null,
            opacity = num(o, "opacity", d.opacity, 0.01f, 1f) ?: return null,
            hardness = num(o, "hardness", d.hardness, 0f, 1f) ?: return null,
            mode = enumOf(o["mode"], d.mode) ?: return null,
            shape = enumOf(o["shape"], d.shape) ?: return null,
            grain = enumOf(o["grain"], d.grain) ?: return null,
            grainMode = enumOf(o["grainMode"], d.grainMode) ?: return null,
            grainScale = num(o, "grainScale", d.grainScale, 0.1f, 10f) ?: return null,
            grainContrast = num(o, "grainContrast", d.grainContrast, 0f, 4f) ?: return null,
            roundness = num(o, "roundness", d.roundness, 0.05f, 1f) ?: return null,
            curve = (num(o, "curve", d.curve.toFloat(), 0f, 1f) ?: return null).toInt(),
            rotation = enumOf(o["rotation"], d.rotation) ?: return null,
            taperPx = num(o, "taper", d.taperPx, 0f, 2000f) ?: return null,
            pixelSnap = (o["pixelSnap"] ?: false) as? Boolean ?: return null,
            presetId = presetId
        )
    }

    fun encode(list: List<BrushPreset>): String = MiniJson.stringify(linkedMapOf(
        "format" to FORMAT, "version" to VERSION,
        "brushes" to list.map { linkedMapOf("id" to it.id, "title" to it.title, "params" to paramsToMap(it.params)) }
    ))

    fun cleanTitle(t: String?, fallback: String): String =
        t.orEmpty().filter { !it.isISOControl() }.trim().take(MAX_TITLE).ifEmpty { fallback }

    /**
     * Разбор файла кистей. [existingIds] - уже занятые id (встроенные и свои): совпадающие получают новый id,
     * поэтому импорт никогда не затирает существующие кисти. Встроенные id из чужого файла тоже переименовываются.
     */
    fun decode(text: String, existingIds: Set<String> = emptySet()): ExchangeResult<BrushPreset> {
        if (text.isBlank()) return ExchangeResult(emptyList(), error = ExchangeError.EMPTY)
        if (text.length > MAX_BYTES) return ExchangeResult(emptyList(), error = ExchangeError.TOO_BIG)
        val root = runCatching { MiniJson.parse(text.trimStart('\uFEFF')) }.getOrNull()
            ?: return ExchangeResult(emptyList(), error = if (PaletteExchange.looksLikeTextPalette(text)) ExchangeError.PALETTE_IN_BRUSHES else ExchangeError.NOT_JSON)
        val o = root as? Map<*, *> ?: return ExchangeResult(emptyList(), error = ExchangeError.NOT_BRUSHES)
        if (o["format"] == PaletteExchange.FORMAT) return ExchangeResult(emptyList(), error = ExchangeError.PALETTE_IN_BRUSHES)
        if (o["format"] != FORMAT) return ExchangeResult(emptyList(), error = ExchangeError.NOT_BRUSHES)
        if (((o["version"] as? Number)?.toInt() ?: 0) > VERSION) return ExchangeResult(emptyList(), error = ExchangeError.NEWER_VERSION)
        val arr = o["brushes"] as? List<*> ?: return ExchangeResult(emptyList(), error = ExchangeError.NOT_BRUSHES)
        val taken = HashSet(existingIds)
        for (b in BrushPresets.all()) taken += b.id
        val out = ArrayList<BrushPreset>()
        var skipped = 0
        for (item in arr) {
            val m = item as? Map<*, *>
            val pm = m?.get("params") as? Map<*, *>
            if (m == null || pm == null || out.size >= MAX_BRUSHES) { skipped++; continue }
            var id = (m["id"] as? String)?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }?.take(48).orEmpty()
            if (!isCustom(id)) id = ID_PREFIX + id
            if (id == ID_PREFIX || id in taken) id = freshId(taken)
            val params = paramsFromMap(pm, id)
            if (params == null) { skipped++; continue }
            taken += id
            out += BrushPreset(id, cleanTitle(m["title"] as? String, if (params.mode == StrokeMode.ERASE) "Свой ластик" else "Своя кисть"), params)
        }
        return if (out.isEmpty()) ExchangeResult(emptyList(), skipped, ExchangeError.NO_BRUSHES) else ExchangeResult(out, skipped)
    }

    fun freshId(taken: Set<String>): String {
        var n = 1
        while ("$ID_PREFIX$n" in taken) n++
        return "$ID_PREFIX$n"
    }

    /** Свои кисти из настроек (тихо: повреждённая строка - пустой список). */
    fun load(stored: String?): List<BrushPreset> = if (stored.isNullOrBlank()) emptyList() else decode(stored).items

    /** Добавление в список своих: не больше [MAX_BRUSHES], новые в конце. */
    fun merge(current: List<BrushPreset>, added: List<BrushPreset>): List<BrushPreset> = (current + added).distinctBy { it.id }.take(MAX_BRUSHES)

    /** Текущие настройки кисти/ластика как своя кисть (цвет не сохраняется). */
    fun fromCurrent(p: BrushParams, title: String, taken: Set<String>): BrushPreset {
        val id = freshId(taken + BrushPresets.all().map { it.id })
        return BrushPreset(id, cleanTitle(title, "Своя кисть"), p.copy(color = BrushParams().color, presetId = id))
    }

    fun suggestName(params: BrushParams, current: List<BrushPreset>): String {
        val base = if (params.mode == StrokeMode.ERASE) "Свой ластик" else "Своя кисть"
        val n = current.count { (it.params.mode == StrokeMode.ERASE) == (params.mode == StrokeMode.ERASE) } + 1
        return "$base $n"
    }

    /** Текст итога импорта для сообщения (шаблоны переводятся i18n). */
    fun report(r: ExchangeResult<BrushPreset>): String = when {
        r.error != null -> r.error.ru
        r.skipped > 0 -> "Импортировано кистей: ${r.items.size}, пропущено повреждённых: ${r.skipped}"
        else -> "Импортировано кистей: ${r.items.size}"
    }
}

object PaletteExchange {
    const val FORMAT = "vibetoon-palettes"
    const val VERSION = 1
    const val MAX_COLORS = 64
    const val MAX_PALETTES = 50
    const val MAX_NAME = 40
    /** Ключ «vt_app»: свои палитры в том же формате, что и файл экспорта. */
    const val PREF_KEY = "user_palettes"

    fun hex(c: Long): String {
        val a = (c ushr 24) and 0xFF
        val rgb = String.format("%06X", c and 0xFFFFFF)
        return if (a == 0xFFL) "#$rgb" else "#" + String.format("%02X", a) + rgb
    }

    /** #RGB, #RRGGBB, #AARRGGBB (с # или без); null - не цвет. */
    fun parseHex(s: String): Long? {
        val t = s.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (t.isEmpty() || t.any { it.digitToIntOrNull(16) == null }) return null
        return when (t.length) {
            3 -> 0xFF000000L or t.map { it.toString().repeat(2) }.joinToString("").toLong(16)
            6 -> 0xFF000000L or t.toLong(16)
            8 -> t.toLong(16)
            else -> null
        }
    }

    fun encode(list: List<UserPalette>): String = MiniJson.stringify(linkedMapOf(
        "format" to FORMAT, "version" to VERSION,
        "palettes" to list.map { linkedMapOf("name" to it.name, "colors" to it.colors.map(::hex)) }
    ))

    fun cleanName(t: String?, fallback: String): String =
        t.orEmpty().filter { !it.isISOControl() }.trim().take(MAX_NAME).ifEmpty { fallback }

    private fun normColors(cs: List<Long>): List<Long> = cs.map { it and 0xFFFFFFFFL }.distinct().take(MAX_COLORS)

    fun looksLikeTextPalette(text: String): Boolean {
        val t = text.trimStart('\uFEFF').trimStart()
        return t.startsWith("GIMP Palette") || t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(3).toList().let { l -> l.isNotEmpty() && l.all { line -> line.split(',', ';', ' ', '\t').filter { it.isNotBlank() }.all { parseHex(it) != null } } }
    }

    /** Палитра GIMP: «GIMP Palette», «Name: ...», строки «R G B имя», комментарии с #. */
    fun decodeGpl(text: String, fallbackName: String): ExchangeResult<UserPalette> {
        var name = fallbackName
        val colors = ArrayList<Long>()
        var skipped = 0
        for ((i, raw) in text.trimStart('\uFEFF').lines().withIndex()) {
            val line = raw.trim()
            if (i == 0 || line.isEmpty() || line.startsWith("#") || line.startsWith("Columns:")) continue
            if (line.startsWith("Name:")) { name = cleanName(line.removePrefix("Name:"), fallbackName); continue }
            val parts = line.split(Regex("\\s+"))
            val rgb = parts.take(3).map { it.toIntOrNull() }
            if (rgb.size == 3 && rgb.all { it != null && it in 0..255 }) colors += 0xFF000000L or (rgb[0]!!.toLong() shl 16) or (rgb[1]!!.toLong() shl 8) or rgb[2]!!.toLong()
            else skipped++
        }
        val cs = normColors(colors)
        return if (cs.isEmpty()) ExchangeResult(emptyList(), skipped, ExchangeError.NO_COLORS) else ExchangeResult(listOf(UserPalette(name, cs)), skipped)
    }

    /** Простой список HEX (через запятую, пробел или с новой строки). */
    fun decodeHexList(text: String, fallbackName: String): ExchangeResult<UserPalette> {
        val tokens = text.trimStart('\uFEFF').split(',', ';', ' ', '\t', '\n', '\r').filter { it.isNotBlank() }
        val cs = tokens.mapNotNull(::parseHex)
        val skipped = tokens.size - cs.size
        if (cs.isEmpty()) return ExchangeResult(emptyList(), skipped, ExchangeError.NOT_PALETTE)
        return ExchangeResult(listOf(UserPalette(fallbackName, normColors(cs))), skipped)
    }

    /** Разбор файла палитр (JSON VibeToon, GIMP .gpl или список HEX). */
    fun decode(text: String, fallbackName: String = "Палитра"): ExchangeResult<UserPalette> {
        if (text.isBlank()) return ExchangeResult(emptyList(), error = ExchangeError.EMPTY)
        if (text.length > BrushExchange.MAX_BYTES) return ExchangeResult(emptyList(), error = ExchangeError.TOO_BIG)
        val t = text.trimStart('\uFEFF').trimStart()
        if (t.startsWith("GIMP Palette")) return decodeGpl(t, fallbackName)
        if (!t.startsWith("{") && !t.startsWith("[")) return decodeHexList(t, fallbackName)
        val root = runCatching { MiniJson.parse(t) }.getOrNull() ?: return ExchangeResult(emptyList(), error = ExchangeError.NOT_JSON)
        val o = root as? Map<*, *> ?: return ExchangeResult(emptyList(), error = ExchangeError.NOT_PALETTE)
        if (o["format"] == BrushExchange.FORMAT) return ExchangeResult(emptyList(), error = ExchangeError.BRUSHES_IN_PALETTE)
        if (o["format"] != FORMAT) return ExchangeResult(emptyList(), error = ExchangeError.NOT_PALETTE)
        if (((o["version"] as? Number)?.toInt() ?: 0) > VERSION) return ExchangeResult(emptyList(), error = ExchangeError.NEWER_VERSION)
        val arr = o["palettes"] as? List<*> ?: return ExchangeResult(emptyList(), error = ExchangeError.NOT_PALETTE)
        val out = ArrayList<UserPalette>()
        var skipped = 0
        for (item in arr) {
            val m = item as? Map<*, *>
            val cl = m?.get("colors") as? List<*>
            if (m == null || cl == null || out.size >= MAX_PALETTES) { skipped++; continue }
            val cs = cl.mapNotNull { (it as? String)?.let(::parseHex) }
            if (cs.isEmpty()) { skipped++; continue }
            out += UserPalette(cleanName(m["name"] as? String, "$fallbackName ${out.size + 1}"), normColors(cs))
        }
        return if (out.isEmpty()) ExchangeResult(emptyList(), skipped, ExchangeError.NO_COLORS) else ExchangeResult(out, skipped)
    }

    fun load(stored: String?): List<UserPalette> = if (stored.isNullOrBlank()) emptyList() else decode(stored).items

    /** Добавление палитр: одинаковые имена получают номер «(2)», всего не больше [MAX_PALETTES]. */
    fun merge(current: List<UserPalette>, added: List<UserPalette>): List<UserPalette> {
        val out = current.toMutableList()
        for (p in added) {
            if (out.size >= MAX_PALETTES) break
            var name = p.name
            var n = 2
            while (out.any { it.name == name }) name = "${p.name} (${n++})".take(MAX_NAME + 6)
            out += p.copy(name = name)
        }
        return out
    }

    fun report(r: ExchangeResult<UserPalette>): String = when {
        r.error != null -> r.error.ru
        r.skipped > 0 -> "Импортировано палитр: ${r.items.size}, цветов: ${r.items.sumOf { it.colors.size }}, пропущено: ${r.skipped}"
        else -> "Импортировано палитр: ${r.items.size}, цветов: ${r.items.sumOf { it.colors.size }}"
    }
}
