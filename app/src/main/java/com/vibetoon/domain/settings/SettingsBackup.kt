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

    fun encode(values: Map<String, *>): String {
        val prefs = sortedMapOf<String, Any?>()
        for ((k, v) in values) if (k !in SKIP && (v is Boolean || v is Int || v is Long || v is Float || v is String)) prefs[k] = v
        return MiniJson.stringify(linkedMapOf("format" to FORMAT, "version" to VERSION, "prefs" to prefs))
    }

    /** Значения из файла; null - не файл настроек VibeToon или более новая версия. Числа приводятся к Int/Float. */
    fun decode(text: String): Map<String, Any>? {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        if (root["format"] != FORMAT) return null
        if (((root["version"] as? Number)?.toInt() ?: 0) > VERSION) return null
        val prefs = root["prefs"] as? Map<*, *> ?: return null
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
