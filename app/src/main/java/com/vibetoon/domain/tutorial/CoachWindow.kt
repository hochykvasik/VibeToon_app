package com.vibetoon.domain.tutorial

import kotlin.math.max
import kotlin.math.min

/**
 * Спринт 59: окно подсказки урока можно двигать, сворачивать в компактную полоску и закрывать.
 * Ручки изменения размера нет: ширина задаётся здесь, высота - по тексту (длинный текст прокручивается внутри окна).
 * Пока окно не двигали, оно встаёт само: внизу по центру или вверху, если подсвеченная кнопка внизу ([CoachPlacement]).
 * Чистая геометрия: единицы любые, но одинаковые (в редакторе - пиксели окна).
 */
object CoachWindow {
    /** Ширина развёрнутого окна, dp. */
    const val MAX_W_DP = 520f
    /** Ширина свёрнутой полоски, dp. */
    const val COMPACT_W_DP = 340f
    /** Отступ от края области при авторазмещении, dp. */
    const val MARGIN_DP = 12f
    /** Развёрнутое окно не выше этой доли области: на телефоне под ним остаётся видно редактор. */
    const val MAX_HEIGHT_FRACTION = 0.6f
    /** Нижняя граница высоты окна, dp (очень низкая область - окно хотя бы с шапкой и кнопками). */
    const val MIN_HEIGHT_DP = 140f

    data class Pos(val x: Float, val y: Float)

    /** Ширина окна: не больше [maxW] и не шире области без двух отступов. */
    fun width(areaW: Float, maxW: Float, margin: Float): Float = max(0f, min(maxW, areaW - 2 * margin))

    /** Предел высоты развёрнутого окна. */
    fun maxHeight(areaH: Float, minH: Float): Float = max(0f, min(areaH, max(minH, areaH * MAX_HEIGHT_FRACTION)))

    /** Окно целиком внутри области; если область меньше окна - прижато к левому верхнему углу. */
    fun clamp(p: Pos, w: Float, h: Float, areaW: Float, areaH: Float): Pos =
        Pos(p.x.coerceIn(0f, max(0f, areaW - w)), p.y.coerceIn(0f, max(0f, areaH - h)))

    /** Авторазмещение: по центру, внизу или вверху ([onTop]). */
    fun auto(w: Float, h: Float, areaW: Float, areaH: Float, margin: Float, onTop: Boolean): Pos =
        clamp(Pos((areaW - w) / 2f, if (onTop) margin else areaH - h - margin), w, h, areaW, areaH)

    /** Где окно сейчас: сдвинутое пользователем ([custom]) или авторазмещённое; всегда внутри области. */
    fun place(custom: Pos?, w: Float, h: Float, areaW: Float, areaH: Float, margin: Float, onTop: Boolean): Pos =
        if (custom == null) auto(w, h, areaW, areaH, margin, onTop) else clamp(custom, w, h, areaW, areaH)

    /** Перетаскивание за шапку: окно не выходит за область. */
    fun moveBy(p: Pos, dx: Float, dy: Float, w: Float, h: Float, areaW: Float, areaH: Float): Pos =
        clamp(Pos(p.x + dx, p.y + dy), w, h, areaW, areaH)
}
