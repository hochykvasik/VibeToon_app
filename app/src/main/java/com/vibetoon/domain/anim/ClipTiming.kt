package com.vibetoon.domain.anim

import com.vibetoon.domain.project.Clip
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Спринт 51: точная работа с плашками слоёв на таймлайне - перенос и обрезка к плейхеду и флажкам (маркерам),
 * «вписать между флажками», магнит краёв плашки, числовой ввод начала/конца/длительности в секундах или кадрах.
 * Плашка видна на кадрах [start, end). Правило обрезки: к ПЛЕЙХЕДУ - кадр под плейхедом остаётся видимым
 * (как Trim to Playhead в монтажках), к ФЛАЖКУ - флажок является границей (следующий отрезок начинается на нём).
 * Чистые функции - JVM-тесты.
 */
object ClipTiming {
    /** Длина не меньше [Clip.MIN_LENGTH] и в границах проекта; прочие свойства (скорость, цикл...) сохраняются. */
    private fun fit(c: Clip, start: Int, end: Int, duration: Int): Clip {
        val d = duration.coerceAtLeast(Clip.MIN_LENGTH)
        val s = start.coerceIn(0, d - Clip.MIN_LENGTH)
        val e = end.coerceIn(s + Clip.MIN_LENGTH, d)
        return c.copy(start = s, end = e)
    }

    /** Перенос: начало на [frame], длина та же (у конца проекта плашка упирается и сохраняет длину, если влезает). */
    fun moveStartTo(c: Clip, frame: Int, duration: Int): Clip {
        val len = c.length.coerceIn(Clip.MIN_LENGTH, duration.coerceAtLeast(Clip.MIN_LENGTH))
        val s = frame.coerceIn(0, (duration - len).coerceAtLeast(0))
        return fit(c, s, s + len, duration)
    }

    /** Перенос так, чтобы плашка заканчивалась на границе [frame] (последний видимый кадр - frame - 1). */
    fun moveEndTo(c: Clip, frame: Int, duration: Int): Clip = moveStartTo(c, frame - c.length, duration)

    /** Обрезать слева: начало на [frame], конец на месте. */
    fun trimStartTo(c: Clip, frame: Int, duration: Int): Clip = fit(c, minOf(frame, c.end - Clip.MIN_LENGTH), c.end, duration)

    /** Обрезать справа: конец (не включая) на [end], начало на месте. */
    fun trimEndTo(c: Clip, end: Int, duration: Int): Clip = fit(c, c.start, maxOf(end, c.start + Clip.MIN_LENGTH), duration)

    /** Обрезать справа к плейхеду: кадр [frame] - последний видимый. */
    fun trimEndToPlayhead(c: Clip, frame: Int, duration: Int): Clip = trimEndTo(c, frame + 1, duration)

    /** Ближайший к [frame] флажок (при равенстве - левый); null - флажков нет. */
    fun nearestMarker(markers: Collection<Int>, frame: Int): Int? =
        markers.minWithOrNull(compareBy<Int> { abs(it - frame) }.thenBy { it })

    /** Отрезок между флажками вокруг [frame]: от последнего флажка <= frame (или 0) до первого > frame (или конца). */
    fun markerInterval(markers: Collection<Int>, frame: Int, duration: Int): IntArray {
        val sorted = markers.filter { it in 0..duration }.distinct().sorted()
        val a = sorted.lastOrNull { it <= frame } ?: 0
        val b = sorted.firstOrNull { it > frame } ?: duration
        return intArrayOf(a, maxOf(b, a + Clip.MIN_LENGTH))
    }

    /** «Вписать между флажками»: плашка занимает отрезок между флажками вокруг [frame]. */
    fun fitBetweenMarkers(c: Clip, markers: Collection<Int>, frame: Int, duration: Int): Clip {
        val r = markerInterval(markers, frame, duration)
        return fit(c, r[0], r[1], duration)
    }

    /** Точные начало и конец (конец не включая). */
    fun exact(c: Clip, start: Int, end: Int, duration: Int): Clip = fit(c, start, end, duration)

    /**
     * Магнит при перетаскивании плашки: [mode] 1 - левый край, 2 - правый край, 3 - вся плашка (прилипает тот край,
     * что ближе к цели). Возвращает новое начало (для 1 и 3) или конец (для 2). Цели - флажки, плейхед, края соседей.
     */
    fun snapDrag(mode: Int, start: Int, end: Int, targets: Collection<Int>, threshold: Int): Int {
        if (threshold <= 0 || targets.isEmpty()) return if (mode == 2) end else start
        return when (mode) {
            1 -> SnapMath.snap(start, targets, threshold)
            2 -> SnapMath.snap(end, targets, threshold)
            else -> {
                val len = end - start
                val s = SnapMath.snap(start, targets, threshold)
                val e = SnapMath.snap(end, targets, threshold)
                val ds = abs(s - start).takeIf { s != start } ?: Int.MAX_VALUE
                val de = abs(e - end).takeIf { e != end } ?: Int.MAX_VALUE
                when {
                    ds == Int.MAX_VALUE && de == Int.MAX_VALUE -> start
                    ds <= de -> s
                    else -> e - len
                }
            }
        }
    }

    /** Команды меню «Плашка» на таймлайне. */
    enum class Op { START_TO_PLAYHEAD, END_TO_PLAYHEAD, START_TO_MARKER, END_TO_MARKER,
        TRIM_START_TO_PLAYHEAD, TRIM_END_TO_PLAYHEAD, TRIM_START_TO_MARKER, TRIM_END_TO_MARKER, FIT_MARKERS }

    /** Нужен ли команде флажок. */
    fun needsMarker(op: Op): Boolean = op == Op.START_TO_MARKER || op == Op.END_TO_MARKER || op == Op.TRIM_START_TO_MARKER || op == Op.TRIM_END_TO_MARKER

    /**
     * Плашка после команды [op] при плейхеде на [frame]. Флажок - ближайший к плейхеду. null - команде нужен флажок,
     * а флажков нет (для «вписать между флажками» без флажков отрезок - весь проект).
     */
    fun apply(op: Op, c: Clip, frame: Int, markers: Collection<Int>, duration: Int): Clip? {
        val mk = nearestMarker(markers, frame)
        if (needsMarker(op) && mk == null) return null
        return when (op) {
            Op.START_TO_PLAYHEAD -> moveStartTo(c, frame, duration)
            Op.END_TO_PLAYHEAD -> moveEndTo(c, frame + 1, duration)
            Op.START_TO_MARKER -> moveStartTo(c, mk!!, duration)
            Op.END_TO_MARKER -> moveEndTo(c, mk!!, duration)
            Op.TRIM_START_TO_PLAYHEAD -> trimStartTo(c, frame, duration)
            Op.TRIM_END_TO_PLAYHEAD -> trimEndToPlayhead(c, frame, duration)
            Op.TRIM_START_TO_MARKER -> trimStartTo(c, mk!!, duration)
            Op.TRIM_END_TO_MARKER -> trimEndTo(c, mk!!, duration)
            Op.FIT_MARKERS -> fitBetweenMarkers(c, markers, frame, duration)
        }
    }

    /** Начало следующего слоя-рисунка: конец плашки текущего, а если плейхед уже за ней - сам плейхед. */
    fun nextDrawingStart(c: Clip, frame: Int): Int = if (!c.loop && frame >= c.end) frame else c.end

    /** Цели магнита для краёв плашки: флажки, плейхед, начало/конец проекта и края плашек других слоёв. */
    fun snapTargets(markers: Collection<Int>, playhead: Int, duration: Int, others: Collection<Clip>): Set<Int> {
        val t = HashSet<Int>()
        t += 0; t += duration; t += playhead
        t += markers
        for (c in others) { t += c.start; t += c.end }
        return t
    }

    // ---- числовой ввод ----

    /** Кадры -> текст: в секундах «2.5» (до сотых, без лишних нулей) или в кадрах «60». */
    fun format(frames: Int, fps: Int, seconds: Boolean): String {
        if (!seconds) return frames.toString()
        val v = frames.toDouble() / fps.coerceAtLeast(1)
        val r = (v * 100).roundToInt() / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    /**
     * Текст -> кадры. Единица по умолчанию [seconds]; суффикс её меняет: «2s», «2с», «2 сек» - секунды, «48f», «48к» -
     * кадры; «1:05» - минуты:секунды. Запятая как десятичный разделитель. null - не число или отрицательное.
     */
    private val SEC_SUFFIX = Regex("^(.*?)(сек|sec|s|с)$")
    private val FRAME_SUFFIX = Regex("^(.*?)(кадр|fr|f|к)$")

    fun parse(text: String, fps: Int, seconds: Boolean): Int? {
        val t = text.trim().lowercase().replace(',', '.').replace(" ", "")
        if (t.isEmpty()) return null
        val f = fps.coerceAtLeast(1)
        if (':' in t) {
            val p = t.split(':')
            if (p.size != 2) return null
            val m = p[0].toIntOrNull() ?: return null
            val s = p[1].toDoubleOrNull() ?: return null
            if (m < 0 || s < 0) return null
            return ((m * 60 + s) * f).roundToInt()
        }
        // суффиксы ввода (служебные шаблоны, не текст интерфейса): «сек», «с», «sec», «s» - секунды; «кадр», «к», «fr», «f» - кадры
        val (num, inSeconds) = SEC_SUFFIX.matchEntire(t)?.let { it.groupValues[1] to true }
            ?: FRAME_SUFFIX.matchEntire(t)?.let { it.groupValues[1] to false }
            ?: (t to seconds)
        val v = num.toDoubleOrNull() ?: return null
        if (v < 0 || v.isNaN() || v.isInfinite()) return null
        return if (inSeconds) (v * f).roundToInt() else v.roundToInt()
    }

    /**
     * Начало/конец/длительность из диалога (любые два из трёх; [changed] - что правили последним: 0 - начало, 1 - конец,
     * 2 - длительность). Правка начала или длительности держит длительность/начало, правка конца - начало.
     */
    fun resolve(start: Int, end: Int, length: Int, changed: Int, duration: Int): IntArray {
        val d = duration.coerceAtLeast(Clip.MIN_LENGTH)
        val (s, e) = when (changed) {
            1 -> start to end
            2 -> start to start + length
            else -> start to start + length
        }
        val s1 = s.coerceIn(0, d - Clip.MIN_LENGTH)
        val e1 = e.coerceIn(s1 + Clip.MIN_LENGTH, d)
        return intArrayOf(s1, e1)
    }
}

/**
 * Спринт 51: «Лесенка» с настройками. Порядок - порядок выделения слоёв ([reverse] - снизу вверх, то есть с конца
 * выделения). Первый в порядке слой остаётся на месте. [chain] = true - встык: начало следующего = конец предыдущего
 * + [step] кадров (step - промежуток, 0 - вплотную, отрицательный - нахлёст); false - каскад со сдвигом: начало
 * следующего = начало предыдущего + [step] кадров. Длины сохраняются; старт не уходит за 0.
 */
object StaggerPlan {
    const val MAX_STEP = 240

    /** Настройки лесенки: [chain] встык/каскад, [step] кадров (промежуток или сдвиг), [reverse] - снизу вверх. */
    data class Settings(val step: Int = 0, val chain: Boolean = true, val reverse: Boolean = false) {
        fun normalized() = copy(step = if (chain) step.coerceIn(-MAX_STEP, MAX_STEP) else step.coerceIn(1, MAX_STEP))
    }

    fun encode(s: Settings): String = s.normalized().let { "v1;c=${if (it.chain) 1 else 0};s=${it.step};r=${if (it.reverse) 1 else 0}" }

    fun decode(text: String?): Settings {
        if (text.isNullOrBlank() || !text.startsWith("v1")) return Settings()
        val kv = text.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val d = Settings()
        return Settings(kv["s"]?.toIntOrNull() ?: d.step, kv["c"]?.let { it == "1" } ?: d.chain, kv["r"]?.let { it == "1" } ?: d.reverse).normalized()
    }

    fun starts(starts: List<Int>, lengths: List<Int>, step: Int, chain: Boolean, reverse: Boolean): List<Int> {
        require(starts.size == lengths.size)
        val n = starts.size
        if (n == 0) return emptyList()
        val order = if (reverse) (n - 1 downTo 0).toList() else (0 until n).toList()
        val out = IntArray(n)
        var prevStart = starts[order[0]]
        var prevLen = lengths[order[0]]
        out[order[0]] = prevStart
        for (k in 1 until n) {
            val i = order[k]
            val s = (if (chain) prevStart + prevLen + step else prevStart + step).coerceAtLeast(0)
            out[i] = s
            prevStart = s; prevLen = lengths[i]
        }
        return out.toList()
    }
}
