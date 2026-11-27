package com.vibetoon.domain.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Спринт 61: верхняя строка действий (экран проектов, шапка редактора) на узком экране.
 *
 * Было: заголовок с weight(1f) и 8 кнопок по 48 dp в одной строке. Строка Compose меряет детей по очереди и отдаёт
 * последнему то, что осталось, - на телефоне в портрете (360-411 dp) заголовок схлопывался до нуля, а крайняя
 * кнопка («Настройки») получала несколько dp и рисовалась микроскопической.
 *
 * Стало: кнопки всегда полного размера ([BUTTON_DP] - удобная область касания) в полосе с горизонтальной прокруткой;
 * заголовку оставляется не меньше [MIN_TITLE_DP] (длинный - с «…»), а если места хватает на всё - прокрутки нет.
 * Режим левши: полоса зеркальна - кнопки у левого края в обратном порядке, заголовок справа.
 * Чистые функции в dp - unit-тест.
 */
object ActionBarLayout {
    /** Размер кнопки и её области касания (Material: не меньше 48 dp). */
    const val BUTTON_DP = 48f
    /** Сколько ширины остаётся заголовку, когда кнопки не помещаются. */
    const val MIN_TITLE_DP = 96f
    /** Заголовок не шире этой доли строки, когда кнопки прокручиваются. */
    const val MAX_TITLE_FRACTION = 0.4f
    /** Уже этой ширины строка поиска уходит из строки действий отдельной строкой под ней. */
    const val INLINE_SEARCH_MIN_DP = 600f

    data class Plan(
        /** Ширина заголовка, dp (0 - заголовка нет). */
        val titleDp: Float,
        /** Ширина видимой части полосы кнопок, dp. */
        val stripDp: Float,
        /** Кнопки не помещаются - полоса прокручивается. */
        val scrolls: Boolean,
        /** Сколько кнопок видно целиком без прокрутки. */
        val visibleButtons: Int,
        /** Кнопки у левого края, заголовок справа (режим левши). */
        val mirrored: Boolean
    )

    private fun fin(v: Float) = if (v.isFinite()) max(0f, v) else 0f

    /**
     * Раскладка строки шириной [barDp] (без внешних отступов): [buttons] кнопок по [buttonDp] плюс [extraDp]
     * (например, поле поиска), заголовок - если [hasTitle].
     */
    fun plan(barDp: Float, buttons: Int, hasTitle: Boolean, leftHanded: Boolean, buttonDp: Float = BUTTON_DP, extraDp: Float = 0f): Plan {
        val bar = fin(barDp)
        val b = if (buttonDp.isFinite() && buttonDp > 0f) buttonDp else BUTTON_DP
        val need = max(0, buttons) * b + fin(extraDp)
        val title = when {
            !hasTitle -> 0f
            bar - need >= MIN_TITLE_DP -> bar - need          // всё помещается: заголовок забирает остаток
            else -> min(MIN_TITLE_DP, bar * MAX_TITLE_FRACTION)
        }
        val strip = max(0f, bar - title)
        val scrolls = need > strip + 0.5f
        val visible = if (b <= 0f) 0 else min(max(0, buttons), ((strip - (if (scrolls) 0f else fin(extraDp))) / b).toInt().coerceAtLeast(0))
        return Plan(title, strip, scrolls, visible, leftHanded)
    }

    /** Порядок кнопок слева направо: у левши - зеркально (первая кнопка у левого края). */
    fun <T> order(items: List<T>, leftHanded: Boolean): List<T> = if (leftHanded) items.asReversed() else items

    /** Поле поиска - в строке действий (широкий экран) или отдельной строкой под ней (телефон). */
    fun inlineSearch(widthDp: Float): Boolean = fin(widthDp) >= INLINE_SEARCH_MIN_DP

    /**
     * Подсказки прокрутки у краёв полосы: (слева, справа) по положению прокрутки [value] из [max].
     * Пока полоса не прокручивается - подсказок нет.
     */
    fun edgeHints(value: Int, max: Int): Pair<Boolean, Boolean> =
        if (max <= 0) false to false else (value > 0) to (value < max)
}
