package com.vibetoon.domain.export

/**
 * Спринт 42: тексты уведомления фоновой работы (импорт/экспорт) в шторке Android. Раньше было только «Экспорт» и
 * полоска - непонятно, что за приложение и сколько осталось. Теперь в заголовке имя приложения и процент, в тексте -
 * кадр из скольких и подсказка, что приложение можно свернуть. Строки - русские исходники; служба переводит их
 * так же, как интерфейс (RU/EN, шаблоны с {}), поэтому числа подставляются в готовые пары tools/i18n/pairs2.py.
 */
object WorkNotice {
    const val APP_NAME = "VibeToon"

    enum class Kind { EXPORT, IMPORT, OTHER }

    /** Вид работы по заголовку, с которым её начал MainViewModel (BackgroundWork.begin). */
    fun kind(title: String): Kind = when (title) {
        "Экспорт" -> Kind.EXPORT
        "Импорт" -> Kind.IMPORT
        else -> Kind.OTHER
    }

    /** Заголовок: «VibeToon: экспорт 42 %»; без процента (ещё не начался или импорт) - без числа. */
    fun title(kind: Kind, percent: Int?): String = when (kind) {
        // имя приложения - прямо в литерале: строка целиком должна совпасть с парой словаря RU/EN
        Kind.EXPORT -> if (percent != null) "VibeToon: экспорт ${percent.coerceIn(0, 100)} %" else "VibeToon: экспорт"
        Kind.IMPORT -> if (percent != null) "VibeToon: импорт ${percent.coerceIn(0, 100)} %" else "VibeToon: импорт"
        Kind.OTHER -> "VibeToon: фоновая работа"
    }

    /** Строка состояния: какой кадр из скольких (если кадров больше одного), иначе этап работы. */
    fun status(kind: Kind, done: Int, total: Int): String = when {
        kind == Kind.EXPORT && total > 1 -> "Кадр ${done.coerceIn(0, total)} из $total"
        kind == Kind.EXPORT -> "Подготовка кадров"
        kind == Kind.IMPORT -> "Подготовка медиа"
        else -> "Работа продолжается в фоне"
    }

    /** Короткий текст (свёрнутое уведомление). */
    const val HINT = "можно свернуть приложение"

    /** Развёрнутое уведомление: вторая строка. */
    const val TAP_HINT = "Нажмите, чтобы вернуться в VibeToon"

    /** Если работ несколько - сколько ещё ждёт (подпись в шапке). Одна - ничего. */
    fun more(count: Int): String? = if (count > 1) "Задач: $count" else null
}
