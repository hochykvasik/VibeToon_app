package com.vibetoon.domain.settings

import com.vibetoon.domain.project.MiniJson

/**
 * Пакет 10: резервная копия настроек приложения (вкладка «Backup»): все значения SharedPreferences «vt_app»
 * (флажки, числа, строки) в один JSON-файл и обратно. Служебные ключи (онбординг) не переносятся.
 * Чистые функции - unit-тест.
 */
object SettingsBackup {
    const val FORMAT = "vibetoon-settings"
    const val VERSION = 1
    private val SKIP = setOf("onboarded")

    /**
     * [colors] - спринт 39: настройки окна цвета («vt_colors»: последние цвета, режим Круг/RGB/HSB) отдельным
     * необязательным разделом. Свои кисти и палитры лежат в «vt_app» и попадают в «prefs» сами.
     */
    fun encode(values: Map<String, *>, colors: Map<String, *> = emptyMap<String, Any?>()): String {
        val root = linkedMapOf<String, Any?>("format" to FORMAT, "version" to VERSION, "prefs" to filtered(values))
        val c = filtered(colors)
        if (c.isNotEmpty()) root["colors"] = c
        return MiniJson.stringify(root)
    }

    private fun filtered(values: Map<String, *>): Map<String, Any?> {
        val out = sortedMapOf<String, Any?>()
        for ((k, v) in values) if (k !in SKIP && (v is Boolean || v is Int || v is Long || v is Float || v is String)) out[k] = v
        return out
    }

    /** Раздел «colors» (может отсутствовать в старых копиях - тогда пусто). */
    fun decodeColors(text: String): Map<String, Any> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyMap()
        if (root["format"] != FORMAT) return emptyMap()
        return values(root["colors"] as? Map<*, *> ?: return emptyMap())
    }

    /** Значения из файла; null - не файл настроек VibeToon или более новая версия. Числа приводятся к Int/Float. */
    fun decode(text: String): Map<String, Any>? {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        if (root["format"] != FORMAT) return null
        if (((root["version"] as? Number)?.toInt() ?: 0) > VERSION) return null
        val prefs = root["prefs"] as? Map<*, *> ?: return null
        return values(prefs)
    }

    private fun values(prefs: Map<*, *>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in prefs) {
            val key = k as? String ?: continue
            if (key in SKIP) continue
            when (v) {
                is Boolean -> out[key] = v
                is String -> out[key] = v
                is Number -> {
                    val d = v.toDouble()
                    out[key] = if (d == Math.floor(d) && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) d.toInt() else d.toFloat()
                }
            }
        }
        return out
    }
}
