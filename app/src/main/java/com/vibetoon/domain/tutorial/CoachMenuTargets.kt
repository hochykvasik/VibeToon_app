package com.vibetoon.domain.tutorial

/**
 * Спринт 57: цели подсказок внутри меню «Действия». Меню - отдельный Popup со своими координатами, поэтому такие цели
 * подсвечиваются в самом пункте меню (а не рамкой CoachOverlay), а редактор сам открывает меню на этом шаге и
 * закрывает его, когда урок ушёл на другой шаг.
 */
object CoachMenuTargets {
    /** Пункты меню «Действия», на которые умеют указывать уроки (подписи - как в интерфейсе). */
    val ACTIONS_MENU: List<String> = listOf("Тестовый PSD", "Окно референса…")

    fun inActionsMenu(target: String?): Boolean = target != null && target in ACTIONS_MENU

    /** Что сделать с меню при смене шага: true - открыть, false - закрыть (меню открыл урок), null - не трогать. */
    fun menuAction(target: String?, openedByCoach: Boolean): Boolean? = when {
        inActionsMenu(target) -> true
        openedByCoach -> false
        else -> null
    }
}
