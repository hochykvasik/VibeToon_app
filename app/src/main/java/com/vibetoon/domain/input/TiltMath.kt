package com.vibetoon.domain.input

/**
 * Спринт 40: наклон пера меняет кисть (а не только показывается на тестовой площадке). Чистые функции - Package40TiltTest.
 *
 * Режимы (настройка `stylus_tilt` в vt_app, сила - `stylus_tilt_strength` 0..100):
 *  - [Mode.OFF] - наклон не влияет (как раньше);
 *  - [Mode.SIZE] - чем сильнее перо наклонено, тем шире след (до ×2.5 при силе 100);
 *  - [Mode.SHADING] - «карандаш боком»: шире и прозрачнее, для штриховки и мягкой тени;
 *  - [Mode.ANGLE] - плоское перо: штамп сплющивается по наклону и поворачивается по направлению пера (каллиграфия).
 *
 * Палец, мышь и перо без датчика наклона (AXIS_TILT = NaN, спринт 44) рисуют без изменений: множители 1, угол NaN.
 * Наклон 0..[FULL_TILT_DEG] переводится в 0..1; дрожание датчика сглаживается [smooth].
 */
object TiltMath {
    enum class Mode(val id: Int, val ru: String) {
        OFF(0, "Не влияет"), SIZE(1, "Толщина"), SHADING(2, "Штриховка"), ANGLE(3, "Плоское перо");
        companion object { fun of(id: Int): Mode = values().firstOrNull { it.id == id } ?: OFF }
    }

    const val PREF_MODE = "stylus_tilt"
    const val PREF_STRENGTH = "stylus_tilt_strength"
    const val DEFAULT_STRENGTH = 60
    /** Наклон (от вертикали), при котором эффект максимальный: дальше 60° перо почти не наклоняют. */
    const val FULL_TILT_DEG = 60f
    const val MAX_SIZE_GAIN = 1.5f
    const val SHADING_SIZE_GAIN = 2.5f
    const val SHADING_FLOW_LOSS = 0.7f
    const val MIN_ROUNDNESS = 0.2f
    const val MIN_FLOW = 0.05f
    /** Наклон (0..1), ниже которого плоское перо не поворачивает штамп: ~3° от вертикали. */
    const val MIN_ANGLE_TILT = 0.05f

    data class Settings(val mode: Mode = Mode.OFF, val strength: Int = DEFAULT_STRENGTH) {
        val k: Float get() = strength.coerceIn(0, 100) / 100f
        val active: Boolean get() = mode != Mode.OFF && strength > 0
    }

    fun settings(modeId: Int, strength: Int) = Settings(Mode.of(modeId), strength.coerceIn(0, 100))

    /** Наклон 0..1 из AXIS_TILT (радианы от вертикали); нет датчика - 0. */
    fun norm(tiltRadians: Float): Float {
        if (!tiltRadians.isFinite() || tiltRadians <= 0f) return 0f
        val deg = Math.toDegrees(tiltRadians.toDouble()).toFloat()
        return (deg / FULL_TILT_DEG).coerceIn(0f, 1f)
    }

    /** Сглаживание дрожания датчика: экспоненциальное среднее; первое значение (prev NaN) берётся как есть. */
    fun smooth(prev: Float, next: Float, k: Float = 0.35f): Float = when {
        !next.isFinite() -> prev
        !prev.isFinite() -> next
        else -> prev + (next - prev) * k.coerceIn(0f, 1f)
    }

    /** Множитель радиуса штампа. */
    fun sizeFactor(s: Settings, t: Float): Float {
        if (!s.active) return 1f
        val x = t.coerceIn(0f, 1f) * s.k
        return when (s.mode) {
            Mode.SIZE -> 1f + MAX_SIZE_GAIN * x
            Mode.SHADING -> 1f + SHADING_SIZE_GAIN * x
            else -> 1f
        }
    }

    /** Множитель непрозрачности штампа (flow). */
    fun flowFactor(s: Settings, t: Float): Float {
        if (!s.active || s.mode != Mode.SHADING) return 1f
        return (1f - SHADING_FLOW_LOSS * t.coerceIn(0f, 1f) * s.k).coerceIn(MIN_FLOW, 1f)
    }

    /** Сплющивание штампа для плоского пера (берётся в начале штриха: форма штампа задаётся на весь штрих). */
    fun roundness(s: Settings, t: Float, base: Float): Float {
        if (!s.active || s.mode != Mode.ANGLE) return base
        return minOf(base, (1f - (1f - MIN_ROUNDNESS) * t.coerceIn(0f, 1f) * s.k).coerceIn(MIN_ROUNDNESS, 1f))
    }

    /**
     * Поворот штампа (радианы, как у поворота «по траектории»: 0 - вдоль оси X слоя, ось Y вниз) для плоского пера:
     * длинная ось сплющенного штампа идёт вдоль направления пера (getOrientation: 0 - перо смотрит вверх, по часовой),
     * с поправкой на поворот холста [canvasRotation]. NaN - поворот по настройке кисти.
     */
    fun angle(s: Settings, orientationRadians: Float, canvasRotation: Float = 0f, t: Float = 1f, flipX: Boolean = false, flipY: Boolean = false): Float {
        // спринт 44: почти вертикальное перо - направление не определено (у многих дигитайзеров ориентация считается
        // из наклона): штамп не сплющен, и поворот кисти из её настроек не подменяется случайным углом
        if (!s.active || s.mode != Mode.ANGLE || !orientationRadians.isFinite() || !(t >= MIN_ANGLE_TILT)) return Float.NaN
        val pi = Math.PI.toFloat()
        // экран = R(поворот) · F(отражение) · холст: сначала снимаем поворот, затем отражение вида (спринт 58 -
        // раньше у отражённого холста плоское перо ложилось зеркально к направлению пера)
        var a = orientationRadians - pi / 2 - canvasRotation
        if (flipX) a = pi - a
        if (flipY) a = -a
        val twoPi = 2 * pi
        return ((a % twoPi) + twoPi) % twoPi
    }

    /** Подпись для тестовой площадки: «Наклон пера 30° → толщина ×1.4» (шаблоны переводятся RU/EN). */
    fun describe(s: Settings, tiltRadians: Float): String {
        val deg = StylusSample.tiltDegrees(tiltRadians) ?: return "Датчика наклона нет: кисть рисует без изменений"
        val t = norm(tiltRadians)
        return when {
            !s.active -> "Наклон пера $deg°: не влияет на кисть (выберите режим ниже)"
            s.mode == Mode.SIZE -> "Наклон пера $deg° → толщина ×${fmt(sizeFactor(s, t))}"
            s.mode == Mode.SHADING -> "Наклон пера $deg° → штриховка: толщина ×${fmt(sizeFactor(s, t))}, плотность ${Math.round(flowFactor(s, t) * 100)} %"
            t < MIN_ANGLE_TILT -> "Наклон пера $deg° → перо почти вертикально: штамп не меняется"
            else -> "Наклон пера $deg° → штамп сплющен до ${Math.round(roundness(s, t, 1f) * 100)} % и повёрнут по перу"
        }
    }

    private fun fmt(f: Float): String { val r = Math.round(f * 10); return "${r / 10}.${r % 10}" }
}
