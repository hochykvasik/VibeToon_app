package com.vibetoon.domain.anim

/**
 * Быстрое снятие выделения на таймлайне (пакет 8): ключи, маркеры. Раньше снять выделение можно было только
 * повторным долгим нажатием. Теперь короткий тап по уже выделенному элементу снимает с него выделение, тап по
 * пустому месту дорожек снимает всё; долгое нажатие по-прежнему добавляет к выделению и тянет группу.
 */
object QuickDeselect {
    /**
     * Новое выделение после короткого тапа или null - тап не меняет выделение (работает обычное действие тапа:
     * выбор слоя, переход к ключу). [hit] - элемент под пальцем (null - пустое место).
     */
    fun <T> afterTap(selection: Set<T>, hit: T?): Set<T>? = when {
        selection.isEmpty() -> null
        hit == null -> emptySet()
        hit in selection -> selection - hit
        else -> null
    }

    /**
     * Кость, к которой относятся выделенные ключи: если все ключи костей в выделении принадлежат одной кости,
     * свойства должны показывать именно её (раньше долгое нажатие по ключу «Кости 2» оставляло в свойствах «Кость 1»).
     */
    fun focusBone(keys: Set<Pair<Int, Int>>, boneIds: Collection<Int>): Int? {
        val owners = keys.asSequence().map { it.first }.filter { it in boneIds }.distinct().take(2).toList()
        return owners.singleOrNull()
    }
}
