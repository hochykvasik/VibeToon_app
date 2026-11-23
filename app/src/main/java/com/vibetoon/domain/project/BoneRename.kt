package com.vibetoon.domain.project

/**
 * Переименование кости (пакет 8): имя из свойств кости видно в строке кости на таймлайне, в списке костей
 * («Привязать к кости») и сохраняется в проекте (поле "name" в ProjectJson). Чистая функция - unit-тест.
 */
object BoneRename {
    const val MAX_LEN = 40

    /**
     * Нормализованное новое имя или null, если менять нечего: пустой ввод (кость не остаётся безымянной)
     * или то же имя. Переводы строк и табуляции заменяются пробелами, пробелы по краям обрезаются.
     */
    fun sanitize(input: String, old: String): String? {
        val clean = input.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_LEN).trim()
        if (clean.isEmpty() || clean == old) return null
        return clean
    }

    /** Применить имя к кости с [id] в списке; остальные кости не меняются. */
    fun apply(bones: List<Bone>, id: Int, name: String): List<Bone> = bones.map { if (it.id == id) it.copy(name = name) else it }
}
