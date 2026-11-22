package com.vibetoon.domain.paint

import com.vibetoon.domain.anim.PathMath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Режим накопления штриха (Stroke FBO): значения совпадают с `mode` в C++ (`Renderer::paintBegin`). */
enum class StrokeMode(val gl: Int) {
    /** Маркер: GL_MAX по 4 каналам, перекрытия штампов не темнее (непрозрачность ограничена). */
    CAP_OPACITY(0),
    /** Аэрограф: premultiplied "поверх", краска копится на перекрытиях (работает Flow). */
    ACCUMULATE(1),
    /** Ластик: маска стирания копится как у маркера, на запекании - Destination-Out. */
    ERASE(2)
}

enum class BrushShape(val gl: Int) { CIRCLE(0), SQUARE(1) }

/** Поворот штампа (a_DabRotation): без поворота, случайный (зерно графита), по направлению движения (квадратный маркер). */
enum class DabRotation { NONE, RANDOM, TRAJECTORY }

/**
 * Настройки кисти. [size] - диаметр в пикселях слоя (1..1500), [spacing] - базовый шаг (доля диаметра),
 * [minSizePercent] - размер при нулевом давлении, [flow] - непрозрачность одного штампа, [opacity] - непрозрачность
 * всего штриха (применяется при запекании Stroke FBO в слой), [hardness] 0..1 (0 - аэрограф, 1 - жёсткое перо).
 */
data class BrushParams(
    val size: Float = 24f,
    val spacing: Float = 0.22f,
    val minSizePercent: Float = 0.15f,
    val flow: Float = 1f,
    val opacity: Float = 1f,
    val hardness: Float = 0.85f,
    val mode: StrokeMode = StrokeMode.CAP_OPACITY,
    val shape: BrushShape = BrushShape.CIRCLE,
    val color: Int = 0xFF000000.toInt(),
    /** Зерно (карандаш, скринтон, бумага): вид, привязка (стоит на холсте или едет со штампом), масштаб и контраст. */
    val grain: GrainKind = GrainKind.NONE,
    val grainMode: GrainMode = GrainMode.NONE,
    val grainScale: Float = 1f,
    val grainContrast: Float = 1f,
    /** Сплющивание штампа по оси Y (1 - без; квадратный маркер 0.4). */
    val roundness: Float = 1f,
    /** 1 - двойной smoothstep по краю (градиентное перо), 0 - обычный. */
    val curve: Int = 0,
    val rotation: DabRotation = DabRotation.NONE,
    /** Длина (px) плавного нарастания толщины в начале штриха (Ink Pen); 0 - без. */
    val taperPx: Float = 0f,
    /** Штампы прижимаются к сетке пикселей слоя (пиксельная кисть). */
    val pixelSnap: Boolean = false,
    /** Идентификатор пресета (для меню и превью). */
    val presetId: String = "marker"
)

object BrushMath {
    const val MIN_SIZE = 1f
    const val MAX_SIZE = 1500f

    /** Логарифмический слайдер: Size = Min * (Max / Min)^t, t в 0..1 (можно поймать и 3 px, и 1500 px). */
    fun sizeFromSlider(t: Float, min: Float = MIN_SIZE, max: Float = MAX_SIZE): Float = min * (max / min).pow(t.coerceIn(0f, 1f))

    fun sliderFromSize(size: Float, min: Float = MIN_SIZE, max: Float = MAX_SIZE): Float =
        (ln(size.coerceIn(min, max) / min) / ln(max / min))

    /**
     * Умный шаг: чем больше кисть, тем реже штампы (иначе гигантская кисть вешает GPU):
     * EffectiveSpacing = clamp(base * (1 + R / 300), base, 0.4); если base сам больше 0.4, остаётся base.
     */
    fun effectiveSpacing(base: Float, radius: Float): Float = (base * (1f + radius / 300f)).coerceIn(base, max(base, 0.4f))

    /** Радиус штампа при давлении [pressure] 0..1: от minSizePercent до полного размера. */
    fun radiusAt(p: BrushParams, pressure: Float): Float =
        max(0.5f, p.size / 2f * (p.minSizePercent + (1f - p.minSizePercent) * pressure.coerceIn(0f, 1f)))
}

/**
 * Движок штампов: точки касания (с накопленными историческими точками между кадрами) превращаются в штампы вдоль
 * Centripetal Catmull-Rom сплайна. Координаты, давление и радиус интерполируются, шаг считается по накопителю
 * расстояния ([residual]), поэтому штампы идут равномерно и не слипаются на стыках событий. Штампы отдаются
 * плоским массивом по [STRIDE] чисел: x, y, радиус, непрозрачность, поворот (a_DabRotation, радианы).
 *
 * Сплайну нужна следующая точка, поэтому конец штриха отстаёт на одно событие; [end] выдаёт остаток.
 */
class DabStroker(private val params: BrushParams) {
    companion object { const val STRIDE = 5 }

    private class Pt(val x: Float, val y: Float, val p: Float)

    private val ctrl = ArrayList<Pt>()
    private var residual = 0f
    private var out = FloatArray(256)
    private var n = 0
    private var started = false
    private var traveled = 0f
    private var dirAngle = 0f
    private var rng = (System.nanoTime() and 0x7FFFFFFF).toInt() or 1

    private fun rotationNow(): Float = when (params.rotation) {
        DabRotation.NONE -> 0f
        DabRotation.TRAJECTORY -> dirAngle
        DabRotation.RANDOM -> {
            rng = rng * 1664525 + 1013904223
            (rng ushr 8) / 16777216f * (2f * Math.PI.toFloat())
        }
    }

    private fun emit(x: Float, y: Float, pressure: Float) {
        if ((n + 1) * STRIDE > out.size) out = out.copyOf(out.size * 2)
        var r = BrushMath.radiusAt(params, pressure)
        if (params.taperPx > 0f) r = max(0.5f, r * (0.2f + 0.8f * min(1f, traveled / params.taperPx)))
        var cx = x; var cy = y
        if (params.pixelSnap) {
            val d = Math.round(2f * BrushMath.radiusAt(params, pressure)).coerceAtLeast(1)
            r = d / 2f
            if (d % 2 == 1) { cx = kotlin.math.floor(x) + 0.5f; cy = kotlin.math.floor(y) + 0.5f } else { cx = Math.round(x).toFloat(); cy = Math.round(y).toFloat() }
        }
        val o = n * STRIDE
        out[o] = cx; out[o + 1] = cy; out[o + 2] = r
        out[o + 3] = params.flow.coerceIn(0f, 1f)
        out[o + 4] = rotationNow()
        n++
    }

    private fun take(): FloatArray {
        val r = out.copyOf(n * STRIDE)
        n = 0
        return r
    }

    private fun stepAt(pressure: Float): Float {
        val r = BrushMath.radiusAt(params, pressure)
        return max(2f * r * BrushMath.effectiveSpacing(params.spacing, r), 1f)
    }

    /** Первая точка штриха: сразу даёт штамп (тап рисует точку). */
    fun begin(x: Float, y: Float, pressure: Float): FloatArray {
        ctrl.clear(); residual = 0f; n = 0; started = true; traveled = 0f; dirAngle = 0f
        ctrl.add(Pt(x, y, pressure))
        emit(x, y, pressure)
        return take()
    }

    /** Следующая точка касания (в том числе историческая); возвращает новые штампы (возможно, пустой массив). */
    fun add(x: Float, y: Float, pressure: Float): FloatArray {
        if (!started) return begin(x, y, pressure)
        val last = ctrl.last()
        if (hypot(x - last.x, y - last.y) < 0.25f) return FloatArray(0)   // дребезг сенсора
        ctrl.add(Pt(x, y, pressure))
        val k = ctrl.size
        if (k >= 3) {
            val p1 = ctrl[k - 3]; val p2 = ctrl[k - 2]; val p3 = ctrl[k - 1]
            val p0 = if (k >= 4) ctrl[k - 4] else Pt(2 * p1.x - p2.x, 2 * p1.y - p2.y, p1.p)
            segment(p0, p1, p2, p3)
            if (ctrl.size > 4) ctrl.removeAt(0)
        }
        return take()
    }

    /** Конец штриха: дорисовывает последний сегмент (до последней точки). */
    fun end(): FloatArray {
        val k = ctrl.size
        if (started && k >= 2) {
            val p1 = ctrl[k - 2]; val p2 = ctrl[k - 1]
            val p0 = if (k >= 3) ctrl[k - 3] else Pt(2 * p1.x - p2.x, 2 * p1.y - p2.y, p1.p)
            val p3 = Pt(2 * p2.x - p1.x, 2 * p2.y - p1.y, p2.p)
            segment(p0, p1, p2, p3)
        }
        started = false
        ctrl.clear()
        return take()
    }

    private fun segment(p0: Pt, p1: Pt, p2: Pt, p3: Pt) {
        val chord = hypot(p2.x - p1.x, p2.y - p1.y)
        if (chord < 1e-4f) return
        dirAngle = kotlin.math.atan2(p2.y - p1.y, p2.x - p1.x)
        val minStep = stepAt(min(p1.p, p2.p))
        val subs = ceil(chord / max(minStep * 0.5f, 0.5f)).toInt().coerceIn(1, 512)
        fun tt(t: Float, a: Pt, b: Pt) = t + max(hypot(b.x - a.x, b.y - a.y), 1e-4f).pow(0.5f)
        val t0 = 0f; val t1 = tt(t0, p0, p1); val t2 = tt(t1, p1, p2); val t3 = tt(t2, p2, p3)
        var px = p1.x; var py = p1.y; var pp = p1.p
        for (i in 1..subs) {
            val u = i.toFloat() / subs
            val t = t1 + u * (t2 - t1)
            val cx = PathMath.cr(p0.x, p1.x, p2.x, p3.x, t0, t1, t2, t3, t)
            val cy = PathMath.cr(p0.y, p1.y, p2.y, p3.y, t0, t1, t2, t3, t)
            val cp = p1.p + (p2.p - p1.p) * u
            var seg = hypot(cx - px, cy - py)
            while (seg > 1e-6f && residual + seg >= stepAt(pp)) {
                val need = stepAt(pp) - residual           // сколько пройти до следующего штампа
                val f = need / seg
                px += (cx - px) * f; py += (cy - py) * f
                pp += (cp - pp) * f
                traveled += stepAt(pp)
                emit(px, py, pp)
                seg -= need
                residual = 0f
            }
            residual += seg
            px = cx; py = cy; pp = cp
        }
    }
}
