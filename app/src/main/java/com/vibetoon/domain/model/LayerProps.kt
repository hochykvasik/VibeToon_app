package com.vibetoon.domain.model

/**
 * Свойства слоя панели слоёв (этап 3): прозрачность, режим наложения, замок прозрачности и обтравочная маска.
 * Хранится в project.json по id узла и перекрывает значения из PSD. [blend] = 4-символьный ключ PSD
 * ("norm", "mul ", "scrn", "over", "sLit").
 */
data class LayerProps(
    val opacity: Float = 1f,
    val blend: String = "norm",
    val alphaLock: Boolean = false,
    val clip: Boolean = false,
    /** Цветокоррекция слоя, ползунки -100..100. */
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val hue: Float = 0f,
    /** Глаз: выключенный слой не рисуется ни на холсте, ни в экспорте. */
    val visible: Boolean = true,
    /** Замок: слой нельзя выделить на холсте, двигать жестами и рисовать по нему. */
    val locked: Boolean = false,
    /** Своё имя слоя (null - имя из файла). */
    val name: String? = null,
    /** Глубина для параллакса камеры: 0 - на плоскости камеры (двигается 1:1), больше - дальше (смещается и увеличивается медленнее), меньше нуля - ближе. */
    val depth: Float = 0f,
    /**
     * Спринт 38: маска слоя - id слоя-маски (-1 - нет). Альфа маски = видимость слоя (непрозрачное видно, стёртое скрыто),
     * [maskInvert] - наоборот, [maskOff] - маска временно выключена (слой виден целиком, сама маска по-прежнему не рисуется).
     */
    val mask: Int = -1,
    val maskInvert: Boolean = false,
    val maskOff: Boolean = false,
    /** Спринт 38: слой прикреплён к слою-пути (id, -1 - нет); [pathOrient] - поворачивается по направлению пути. */
    val path: Int = -1,
    val pathOrient: Boolean = false,
    /** Спринт 45: обрезка (вкладка «Обрезка») - l, t, r, b в долях картинки слоя; null - картинка целиком (см. LayerCrop). */
    val crop: List<Float>? = null
) {
    private fun c(v: Float) = if (v.isFinite()) v.coerceIn(-100f, 100f) else 0f
    fun normalized() = copy(
        opacity = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 1f, blend = if (blend in LayerBlend.keys) blend else "norm",
        exposure = c(exposure), contrast = c(contrast), saturation = c(saturation), hue = c(hue),
        name = name?.trim()?.take(80)?.ifEmpty { null },
        depth = if (depth.isFinite()) depth.coerceIn(-0.5f, 5f) else 0f,
        mask = if (mask < 0) -1 else mask, path = if (path < 0) -1 else path,
        crop = LayerCrop.of(crop)?.toList()
    )
    val hasColor get() = exposure != 0f || contrast != 0f || saturation != 0f || hue != 0f
}

/** Режимы наложения, доступные в панели слоёв. */
object LayerBlend {
    val modes: List<Pair<String, String>> = listOf(
        "norm" to "Normal", "mul " to "Multiply", "scrn" to "Screen", "over" to "Overlay", "sLit" to "Soft Light",
        "lddg" to "Add", "dark" to "Darken", "lite" to "Lighten", "div " to "Color Dodge", "idiv" to "Color Burn"
    )
    val keys: Set<String> = modes.map { it.first }.toSet()
    fun title(key: String) = modes.firstOrNull { it.first == key }?.second ?: "Normal"
}
