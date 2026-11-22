package com.vibetoon.domain.anim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Узел кривой плавности: время [x] (доля сегмента 0..1), значение [y] (доля пути; может выходить за 0..1) и два
 * вектора усиков относительно узла: входящий ([inX] <= 0) и исходящий ([outX] >= 0).
 */
data class CurveNode(val x: Float, val y: Float, val inX: Float, val inY: Float, val outX: Float, val outY: Float)

/**
 * Многоузловая кривая плавности: кубические сегменты Безье между соседними узлами, проходящие через все узлы.
 * Первый узел всегда (0, 0), последний (1, 1). Хранится в [Keyframe.curve] списком чисел:
 * `[n, x, y, inX, inY, outX, outY, ...]` (6 чисел на узел); старый формат из 4 чисел (x1, y1, x2, y2) читается как
 * два узла с усиками.
 */
class Curve(val nodes: List<CurveNode>) {
    /** Значение на доле времени [t] 0..1. */
    fun eval(t: Float): Float {
        if (t <= 0f) return nodes.first().y
        if (t >= 1f) return nodes.last().y
        var i = 0
        while (i < nodes.size - 2 && nodes[i + 1].x <= t) i++
        val a = nodes[i]; val b = nodes[i + 1]
        val span = (b.x - a.x).coerceAtLeast(1e-5f)
        // усики не могут выйти за сегмент по времени: x(s) остаётся монотонной
        val p1x = a.x + a.outX.coerceIn(0f, span); val p1y = a.y + a.outY
        val p2x = b.x + b.inX.coerceIn(-span, 0f); val p2y = b.y + b.inY
        return bezierY(a.x, p1x, p2x, b.x, a.y, p1y, p2y, b.y, t)
    }

    fun toList(): List<Float> = buildList {
        add(nodes.size.toFloat())
        for (n in nodes) { add(n.x); add(n.y); add(n.inX); add(n.inY); add(n.outX); add(n.outY) }
    }

    fun withNode(i: Int, n: CurveNode) = Curve(nodes.toMutableList().also { it[i] = n })

    /** Добавляет узел на кривой в момент [x] (усики по касательной, чтобы форма не изменилась). */
    fun insertAt(x: Float): Curve {
        val t = x.coerceIn(0.02f, 0.98f)
        var i = 0
        while (i < nodes.size - 2 && nodes[i + 1].x <= t) i++
        val a = nodes[i]; val b = nodes[i + 1]
        if (t - a.x < 0.02f || b.x - t < 0.02f) return this
        val y = eval(t)
        val d = 0.001f
        val slope = (eval((t + d).coerceAtMost(1f)) - eval((t - d).coerceAtLeast(0f))) / (2f * d)
        val h = minOf(t - a.x, b.x - t) / 3f
        val n = CurveNode(t, y, -h, -slope * h, h, slope * h)
        return Curve(nodes.toMutableList().also { it.add(i + 1, n) })
    }

    fun without(i: Int): Curve = if (i <= 0 || i >= nodes.size - 1) this else Curve(nodes.filterIndexed { k, _ -> k != i })

    companion object {
        val LINEAR = Curve(listOf(CurveNode(0f, 0f, 0f, 0f, 1f / 3f, 1f / 3f), CurveNode(1f, 1f, -1f / 3f, -1f / 3f, 0f, 0f)))

        /** Читает кривую из списка чисел (новый или старый формат); null, если список неверный. */
        fun parse(list: List<Float>?): Curve? {
            if (list == null || list.any { !it.isFinite() }) return null
            if (list.size == 4) {
                val (x1, y1, x2, y2) = list
                return Curve(listOf(
                    CurveNode(0f, 0f, 0f, 0f, x1.coerceIn(0f, 1f), y1),
                    CurveNode(1f, 1f, x2.coerceIn(0f, 1f) - 1f, y2 - 1f, 0f, 0f)
                ))
            }
            val n = list.firstOrNull()?.toInt() ?: return null
            if (n < 2 || n > 64 || list.size != 1 + n * 6) return null
            val nodes = List(n) { i ->
                val o = 1 + i * 6
                CurveNode(list[o].coerceIn(0f, 1f), list[o + 1], list[o + 2].coerceAtMost(0f), list[o + 3], list[o + 4].coerceAtLeast(0f), list[o + 5])
            }
            val sorted = nodes.sortedBy { it.x }
            return Curve(sorted.toMutableList().also {
                it[0] = it[0].copy(x = 0f, y = 0f)
                it[it.size - 1] = it[it.size - 1].copy(x = 1f, y = 1f)
            })
        }

        /** Кривая по типу плавности (старые пресеты). */
        fun of(e: EasingType): Curve = parse(listOf(e.x1, e.y1, e.x2, e.y2)) ?: LINEAR

        /** Кривая по функции f(t) на [0,1]: узлы по выборке, усики - по Catmull-Rom (плавно через все узлы). */
        fun fromFunction(samples: Int, f: (Float) -> Float): Curve {
            val xs = FloatArray(samples + 1) { it / samples.toFloat() }
            val ys = FloatArray(samples + 1) { f(xs[it]) }
            ys[0] = 0f; ys[samples] = 1f
            val nodes = ArrayList<CurveNode>()
            for (i in 0..samples) {
                val pi = (i - 1).coerceAtLeast(0); val ni = (i + 1).coerceAtMost(samples)
                val slope = (ys[ni] - ys[pi]) / (xs[ni] - xs[pi]).coerceAtLeast(1e-5f)
                val hIn = if (i == 0) 0f else (xs[i] - xs[i - 1]) / 3f
                val hOut = if (i == samples) 0f else (xs[i + 1] - xs[i]) / 3f
                nodes += CurveNode(xs[i], ys[i], -hIn, -slope * hIn, hOut, slope * hOut)
            }
            return Curve(nodes)
        }

        val PRESETS: List<Pair<String, () -> Curve>> = listOf(
            "Linear" to { LINEAR },
            "Ease In" to { of(EasingType.EASE_IN) },
            "Ease Out" to { of(EasingType.EASE_OUT) },
            "Ease In-Out" to { of(EasingType.EASE_IN_OUT) },
            "Bounce" to { fromFunction(24) { bounceOut(it) } },
            "Elastic" to { fromFunction(24) { elasticOut(it) } },
            "Back (перелёт)" to { fromFunction(12) { backOut(it) } },
            "Wave" to { fromFunction(24) { t -> t + 0.12f * sin(2f * PI.toFloat() * 3f * t) * (1f - t) } }
        )

        private fun bounceOut(x: Float): Float {
            val n = 7.5625f; val d = 2.75f
            return when {
                x < 1f / d -> n * x * x
                x < 2f / d -> { val t = x - 1.5f / d; n * t * t + 0.75f }
                x < 2.5f / d -> { val t = x - 2.25f / d; n * t * t + 0.9375f }
                else -> { val t = x - 2.625f / d; n * t * t + 0.984375f }
            }
        }

        private fun elasticOut(x: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            val c = (2f * PI / 3f).toFloat()
            return 2f.pow(-10f * x) * sin((x * 10f - 0.75f) * c) + 1f
        }

        private fun backOut(x: Float): Float {
            val c1 = 1.70158f; val c3 = c1 + 1f
            return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
        }

        private fun at(a: Float, b: Float, c: Float, d: Float, s: Float): Float {
            val u = 1f - s
            return u * u * u * a + 3f * u * u * s * b + 3f * u * s * s * c + s * s * s * d
        }

        private fun bezierY(x0: Float, x1: Float, x2: Float, x3: Float, y0: Float, y1: Float, y2: Float, y3: Float, t: Float): Float {
            var lo = 0f; var hi = 1f
            var s = ((t - x0) / (x3 - x0).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
            for (i in 0 until 40) {
                val x = at(x0, x1, x2, x3, s)
                if (abs(x - t) < 1e-6f) break
                if (x < t) lo = s else hi = s
                s = (lo + hi) / 2f
            }
            return at(y0, y1, y2, y3, s)
        }
    }
}
