package com.vibetoon.domain.settings

/**
 * Спринт 30: язык интерфейса без Compose. То же правило, что у AppSettings.effectiveLang: выбранный в настройках
 * язык («ru»/«en»), а если выбран «Системный» (пусто) - русский только для русской системы, иначе английский.
 */
object AppLanguage {
    const val PREFS = "vt_app"
    const val KEY = "lang"

    fun effective(saved: String?, systemLanguage: String): String =
        saved.orEmpty().ifEmpty { if (systemLanguage == "ru") "ru" else "en" }
}

/**
 * Спринт 30: стандартные имена, которые приложение даёт новым объектам. Это русские ключи словаря; при создании
 * объекта имя переводится на текущий язык приложения (ui.i18n.defaultName) и дальше хранится как обычное имя.
 * «Рисунок N», «Фигура: …», «Кость N» и «Сцена N» хранятся по-русски и переводятся при показе (DefaultLayerName,
 * DefaultBoneName, sceneDisplayName), поэтому их здесь нет.
 */
object DefaultNameKeys {
    val ALL: List<String> = listOf(
        "Новый проект", "{} (копия)", "{} копия", "Слой", "Анимация", "Вектор", "Фон", "Группа", "Текст", "Камера",
        "Видео", "{} (звук)", "Символ {}", "Группа {}", "Слияние ({})", "Кадр", "кадр {}"
    )
}
