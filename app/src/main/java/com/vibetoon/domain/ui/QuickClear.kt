package com.vibetoon.domain.ui

/**
 * Спринт 62: быстрая очистка выбранного слоя кнопкой рядом с «Отменить / Повторить» в верхней строке редактора
 * (на телефоне в портрете раньше приходилось открывать панель слоёв → меню слоя → «Очистить» и закрывать панель).
 *
 * Защита от случайного нажатия: кнопка открывает подтверждение прямо под собой («Очистить слой «…»?»), а сама
 * очистка - одна запись истории, «Отменить» рядом возвращает рисунок. Кнопка неактивна, когда чистить нечего или
 * нельзя. Чистые функции - Sprint62Test.
 */
object QuickClear {
    enum class Kind { NONE, RASTER, VECTOR, OTHER }
    enum class State { NO_LAYER, NOT_PAINTABLE, LOCKED, PLAYING, EMPTY, READY }

    /**
     * Состояние кнопки для выбранного слоя: [kind] - рисованный слой (картинка/PSD), векторный или другой (текст,
     * видео, группа, кость); [locked] - замок; [playing] - идёт воспроизведение; [vectorCount] - примитивов у векторного.
     */
    fun state(kind: Kind, locked: Boolean, playing: Boolean, vectorCount: Int = 0): State = when {
        kind == Kind.NONE -> State.NO_LAYER
        kind == Kind.OTHER -> State.NOT_PAINTABLE
        playing -> State.PLAYING
        locked -> State.LOCKED
        kind == Kind.VECTOR && vectorCount <= 0 -> State.EMPTY
        else -> State.READY
    }

    fun enabled(s: State): Boolean = s == State.READY

    /** Пояснение, почему очистить нельзя (подсказка кнопки и сообщение при нажатии); у READY - вопрос подтверждения. */
    fun reason(s: State): String = when (s) {
        State.NO_LAYER -> "Выберите слой, чтобы очистить его"
        State.NOT_PAINTABLE -> "Очистить можно рисованный или векторный слой"
        State.LOCKED -> "Слой заблокирован"
        State.PLAYING -> "Остановите воспроизведение"
        State.EMPTY -> "Слой уже пуст"
        State.READY -> "Очистить слой"
    }
}
