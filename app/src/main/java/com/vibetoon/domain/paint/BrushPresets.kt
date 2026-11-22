package com.vibetoon.domain.paint

/** Пресет кисти: название для меню и все настройки (размер, форма, жёсткость, режим, зерно). Цвет не входит - берётся текущий. */
data class BrushPreset(val id: String, val title: String, val params: BrushParams)

/** Базовые кисти и ластики. Размеры - диаметр в пикселях слоя. */
object BrushPresets {
    val brushes: List<BrushPreset> = listOf(
        BrushPreset(
            "pen", "Перо",
            BrushParams(size = 10f, spacing = 0.1f, minSizePercent = 0.25f, hardness = 1f, mode = StrokeMode.CAP_OPACITY, presetId = "pen")
        ),
        BrushPreset(
            "marker", "Маркер",
            BrushParams(size = 40f, spacing = 0.2f, minSizePercent = 0.3f, hardness = 0.85f, mode = StrokeMode.CAP_OPACITY, presetId = "marker")
        ),
        BrushPreset(
            "airbrush", "Аэрограф",
            BrushParams(size = 90f, spacing = 0.1f, minSizePercent = 0.4f, flow = 0.12f, hardness = 0f, mode = StrokeMode.ACCUMULATE, presetId = "airbrush")
        ),
        BrushPreset(
            "pencil", "Карандаш",
            BrushParams(
                size = 12f, spacing = 0.12f, minSizePercent = 0.35f, flow = 0.55f, hardness = 0.7f, mode = StrokeMode.ACCUMULATE,
                grain = GrainKind.PENCIL, grainMode = GrainMode.MOVING, rotation = DabRotation.RANDOM, grainScale = 1f, grainContrast = 1.4f, presetId = "pencil"
            )
        ),
        BrushPreset(
            "screentone", "Скринтон мелкий",
            BrushParams(
                size = 80f, spacing = 0.2f, minSizePercent = 1f, hardness = 1f, mode = StrokeMode.CAP_OPACITY,
                grain = GrainKind.SCREENTONE, grainMode = GrainMode.CANVAS, grainScale = 1f, grainContrast = 1f, presetId = "screentone"
            )
        ),
        BrushPreset(
            "square", "Квадратный маркер",
            BrushParams(
                size = 24f, spacing = 0.25f, minSizePercent = 0.5f, hardness = 1f, mode = StrokeMode.CAP_OPACITY, shape = BrushShape.SQUARE,
                roundness = 0.4f, rotation = DabRotation.TRAJECTORY, presetId = "square"
            )
        ),
        BrushPreset(
            "ink", "Ручка (Ink)",
            BrushParams(size = 14f, spacing = 0.08f, minSizePercent = 0.05f, hardness = 1f, mode = StrokeMode.CAP_OPACITY, taperPx = 40f, presetId = "ink")
        ),
        BrushPreset(
            "dense", "Плотный маркер",
            BrushParams(size = 60f, spacing = 0.15f, minSizePercent = 1f, flow = 1f, hardness = 1f, mode = StrokeMode.CAP_OPACITY, presetId = "dense")
        ),
        BrushPreset(
            "screentone_coarse", "Скринтон крупный",
            BrushParams(
                size = 80f, spacing = 0.2f, minSizePercent = 1f, hardness = 1f, mode = StrokeMode.CAP_OPACITY,
                grain = GrainKind.SCREENTONE_COARSE, grainMode = GrainMode.CANVAS, presetId = "screentone_coarse"
            )
        ),
        BrushPreset(
            "pixel", "Пиксельная",
            BrushParams(size = 4f, spacing = 0.5f, minSizePercent = 1f, hardness = 1f, mode = StrokeMode.CAP_OPACITY, shape = BrushShape.SQUARE, pixelSnap = true, presetId = "pixel")
        ),
        BrushPreset(
            "soft_edge", "Градиентная",
            BrushParams(size = 50f, spacing = 0.1f, minSizePercent = 0.3f, hardness = 0.25f, curve = 1, mode = StrokeMode.CAP_OPACITY, presetId = "soft_edge")
        ),
        BrushPreset(
            "hline", "Штриховка ═",
            BrushParams(
                size = 70f, spacing = 0.2f, minSizePercent = 1f, hardness = 1f, mode = StrokeMode.CAP_OPACITY,
                grain = GrainKind.HLINES, grainMode = GrainMode.MOVING, rotation = DabRotation.TRAJECTORY, presetId = "hline"
            )
        ),
        BrushPreset(
            "vline", "Штриховка ║",
            BrushParams(
                size = 70f, spacing = 0.2f, minSizePercent = 1f, hardness = 1f, mode = StrokeMode.CAP_OPACITY,
                grain = GrainKind.VLINES, grainMode = GrainMode.MOVING, rotation = DabRotation.TRAJECTORY, presetId = "vline"
            )
        )
    )

    /** Три ластика: жёсткий круглый, мягкий, прямоугольный. */
    val erasers: List<BrushPreset> = listOf(
        BrushPreset("e_hard", "Жёсткий ластик", BrushParams(size = 40f, spacing = 0.15f, minSizePercent = 0.5f, hardness = 1f, mode = StrokeMode.ERASE, presetId = "e_hard")),
        BrushPreset("e_soft", "Мягкий ластик", BrushParams(size = 80f, spacing = 0.12f, minSizePercent = 0.5f, hardness = 0.1f, mode = StrokeMode.ERASE, presetId = "e_soft")),
        BrushPreset("e_square", "Квадратный ластик", BrushParams(size = 40f, spacing = 0.2f, minSizePercent = 0.5f, hardness = 1f, mode = StrokeMode.ERASE, shape = BrushShape.SQUARE, presetId = "e_square"))
    )

    fun all(): List<BrushPreset> = brushes + erasers
    fun byId(id: String): BrushPreset? = all().firstOrNull { it.id == id }

    /**
     * S-образный мазок для превью: точки вдоль кривой Безье с имитацией давления (в начале 0.1, в середине 1.0, в конце 0.0+).
     * Координаты - в пикселях области [w] x [h]. Возвращает штампы ([DabStroker.STRIDE] чисел на штамп).
     */
    fun previewDabs(p: BrushParams, w: Int, h: Int): FloatArray {
        // размер в превью ограничен, чтобы штрих помещался: зерно при этом не масштабируется (видна реальная текстура)
        val pr = p.copy(size = p.size.coerceIn(6f, h * 0.42f))
        val s = DabStroker(pr)
        val out = ArrayList<Float>()
        fun take(a: FloatArray) { for (v in a) out.add(v) }
        val x0 = w * 0.08f; val x1 = w * 0.92f
        val n = 48
        fun pt(i: Int): Triple<Float, Float, Float> {
            val t = i.toFloat() / n
            val x = x0 + (x1 - x0) * t
            // S: синусоида с затуханием у краёв
            val y = h * 0.5f + kotlin.math.sin((t * 2f - 1f) * Math.PI.toFloat() * 1.0f) * -h * 0.28f
            val pressure = (kotlin.math.sin(t * Math.PI.toFloat()) * 0.95f + 0.05f).coerceIn(0.05f, 1f)
            return Triple(x, y, pressure)
        }
        val f = pt(0)
        take(s.begin(f.first, f.second, f.third))
        for (i in 1..n) { val q = pt(i); take(s.add(q.first, q.second, q.third)) }
        take(s.end())
        return out.toFloatArray()
    }
}
