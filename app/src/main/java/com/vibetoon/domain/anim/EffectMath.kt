package com.vibetoon.domain.anim

/** Категории библиотеки эффектов. */
enum class EffectCategory(val title: String) {
    COLOR("Цвет"), BLUR("Размытие"), DISTORT("Искажение"), STYLE("Стилизация"), NOISE_MOTION("Шум и Движение")
}

/** Числовой параметр эффекта (ползунок в инспекторе). [options] - дискретный выбор (значение = индекс варианта). */
class FxParam(val name: String, val min: Float, val max: Float, val def: Float, val options: List<String> = emptyList())

/**
 * Описание эффекта. [type] совпадает с uMode в шейдере (кроме [SPIN], он считается на CPU).
 * [colorAt] - индекс первого из трёх параметров r, g, b (0..1), которые в инспекторе показываются как цвет с пипеткой.
 */
class FxDef(
    val type: Int, val title: String, val subtitle: String, val category: EffectCategory,
    val params: List<FxParam>, val colorAt: Int = -1
)

/** Эффект на слое: тип и до 8 значений параметров. */
data class Effect(val type: Int, val p: List<Float>, val slot: Int = -1)

object EffectMath {
    const val CHROMA = 0; const val BLUR = 1; const val COLOR = 2; const val PIXELATE = 3; const val GLOW = 4
    const val SHADOW = 5; const val INVERT = 6; const val VIGNETTE = 7; const val STROKE = 8; const val WAVE = 9
    const val MIRROR = 10; const val TILES = 11; const val NOISE = 12; const val SHAKE = 13; const val MOTION = 14
    const val ZOOM = 15
    /** Не шейдерный эффект: авто-вращение слоя (градусов в секунду). */
    const val SPIN = 16
    const val RGB_SPLIT = 17; const val PULSE = 18; const val DISPLACE = 19
    /** Не шейдерные эффекты-трансформации (как Spin): растяжение вдоль оси под углом и качание между двумя углами. */
    const val STRETCH_AXIS = 20; const val SWING = 21
    /** Волны Swing (значение параметра «Волна»). */
    const val WAVE_SINE = 0; const val WAVE_TRIANGLE = 1; const val WAVE_SQUARE = 2; const val WAVE_SAW = 3
    val WAVE_NAMES = listOf("Синусоида", "Треугольник", "Меандр", "Пила")
    /** Переключатель-параметр: 0 - выкл, 1 - вкл (в ключах - порог 0.5). */
    val ON_OFF = listOf("Выкл", "Вкл")
    const val MAX_PER_LAYER = 8
    const val RECORD = 10

    val DEFS: List<FxDef> = listOf(
        FxDef(CHROMA, "Хромакей", "Chroma Key", EffectCategory.COLOR, listOf(
            FxParam("R", 0f, 1f, 0f), FxParam("G", 0f, 1f, 0.8f), FxParam("B", 0f, 1f, 0f),
            FxParam("Допуск", 0f, 1f, 0.35f), FxParam("Мягкость", 0f, 1f, 0.15f)), colorAt = 0),
        FxDef(COLOR, "Цветокоррекция", "Color Correction", EffectCategory.COLOR, listOf(
            FxParam("Экспозиция (EV)", -2f, 2f, 0f), FxParam("Контраст", -1f, 1f, 0f), FxParam("Насыщенность", -1f, 1f, 0f), FxParam("Тон, °", -180f, 180f, 0f))),
        FxDef(INVERT, "Инверсия", "Invert", EffectCategory.COLOR, listOf(FxParam("Степень", 0f, 1f, 1f))),
        FxDef(BLUR, "Размытие по Гауссу", "Gaussian Blur", EffectCategory.BLUR, listOf(FxParam("Радиус", 0f, 60f, 12f))),
        FxDef(ZOOM, "Радиальное размытие", "Zoom Blur", EffectCategory.BLUR, listOf(FxParam("Сила", 0f, 0.4f, 0.12f))),
        FxDef(MOTION, "Размытие в движении", "Motion Blur", EffectCategory.BLUR, listOf(FxParam("Длина", 0f, 120f, 30f), FxParam("Угол, °", 0f, 360f, 0f))),
        FxDef(PIXELATE, "Пикселизация", "Pixelate", EffectCategory.DISTORT, listOf(FxParam("Размер пикселя", 1f, 80f, 12f))),
        FxDef(TILES, "Плитки", "Tiles", EffectCategory.DISTORT, listOf(FxParam("Повторов", 1f, 10f, 3f))),
        FxDef(MIRROR, "Зеркало", "Mirror", EffectCategory.DISTORT, listOf(FxParam("Режим (0 гор, 1 верт, 2 оба)", 0f, 2f, 0f))),
        FxDef(WAVE, "Волна", "Wave", EffectCategory.DISTORT, listOf(
            FxParam("Амплитуда", 0f, 60f, 10f), FxParam("Длина волны", 10f, 400f, 120f), FxParam("Скорость", 0f, 10f, 2f),
            FxParam("Направление (0 / 1)", 0f, 1f, 0f))),
        FxDef(GLOW, "Свечение", "Glow", EffectCategory.STYLE, listOf(FxParam("Сила", 0f, 3f, 1f), FxParam("Радиус", 0f, 80f, 20f))),
        FxDef(SHADOW, "Тень", "Drop Shadow", EffectCategory.STYLE, listOf(
            FxParam("Смещение X", -100f, 100f, 12f), FxParam("Смещение Y", -100f, 100f, 12f), FxParam("Размытие", 0f, 60f, 10f),
            FxParam("Непрозрачность", 0f, 1f, 0.6f), FxParam("R", 0f, 1f, 0f), FxParam("G", 0f, 1f, 0f), FxParam("B", 0f, 1f, 0f)), colorAt = 4),
        FxDef(VIGNETTE, "Виньетка", "Vignette", EffectCategory.STYLE, listOf(FxParam("Сила", 0f, 1f, 0.6f), FxParam("Размер", 0f, 1f, 0.4f))),
        FxDef(STROKE, "Обводка", "Stroke", EffectCategory.STYLE, listOf(
            FxParam("Ширина", 1f, 40f, 6f), FxParam("R", 0f, 1f, 1f), FxParam("G", 0f, 1f, 1f), FxParam("B", 0f, 1f, 1f)), colorAt = 1),
        FxDef(NOISE, "Шум", "Noise", EffectCategory.NOISE_MOTION, listOf(FxParam("Сила", 0f, 1f, 0.3f), FxParam("Размер зерна", 1f, 20f, 2f))),
        FxDef(SHAKE, "Тряска", "Shake", EffectCategory.NOISE_MOTION, listOf(FxParam("Амплитуда", 0f, 60f, 8f), FxParam("Частота, 1/с", 1f, 30f, 12f))),
        FxDef(RGB_SPLIT, "RGB-разрыв / Глитч", "RGB Split", EffectCategory.NOISE_MOTION, listOf(
            FxParam("Сдвиг X", -60f, 60f, 8f), FxParam("Сдвиг Y", -60f, 60f, 0f), FxParam("Глитч, px", 0f, 80f, 0f), FxParam("Частота глитча, 1/с", 1f, 30f, 10f))),
        FxDef(PULSE, "Пульсация", "Beat / Pulse", EffectCategory.NOISE_MOTION, listOf(
            FxParam("Масштаб ±", 0f, 0.5f, 0.1f), FxParam("Частота, Гц", 0.1f, 8f, 2f), FxParam("Непрозр. ±", 0f, 1f, 0f),
            FxParam("Центр X", 0f, 1f, 0.5f), FxParam("Центр Y", 0f, 1f, 0.5f))),
        FxDef(DISPLACE, "Карта смещения", "Displacement", EffectCategory.DISTORT, listOf(
            FxParam("Сила, px", 0f, 120f, 20f), FxParam("Масштаб карты, px", 4f, 400f, 80f), FxParam("Скорость", 0f, 5f, 0.5f))),
        FxDef(SPIN, "Вращение", "Spin", EffectCategory.NOISE_MOTION, listOf(FxParam("Скорость, °/с", -720f, 720f, 90f))),
        FxDef(STRETCH_AXIS, "Ось растяжения", "Stretch Axis", EffectCategory.DISTORT, listOf(
            FxParam("Масштаб", 0.05f, 5f, 1.25f), FxParam("Угол, °", -180f, 180f, 0f),
            // Пакет 26: «Маска по слою» - растянутый слой виден только в пределах своей формы без растяжения
            FxParam("Маска по слою", 0f, 1f, 0f, ON_OFF))),
        FxDef(SWING, "Качание", "Swing", EffectCategory.NOISE_MOTION, listOf(
            FxParam("Частота, Гц", 0f, 10f, 1f), FxParam("Угол 1, °", -360f, 360f, -15f), FxParam("Угол 2, °", -360f, 360f, 15f),
            FxParam("Начальная фаза", 0f, 1f, 0f), FxParam("Волна", 0f, 3f, 0f, WAVE_NAMES)))
    )

    /** Эффекты, которые меняют трансформацию слоя на CPU и не попадают в шейдерную цепочку. */
    fun isTransform(type: Int): Boolean = type == SPIN || type == STRETCH_AXIS || type == SWING

    /**
     * Форма волны Swing: доля пути от «Угол 1» к «Угол 2» (0..1) в момент [u] (циклы, дробная часть - фаза).
     * Все волны начинаются в «Угол 1» при u = 0.
     */
    fun swingWave(wave: Int, u: Double): Float {
        val f = u - kotlin.math.floor(u)
        return when (wave) {
            WAVE_TRIANGLE -> (1.0 - kotlin.math.abs(2.0 * f - 1.0)).toFloat()
            WAVE_SQUARE -> if (f < 0.5) 0f else 1f
            WAVE_SAW -> f.toFloat()
            else -> ((1.0 - kotlin.math.cos(2.0 * Math.PI * f)) / 2.0).toFloat()
        }
    }

    /** Угол Swing (градусы) для параметров [p] (частота, угол 1, угол 2, фаза, волна) после [cycles] циклов. */
    fun swingDegrees(p: List<Float>, cycles: Double): Float {
        val a1 = p.getOrElse(1) { 0f }; val a2 = p.getOrElse(2) { 0f }
        val wave = kotlin.math.round(p.getOrElse(4) { 0f }).toInt().coerceIn(WAVE_SINE, WAVE_SAW)
        return a1 + (a2 - a1) * swingWave(wave, cycles + p.getOrElse(3) { 0f })
    }

    /** Линейная часть «Оси растяжения»: масштаб [k] вдоль оси под углом [deg] (поперёк оси - без изменений). */
    fun stretchMatrix(k: Float, deg: Float): com.vibetoon.domain.model.Affine2D {
        val a = Math.toRadians(deg.toDouble()).toFloat()
        val kk = if (k.isFinite()) k.coerceIn(0.05f, 5f) else 1f
        return com.vibetoon.domain.model.Affine2D.rotate(a) * com.vibetoon.domain.model.Affine2D.scale(kk, 1f) * com.vibetoon.domain.model.Affine2D.rotate(-a)
    }

    /** «Маска по слою» включена у «Оси растяжения» с параметрами [p] (третий параметр, порог 0.5; старые эффекты - выкл). */
    fun stretchMasked(p: List<Float>): Boolean = p.getOrElse(2) { 0f }.let { it.isFinite() && it >= 0.5f }

    fun def(type: Int): FxDef? = DEFS.firstOrNull { it.type == type }

    fun create(type: Int): Effect? = def(type)?.let { d -> Effect(type, List(8) { i -> d.params.getOrNull(i)?.def ?: 0f }) }

    /** Значение параметра в допустимых пределах. */
    fun clamp(type: Int, idx: Int, v: Float): Float {
        val p = def(type)?.params?.getOrNull(idx) ?: return v
        return if (v.isFinite()) v.coerceIn(p.min, p.max) else p.def
    }

    /** Записи для шейдера: по 10 float [тип, p0..p7, время]; Spin обрабатывается отдельно и сюда не попадает. */
    fun records(list: List<Effect>, timeSec: Float): List<FloatArray> =
        list.filter { !isTransform(it.type) && def(it.type) != null }.map { e ->
            FloatArray(RECORD).also { r ->
                r[0] = e.type.toFloat()
                for (i in 0 until 8) r[1 + i] = e.p.getOrElse(i) { 0f }
                r[9] = timeSec
            }
        }

    /** Угол авто-вращения слоя (радианы) на кадре: сумма всех Spin на слое. */
    fun spinAngle(list: List<Effect>, frame: Int, fps: Int): Float {
        val deg = list.filter { it.type == SPIN }.sumOf { (it.p.getOrElse(0) { 0f } * frame / fps.coerceAtLeast(1)).toDouble() }
        return Math.toRadians(deg).toFloat()
    }
}
