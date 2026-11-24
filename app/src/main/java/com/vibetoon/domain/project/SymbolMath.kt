package com.vibetoon.domain.project

/**
 * Маркер кадра внутри символа: именованная ссылка на форму (Rest, AI, E, O, U, MBF, L, W, Smile...).
 * Обычный маркер стоит на кадре своей формы. Копия/дубликат маркера - ссылка [form] на форму исходного маркера:
 * «Выбрать маркер» по копии показывает ту же форму, а не пустой кадр, куда копию вставили.
 */
data class FrameMarker(val frame: Int, val name: String, val thumb: String = "", val form: Int = -1) {
    /** Маркер-ссылка на форму другого кадра. */
    val isAlias: Boolean get() = form >= 0 && form != frame
    /** Внутренний кадр, который показывает символ при выборе этого маркера. */
    val target: Int get() = if (isAlias) form else frame
    fun normalized(): FrameMarker = if (form >= 0 && !isAlias) copy(form = -1) else this
}

/** План режима «Слои как кадры»: каждому слою свой кадр (клип слоя = один кадр) и маркер с именем слоя. */
class FramesPlan(val clips: List<Triple<Int, Int, Int>>, val markers: List<FrameMarker>, val duration: Int)

/**
 * Ключ времени экземпляра символа (Time Remapping): с кадра сцены [frame] символ показывает свой внутренний кадр [value].
 * [ease] - переход к СЛЕДУЮЩЕМУ ключу: [TimeEase.HOLD] (резкая смена формы, по умолчанию), [TimeEase.LINEAR] или
 * [TimeEase.SMOOTH] (внутренние кадры между значениями проигрываются по кривой).
 */
data class TimeKey(val frame: Int, val value: Int, val ease: Int = TimeEase.HOLD)

/** Переход между ключами времени (кривая сегмента). */
object TimeEase {
    const val HOLD = 0
    const val LINEAR = 1
    const val SMOOTH = 2
    const val EASE_IN = 3
    const val EASE_OUT = 4
    fun valid(e: Int) = e in HOLD..EASE_OUT
    /** Доля перехода 0..1 по кривой. */
    fun curve(e: Int, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (e) {
            LINEAR -> x
            SMOOTH -> x * x * (3f - 2f * x)
            EASE_IN -> x * x
            EASE_OUT -> 1f - (1f - x) * (1f - x)
            else -> 0f
        }
    }
}

/** Отрезок таймлайна, где символ показывает одну и ту же форму ([hold]) или проигрывает переход ([hold] = false). */
data class FormRun(val start: Int, val end: Int, val value: Int, val hold: Boolean, val toValue: Int = value)

/** Набор меток для липсинга. */
val PHONEME_MARKERS = listOf("Rest", "AI", "E", "O", "U", "MBF", "L", "W")

/** Предельная вложенность символов (защита от зацикливания рекурсивного рендера). */
const val MAX_SYMBOL_DEPTH = 4

/** Чистая математика символов (вложенных сцен): локальное время, Time Remapping, маркеры. */
object SymbolMath {
    /** Кадр вложенной сцены: localFrame = globalFrame % symbolDuration (цикл). */
    fun localFrame(global: Int, duration: Int): Int = if (duration <= 0) 0 else Math.floorMod(global, duration)

    /**
     * Кадр символа без ключей времени: цикл только при настоящей анимации участников
     * (дорожки/кадры/видео), иначе держим кадр 0 — символ не «шевелится» сам по себе.
     * Клипы режима «Слои как кадры» сами по себе цикл не запускают.
     */
    fun defaultFrame(global: Int, duration: Int, animated: Boolean): Int =
        if (animated) localFrame(global, duration) else 0

    /** Дорожка действительно анимирует: минимум два ключа с разными значениями (один ключ или одинаковые - статика). */
    fun trackMoves(keys: List<com.vibetoon.domain.anim.Keyframe>): Boolean {
        if (keys.size < 2) return false
        val k0 = keys.first()
        return keys.any { it.dx != k0.dx || it.dy != k0.dy || it.scaleX != k0.scaleX || it.scaleY != k0.scaleY || it.rotation != k0.rotation || it.opacity != k0.opacity }
    }

    /** Ключи времени меняют кадр: минимум два ключа с разными внутренними кадрами. */
    fun timeKeysMove(keys: List<TimeKey>): Boolean = keys.map { it.value }.distinct().size > 1

    /** Длительность символа: последний кадр с ключом/рисунком среди участников + 1; статичный символ - [fallback] (кадр проекта). */
    fun duration(lastFrames: List<Int>, fallback: Int): Int {
        val last = lastFrames.maxOrNull() ?: 0
        return if (last <= 0) fallback.coerceAtLeast(1) else last + 1
    }

    /** Все id под группой [root] (рекурсивно), по карте "слой -> родитель". */
    fun descendants(root: Int, parentOf: Map<Int, Int>): Set<Int> {
        val out = HashSet<Int>()
        var changed = true
        while (changed) {
            changed = false
            for ((id, p) in parentOf) if (id !in out && (p == root || p in out)) { out += id; changed = true }
        }
        return out
    }

    /**
     * Time Remapping: внутренний кадр по ключам времени. Hold - значение держится до следующего ключа (один ключ = форма
     * на всё время); Linear/Smooth - внутренние кадры между значениями соседних ключей идут по кривой. До первого ключа -
     * его значение; без ключей - null.
     */
    fun timeAt(keys: List<TimeKey>, frame: Int): Int? {
        if (keys.isEmpty()) return null
        val sorted = if (keys.size < 2 || keys.zipWithNext().all { (a, b) -> a.frame <= b.frame }) keys else keys.sortedBy { it.frame }
        val i = sorted.indexOfLast { it.frame <= frame }
        if (i < 0) return sorted.first().value
        val a = sorted[i]
        val b = sorted.getOrNull(i + 1) ?: return a.value
        if (a.ease == TimeEase.HOLD || !TimeEase.valid(a.ease) || b.frame <= a.frame) return a.value
        val t = (frame - a.frame).toFloat() / (b.frame - a.frame)
        return Math.round(a.value + (b.value - a.value) * TimeEase.curve(a.ease, t))
    }

    /**
     * Отрезки одинаковой формы для подсветки на таймлайне: соседние Hold-сегменты с тем же значением сливаются в один
     * (ключ-повтор не разрывает полосу), переходы - отдельные отрезки. [end] - кадр конца проекта (не включительно).
     */
    fun formRuns(keys: List<TimeKey>, end: Int): List<FormRun> {
        if (keys.isEmpty() || end <= 0) return emptyList()
        val ks = keys.sortedBy { it.frame }
        val out = ArrayList<FormRun>()
        for ((i, k) in ks.withIndex()) {
            val next = ks.getOrNull(i + 1)
            val s = if (i == 0) 0 else k.frame
            val e = (next?.frame ?: end).coerceAtMost(end)
            if (e <= s) continue
            val hold = next == null || k.ease == TimeEase.HOLD || next.value == k.value
            val run = FormRun(s, e, k.value, hold, if (hold) k.value else next!!.value)
            val prev = out.lastOrNull()
            if (prev != null && prev.hold && run.hold && prev.value == run.value && prev.end == run.start) out[out.size - 1] = prev.copy(end = run.end)
            else out += run
        }
        return out
    }

    fun withKey(keys: List<TimeKey>, key: TimeKey): List<TimeKey> = (keys.filter { it.frame != key.frame } + key).sortedBy { it.frame }

    /** Кадр символа экземпляра без ключей: Hold на кадре 0, цикл - только если включён [loop] и участники реально анимированы. */
    fun instanceFrame(keys: List<TimeKey>, global: Int, duration: Int, animated: Boolean, loop: Boolean): Int =
        timeAt(keys, global)?.coerceIn(0, maxOf(0, duration - 1)) ?: defaultFrame(global, duration, animated && loop)
    fun withoutKey(keys: List<TimeKey>, frame: Int): List<TimeKey> = keys.filter { it.frame != frame }

    /** Стандартное имя маркера «Кадр N» (русский ключ словаря). */
    const val DEFAULT_FRAME = "Кадр"

    /**
     * Слои [ids] по порядку: слой i виден только на кадрах [i*step, (i+1)*step), на этом кадре стоит маркер с именем слоя (повторы получают номер).
     * [frameName] - имя маркера для слоя без имени (номер с 1); приложение передаёт перевод на текущий язык (спринт 30).
     */
    fun framesPlan(ids: List<Int>, names: List<String>, step: Int = 1, frameName: (Int) -> String = { "$DEFAULT_FRAME $it" }): FramesPlan {
        val s = step.coerceAtLeast(1)
        val used = HashSet<String>()
        val clips = ArrayList<Triple<Int, Int, Int>>()
        val markers = ArrayList<FrameMarker>()
        ids.forEachIndexed { i, id ->
            val f = i * s
            clips += Triple(id, f, f + s)
            var n = names.getOrNull(i)?.trim().orEmpty().ifEmpty { frameName(i + 1) }
            if (!used.add(n)) { var k = 2; while (!used.add("$n $k")) k++; n = "$n $k" }
            markers += FrameMarker(f, n)
        }
        return FramesPlan(clips, markers, maxOf(1, ids.size * s))
    }

    fun withMarker(list: List<FrameMarker>, m: FrameMarker): List<FrameMarker> = (list.filter { it.frame != m.frame } + m).sortedBy { it.frame }
    fun withoutMarker(list: List<FrameMarker>, frame: Int): List<FrameMarker> = list.filter { it.frame != frame }
}
