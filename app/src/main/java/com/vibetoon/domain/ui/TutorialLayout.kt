package com.vibetoon.domain.ui

/**
 * Спринт 62: раскладка экрана урока («Как добавить и анимировать текст» и др.).
 *
 * Было: всегда две колонки - схема слева (≈35 % ширины) и шаги справа. На телефоне в портрете текст шагов
 * оказывался в колонке ≈200 dp внутри двух вложенных карточек: «Где: вкладка в правой панели» ломалось по слову
 * в строку, кнопка «Пройти в редакторе: кнопки подсвечиваются» обрезалась «…».
 *
 * Стало: узкий экран ([STACK_BELOW_DP]) - схема компактной полосой сверху, шаги во всю ширину ниже, без вложенных
 * карточек; кнопка «Пройти в редакторе» - закреплённой панелью внизу экрана, подпись в две строки без обрезки.
 * Широкий экран - две колонки, схема уже (30 %); у левши колонки зеркальны (шаги слева, у большого пальца левой
 * руки). Чистые функции - Sprint62Test.
 */
object TutorialLayout {
    const val STACK_BELOW_DP = 600f
    /** Высота полосы со схемой в узкой раскладке: доля высоты экрана, но в пределах [MIN_DIAGRAM_DP]..[MAX_DIAGRAM_DP]. */
    const val DIAGRAM_FRACTION = 0.22f
    const val MIN_DIAGRAM_DP = 96f
    const val MAX_DIAGRAM_DP = 180f
    /** Уже этого плашка «кнопка + где искать» ставит подпись под кнопкой, а не справа. */
    const val PLATE_STACK_DP = 300f

    data class Plan(
        val stacked: Boolean,
        /** Узкая раскладка: высота полосы со схемой, dp. */
        val diagramHeightDp: Float,
        /** Широкая: доля ширины под схему. */
        val diagramFraction: Float,
        /** Широкая: схема справа (левша). */
        val diagramRight: Boolean,
        /** Кнопка «Пройти в редакторе» закреплена внизу экрана. */
        val bottomAction: Boolean,
        /** Ширина колонки текста шагов, dp. */
        val textWidthDp: Float
    )

    private fun fin(v: Float) = if (v.isFinite()) maxOf(0f, v) else 0f

    fun plan(widthDp: Float, heightDp: Float, leftHanded: Boolean, padDp: Float = 16f): Plan {
        val w = fin(widthDp); val h = fin(heightDp)
        val inner = maxOf(0f, w - 2 * fin(padDp))
        return if (w < STACK_BELOW_DP) {
            val dh = (h * DIAGRAM_FRACTION).coerceIn(MIN_DIAGRAM_DP, MAX_DIAGRAM_DP)
            Plan(true, dh, 0f, false, true, inner)
        } else {
            val frac = 0.3f
            Plan(false, 0f, frac, leftHanded, h < 480f, maxOf(0f, inner - fin(padDp)) * (1f - frac))
        }
    }

    /** Подпись плашки кнопки - под кнопкой (узкая колонка) или справа от неё. */
    fun plateStacked(textWidthDp: Float): Boolean = fin(textWidthDp) < PLATE_STACK_DP
}
