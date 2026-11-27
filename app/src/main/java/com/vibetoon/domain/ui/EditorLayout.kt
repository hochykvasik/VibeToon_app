package com.vibetoon.domain.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Спринт 60: раскладка редактора по реальному размеру окна (dp) и ориентации - вместо «ширина меньше 800 dp = телефон».
 *
 * Раньше телефон в ландшафте (например 891 x 411 dp) считался планшетом: правая панель 236-320 dp вставала в строку,
 * таймлайн занимал 40 % высоты, и холсту оставалась узкая полоска; портрета не было вовсе (активность была закреплена
 * в sensorLandscape). Теперь класс окна определяется по короткой стороне (как sw600dp у Android):
 *  - телефон: короткая сторона < 600 dp; портрет - высота >= ширины;
 *  - телефон в портрете: инструменты строкой ПОД холстом (а не колонкой сбоку), компактная шапка, таймлайн ~28 % высоты;
 *  - телефон в ландшафте: правая панель - выезжающий слой не шире 42 % окна (не закрывает весь холст), таймлайн ~34 %;
 *  - планшет: как раньше (широкий - от 840 dp).
 * Режим левши зеркалит стороны: колонка инструментов, строка инструментов и индикатор инструмента.
 * Чистые функции - unit-тест.
 */
object EditorLayout {
    const val PHONE_SHORT_SIDE_DP = 600f
    const val WIDE_DP = 840f
    /** Ниже этой высоты режимы «Объект / Кости / Сетка» живут в верхней строке (отдельная строка съела бы холст). */
    const val MODES_ROW_MIN_HEIGHT_DP = 480f
    const val TOP_BAR_DP = 44f
    const val MODE_BAR_DP = 38f
    const val TOOL_STRIP_DP = 52f
    const val TIMELINE_GRIP_DP = 18f
    /** Холсту остаётся не меньше этой доли высоты окна при любых панелях. */
    const val MIN_CANVAS_FRACTION = 0.3f

    data class Spec(
        val phone: Boolean,
        val portrait: Boolean,
        val wide: Boolean,
        /** Инструменты строкой под холстом (телефон в портрете), иначе колонкой у края. */
        val toolbarBottom: Boolean,
        val modesInTopBar: Boolean,
        /** Компактная шапка: второстепенное - в меню «Ещё». */
        val compactHeader: Boolean,
        /** Правая панель - выезжающий слой поверх холста (телефон), а не место в строке (планшет). */
        val sidePanelOverlay: Boolean,
        val sidePanelDp: Float,
        val railDp: Float,
        val timelineFraction: Float,
        val timelineMinDp: Float,
        val timelineMaxDp: Float,
        /** Колонка инструментов у правого края (режим левши), строка инструментов - справа налево. */
        val toolsAtEnd: Boolean,
        /** Индикатор инструмента у левого края (режим левши): не над колонкой/строкой инструментов и не по центру. */
        val indicatorAtStart: Boolean,
        /** Длина подписей (инструмент, символ) до сокращения. */
        val labelMaxChars: Int
    )

    fun of(widthDp: Float, heightDp: Float, leftHanded: Boolean): Spec {
        val w = if (widthDp.isFinite()) max(0f, widthDp) else 0f
        val h = if (heightDp.isFinite()) max(0f, heightDp) else 0f
        val phone = min(w, h) < PHONE_SHORT_SIDE_DP
        val portrait = h >= w
        val wide = !phone && w >= WIDE_DP
        val toolbarBottom = phone && portrait
        val side = when {
            !phone -> if (wide) 320f else 236f
            portrait -> min(w * 0.8f, 320f)
            else -> min(w * 0.42f, 280f)
        }
        val (frac, tMin, tMaxCap) = when {
            !phone -> Triple(0.4f, 150f, 340f)
            portrait -> Triple(0.28f, 104f, 300f)
            else -> Triple(0.34f, 88f, 200f)
        }
        val chrome = TOP_BAR_DP + (if (h < MODES_ROW_MIN_HEIGHT_DP) 0f else MODE_BAR_DP) + (if (toolbarBottom) TOOL_STRIP_DP else 0f) + TIMELINE_GRIP_DP
        // таймлайн не съедает холст: потолок - то, что остаётся после шапки, строки инструментов и минимума холста
        val room = max(0f, h - chrome - h * MIN_CANVAS_FRACTION)
        val tMax = max(min(tMin, room), min(tMaxCap, room))
        return Spec(
            phone = phone, portrait = portrait, wide = wide, toolbarBottom = toolbarBottom,
            modesInTopBar = h < MODES_ROW_MIN_HEIGHT_DP,
            compactHeader = phone || w < 700f,
            sidePanelOverlay = phone, sidePanelDp = side,
            railDp = if (wide) 60f else if (phone) 48f else 52f,
            timelineFraction = frac, timelineMinDp = min(tMin, tMax), timelineMaxDp = tMax,
            toolsAtEnd = leftHanded, indicatorAtStart = leftHanded,
            labelMaxChars = if (phone) (if (portrait) 18 else 24) else 40
        )
    }

    /** Высота таймлайна: заданная хватом ([userDp] > 0) или доля окна, всегда в пределах [Spec]. */
    fun timelineHeight(spec: Spec, heightDp: Float, userDp: Float): Float {
        val h = if (heightDp.isFinite()) max(0f, heightDp) else 0f
        val want = if (userDp > 0f && userDp.isFinite()) userDp else h * spec.timelineFraction
        return want.coerceIn(spec.timelineMinDp, max(spec.timelineMinDp, spec.timelineMaxDp))
    }

    /** Пределы перетаскивания хвата таймлайна (те же, что у [timelineHeight]). */
    fun timelineDragRange(spec: Spec): ClosedFloatingPointRange<Float> =
        spec.timelineMinDp..max(spec.timelineMinDp, spec.timelineMaxDp)

    /**
     * Аккуратное сокращение длинной подписи: по границе слова, с многоточием; короткие и пустые - как есть.
     * «Кости: точечный выбор» при 14 -> «Кости: точечный…»; одно длинное слово режется по символам.
     */
    fun shortLabel(text: String, maxChars: Int): String {
        val t = text.trim()
        if (maxChars <= 1) return if (t.isEmpty()) t else "…"
        if (t.length <= maxChars) return t
        val cut = t.substring(0, maxChars - 1)
        val space = cut.lastIndexOf(' ')
        val head = if (space >= maxChars / 2) cut.substring(0, space) else cut
        return head.trimEnd(' ', ':', ',', '-', '–', '—', '.') + "…"
    }

    /** Порядок кнопок строки инструментов слева направо: у левши - зеркально (основные инструменты у правого края). */
    fun <T> stripOrder(items: List<T>, spec: Spec): List<T> = if (spec.toolsAtEnd) items.asReversed() else items
}
