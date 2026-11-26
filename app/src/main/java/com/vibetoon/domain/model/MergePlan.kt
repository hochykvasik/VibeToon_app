package com.vibetoon.domain.model

/**
 * Спринт 54: план «Слить в один слой» (кнопка мультивыбора, «Слить вниз», «Слить все видимые»).
 *
 * Раньше «Слить вместе» всегда делало снимок текущего кадра в НОВЫЙ слой «Слияние (N)» в корне: ключи, кости и
 * деформеры пропадали, слой уезжал из своей группы. Теперь основной путь - как Merge Down в ToonSquid: верхние слои
 * накладываются в пиксели НИЖНЕГО выбранного слоя, а он сохраняет id, группу, ключи, привязку к кости, сетку и
 * эффекты (блик, слитый в анимированную чёлку, двигается вместе с ней). Снимок остаётся запасным путём для слоёв,
 * которые нельзя слить в пиксели (вектор, текст, видео, слои с эффектами/деформерами сверху). Группы никогда не
 * создаются: выбранная группа - отказ с подсказкой (раньше её молча пропускали). Чистая функция - unit-тест.
 */
object MergePlan {
    /** Узел в выделении. Список передаётся снизу вверх (видимый порядок). */
    data class Node(
        val id: Int,
        val isGroup: Boolean = false,
        /** Растровые пиксели правятся напрямую (картинка без рисунков покадровки или слой PSD). */
        val pixel: Boolean = true,
        /** Может быть нижним (целевым): обычный режим наложения, без цветокоррекции и обрезки, не служит маской. */
        val targetOk: Boolean = true,
        /** Может быть верхним: без эффектов, деформеров, маски, пути, обрезки и цветокоррекции. */
        val plainTop: Boolean = true,
        val visible: Boolean = true,
        val locked: Boolean = false,
        /** Есть ключи, привязка к кости/слою или деформеры. */
        val animated: Boolean = false
    )

    sealed class Result {
        data class Refuse(val reason: String) : Result()
        /** Пиксели [tops] (снизу вверх) накладываются на [target]; [topsAnimated] - ключи верхних застынут на текущем кадре. */
        data class Pixels(val target: Int, val tops: List<Int>, val targetAnimated: Boolean, val topsAnimated: Boolean) : Result()
        /** Запасной путь: снимок текущего кадра; [losesAnimation] - анимация будет запечена. */
        data class Snapshot(val leaves: List<Int>, val losesAnimation: Boolean) : Result()
    }

    const val GROUP = "Группы не сливаются: откройте группу и отметьте нужные слои внутри"
    const val TOO_FEW = "Для слияния нужно минимум два обычных слоя"
    const val LOCKED = "Слой заблокирован"
    const val HIDDEN = "Скрытый слой не сливается: включите глаз у всех сливаемых слоёв"

    fun plan(bottomUp: List<Node>): Result {
        if (bottomUp.any { it.isGroup }) return Result.Refuse(GROUP)
        if (bottomUp.size < 2) return Result.Refuse(TOO_FEW)
        if (bottomUp.any { it.locked }) return Result.Refuse(LOCKED)
        if (bottomUp.any { !it.visible }) return Result.Refuse(HIDDEN)
        val target = bottomUp.first()
        val tops = bottomUp.drop(1)
        if (target.pixel && target.targetOk && tops.all { it.pixel && it.plainTop })
            return Result.Pixels(target.id, tops.map { it.id }, target.animated, tops.any { it.animated })
        return Result.Snapshot(bottomUp.map { it.id }, bottomUp.any { it.animated })
    }

    /**
     * Верхний слой выходит за рамку нижнего. Если нижний анимирован, лучше сохранить анимацию и обрезать край
     * (пользователь получает предупреждение); статичные слои без потерь сливаются снимком.
     */
    fun clipOutside(targetAnimated: Boolean): Boolean = targetAnimated
}
