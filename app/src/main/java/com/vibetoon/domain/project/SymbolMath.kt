package com.vibetoon.domain.project

/** Маркер кадра внутри символа: текстовая метка на внутреннем кадре (Rest, AI, E, O, U, MBF, L, W, Smile...). */
data class FrameMarker(val frame: Int, val name: String, val thumb: String = "")

/** План режима «Слои как кадры»: каждому слою свой кадр (клип слоя = один кадр) и маркер с именем слоя. */
class FramesPlan(val clips: List<Triple<Int, Int, Int>>, val markers: List<FrameMarker>, val duration: Int)

/** Ключ времени экземпляра символа (Time Remapping): с кадра сцены [frame] символ показывает свой внутренний кадр [value], без интерполяции (Hold). */
data class TimeKey(val frame: Int, val value: Int)

/** Набор меток для липсинга. */
val PHONEME_MARKERS = listOf("Rest", "AI", "E", "O", "U", "MBF", "L", "W")

/** Предельная вложенность символов (защита от зацикливания рекурсивного рендера). */
const val MAX_SYMBOL_DEPTH = 4

/** Чистая математика символов (вложенных сцен): локальное время, Time Remapping, маркеры. */
object SymbolMath {
    /** Кадр вложенной сцены: localFrame = globalFrame % symbolDuration (цикл). */
    fun localFrame(global: Int, duration: Int): Int = if (duration <= 0) 0 else Math.floorMod(global, duration)

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

    /** Time Remapping: внутренний кадр по ключам времени (Hold - значение держится до следующего ключа); до первого ключа - его значение; без ключей - null. */
    fun timeAt(keys: List<TimeKey>, frame: Int): Int? {
        if (keys.isEmpty()) return null
        val sorted = keys.sortedBy { it.frame }
        return (sorted.lastOrNull { it.frame <= frame } ?: sorted.first()).value
    }

    fun withKey(keys: List<TimeKey>, key: TimeKey): List<TimeKey> = (keys.filter { it.frame != key.frame } + key).sortedBy { it.frame }
    fun withoutKey(keys: List<TimeKey>, frame: Int): List<TimeKey> = keys.filter { it.frame != frame }

    /** Слои [ids] по порядку: слой i виден только на кадрах [i*step, (i+1)*step), на этом кадре стоит маркер с именем слоя (повторы получают номер). */
    fun framesPlan(ids: List<Int>, names: List<String>, step: Int = 1): FramesPlan {
        val s = step.coerceAtLeast(1)
        val used = HashSet<String>()
        val clips = ArrayList<Triple<Int, Int, Int>>()
        val markers = ArrayList<FrameMarker>()
        ids.forEachIndexed { i, id ->
            val f = i * s
            clips += Triple(id, f, f + s)
            var n = names.getOrNull(i)?.trim().orEmpty().ifEmpty { "Кадр ${i + 1}" }
            if (!used.add(n)) { var k = 2; while (!used.add("$n $k")) k++; n = "$n $k" }
            markers += FrameMarker(f, n)
        }
        return FramesPlan(clips, markers, maxOf(1, ids.size * s))
    }

    fun withMarker(list: List<FrameMarker>, m: FrameMarker): List<FrameMarker> = (list.filter { it.frame != m.frame } + m).sortedBy { it.frame }
    fun withoutMarker(list: List<FrameMarker>, frame: Int): List<FrameMarker> = list.filter { it.frame != frame }

    // ---- качество и надёжность отрисовки экземпляров (Sprint 6.9.1) ----

    /** Длинная сторона миниатюры маркера, px: панель показывает её около 130 dp (≈ 390 px на xxhdpi), 96 px было мыльно. */
    const val THUMB_SIDE = 320

    /**
     * Размер картинки вложенной сцены символа: полный размер холста (экземпляр занимает весь холст, иначе текстура
     * растягивается и мылится), уменьшение только если холст больше предела GPU [maxSide]. Пропорции сохраняются.
     */
    fun renderSize(canvasW: Int, canvasH: Int, maxSide: Int): Pair<Int, Int> {
        val cw = canvasW.coerceAtLeast(1); val ch = canvasH.coerceAtLeast(1)
        val k = minOf(1.0, maxSide.coerceAtLeast(16).toDouble() / maxOf(cw, ch))
        return maxOf(1, Math.round(cw * k).toInt()) to maxOf(1, Math.round(ch * k).toInt())
    }

    /** Размер миниатюры с длинной стороной не больше [side] (никогда не больше исходника). */
    fun thumbSize(w: Int, h: Int, side: Int = THUMB_SIDE): Pair<Int, Int> {
        val k = minOf(1.0, side.toDouble() / maxOf(w, h, 1))
        return maxOf(1, Math.round(w * k).toInt()) to maxOf(1, Math.round(h * k).toInt())
    }

    /** Пауза перед повтором неудавшейся отрисовки экземпляра: 400, 800, 1600 ... до 5000 мс. */
    fun retryDelayMs(tries: Int): Long = minOf(5000L, 400L shl (tries - 1).coerceIn(0, 4))

    /** Ключ кэша картинок: определение, внутренний кадр и ревизия содержимого (растёт при любой правке символа). */
    fun cacheKey(defId: Int, local: Int, revision: Int): String = "$defId:$local:$revision"
}
