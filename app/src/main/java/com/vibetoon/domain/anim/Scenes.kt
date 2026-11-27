package com.vibetoon.domain.anim

/**
 * Сцена проекта (пакет 9): именованная часть общего таймлайна [start, end) в кадрах. Сцены не пересекаются и идут
 * по порядку; содержимое (слои, ключи) общее - сцена задаёт, какой отрезок проигрывать, показывать и экспортировать.
 */
data class SceneRange(
    val id: Int, val name: String, val start: Int, val end: Int,
    /** Пакет 24: свои слои сцены (id узлов). Слой, указанный хотя бы в одной сцене, виден только в своих сценах; остальные - общие. */
    val layers: Set<Int> = emptySet(),
    /** Пакет 24: переход В эту сцену из предыдущей (ключ [SceneTransition]) и его длина в кадрах. */
    val transition: String = SceneTransition.CUT.key,
    val transitionFrames: Int = 0,
    /** Пакет 24: свой фон холста сцены (ARGB) или null - фон проекта. */
    val background: Int? = null
) {
    val length: Int get() = (end - start).coerceAtLeast(0)
    operator fun contains(frame: Int): Boolean = frame in start until end
}

/** Чистые операции над списком сцен - unit-тест. */
object SceneMath {
    const val MAX_NAME = 32
    const val MAX_SCENES = 64

    /** По порядку, в пределах [0, duration), без пересечений и пустых отрезков, уникальные id. */
    fun normalize(scenes: List<SceneRange>, duration: Int): List<SceneRange> {
        if (scenes.isEmpty() || duration <= 0) return emptyList()
        val out = ArrayList<SceneRange>()
        val ids = HashSet<Int>()
        var prevEnd = 0
        for (s in scenes.sortedWith(compareBy({ it.start }, { it.id }))) {
            val start = maxOf(s.start, prevEnd, 0)
            val end = minOf(s.end, duration)
            if (end <= start || !ids.add(s.id)) continue
            out += s.copy(start = start, end = end, name = cleanName(s.name).ifEmpty { "$DEFAULT_PREFIX ${out.size + 1}" })
            prevEnd = end
            if (out.size >= MAX_SCENES) break
        }
        return out
    }

    fun cleanName(name: String): String = name.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_NAME).trim()

    /** Сцена, в которую попадает кадр, или null (кадр вне сцен). */
    fun at(scenes: List<SceneRange>, frame: Int): SceneRange? = scenes.firstOrNull { frame in it }

    fun nextId(scenes: List<SceneRange>): Int = (scenes.maxOfOrNull { it.id } ?: 0) + 1

    /** Стандартное имя хранится по-русски («Сцена N»), в интерфейсе переводится при показе (ScenesUi.sceneDisplayName). */
    const val DEFAULT_PREFIX = "Сцена"

    /** Имя «Сцена N», не занятое другими сценами. */
    fun defaultName(scenes: List<SceneRange>): String {
        var n = scenes.size + 1
        while (scenes.any { it.name == "$DEFAULT_PREFIX $n" }) n++
        return "$DEFAULT_PREFIX $n"
    }

    /**
     * «+ Новая сцена»: если сцен нет, весь текущий таймлайн становится «Сцена 1»; новая сцена длиной [length]
     * добавляется в конец, при необходимости проект удлиняется. Результат: сцены и новая длительность проекта.
     */
    data class Added(val scenes: List<SceneRange>, val duration: Int, val sceneId: Int)

    fun add(scenes: List<SceneRange>, duration: Int, length: Int): Added? {
        val cur = normalize(scenes, duration)
        if (cur.size >= MAX_SCENES || length <= 0) return null
        val base = if (cur.isEmpty()) listOf(SceneRange(1, "$DEFAULT_PREFIX 1", 0, duration)) else cur
        val start = maxOf(base.last().end, if (cur.isEmpty()) duration else 0)
        val id = nextId(base)
        val added = SceneRange(id, defaultName(base), start, start + length)
        val newDuration = maxOf(duration, added.end)
        return Added(normalize(base + added, newDuration), newDuration, id)
    }

    /**
     * «Разрезать на плейхеде»: сцена, в которой стоит [frame], делится на две (вторая получает новое имя).
     * Без сцен - таймлайн делится на «Сцена 1» и «Сцена 2». Разрез на первом кадре сцены невозможен (null).
     */
    fun split(scenes: List<SceneRange>, duration: Int, frame: Int): List<SceneRange>? {
        val cur = normalize(scenes, duration).ifEmpty { listOf(SceneRange(1, "$DEFAULT_PREFIX 1", 0, duration)) }
        if (cur.size >= MAX_SCENES) return null
        val s = at(cur, frame) ?: return null
        if (frame <= s.start || frame >= s.end) return null
        // вторая половина - тот же холст (свои слои), без перехода
        val second = s.copy(id = nextId(cur), name = defaultName(cur), start = frame, transition = SceneTransition.CUT.key, transitionFrames = 0)
        return normalize(cur.map { if (it.id == s.id) it.copy(end = frame) else it } + second, duration)
    }

    fun rename(scenes: List<SceneRange>, id: Int, name: String): List<SceneRange>? {
        val clean = cleanName(name)
        val s = scenes.firstOrNull { it.id == id } ?: return null
        if (clean.isEmpty() || clean == s.name) return null
        return scenes.map { if (it.id == id) it.copy(name = clean) else it }
    }

    /**
     * Удалить сцену (только метку - кадры и ключи остаются): её отрезок присоединяется к соседней сцене слева,
     * у первой сцены - к следующей. Последняя оставшаяся сцена просто убирается (таймлайн без сцен).
     */
    fun remove(scenes: List<SceneRange>, id: Int): List<SceneRange> {
        val i = scenes.indexOfFirst { it.id == id }
        if (i < 0) return scenes
        val s = scenes[i]
        val out = scenes.toMutableList()
        out.removeAt(i)
        if (out.isEmpty()) return out
        // свои слои удалённой сцены переходят к соседней: кадры отрезка выглядят как прежде
        if (i > 0) out[i - 1] = out[i - 1].copy(end = maxOf(out[i - 1].end, s.end), layers = out[i - 1].layers + s.layers)
        else out[0] = out[0].copy(start = minOf(out[0].start, s.start), layers = out[0].layers + s.layers, transition = SceneTransition.CUT.key, transitionFrames = 0)
        return out
    }

    /** Соседняя сцена относительно кадра: [dir] = -1 предыдущая, +1 следующая. Вне сцен - ближайшая в эту сторону. */
    fun step(scenes: List<SceneRange>, frame: Int, dir: Int): SceneRange? {
        val cur = at(scenes, frame)
        return if (dir < 0) {
            if (cur != null) scenes.lastOrNull { it.end <= cur.start } else scenes.lastOrNull { it.end <= frame }
        } else {
            if (cur != null) scenes.firstOrNull { it.start >= cur.end } else scenes.firstOrNull { it.start > frame }
        }
    }

    /** Кадр проигрывания внутри сцены [range] (с циклом или с остановкой на последнем кадре сцены). */
    fun playFrame(elapsedFrames: Long, startFrame: Int, range: SceneRange, loop: Boolean): Int {
        val len = range.length.coerceAtLeast(1)
        val offset = (startFrame - range.start).coerceIn(0, len - 1).toLong() + elapsedFrames.coerceAtLeast(0)
        return if (loop) range.start + (offset % len).toInt() else range.start + minOf(offset, (len - 1).toLong()).toInt()
    }

    /** Кадр проекта для кадра [index] экспорта отрезка [range] (частоты проекта и видео могут отличаться). */
    fun exportFrame(index: Int, projectFps: Int, outFps: Int, range: SceneRange): Int =
        range.start + ExportMath.projectFrame(index, projectFps, outFps, range.length.coerceAtLeast(1))
}

/** Пакет 24: переход между сценами (в начале сцены, из предыдущей соседней сцены). */
enum class SceneTransition(val key: String, val title: String) {
    CUT("cut", "Без перехода"),
    DISSOLVE("dissolve", "Растворение"),
    FADE_BLACK("black", "Через чёрный"),
    FADE_WHITE("white", "Через белый"),
    PUSH("push", "Сдвиг влево");

    companion object {
        fun of(key: String?): SceneTransition = entries.firstOrNull { it.key == key } ?: CUT
    }
}

/**
 * Пакет 24: отдельные холсты сцен и переходы. Свой слой сцены (SceneRange.layers) виден только на кадрах своих сцен,
 * общий слой - везде. Переход в сцену S из предыдущей соседней сцены P занимает окно вокруг стыка: половина длины -
 * последние кадры P, половина - первые кадры S. В окне:
 *  - «Растворение»: свои слои P гаснут, свои слои S проявляются (общие без изменений);
 *  - «Через чёрный/белый»: весь кадр уходит в цвет к стыку и выходит из него (слои по своим сценам);
 *  - «Сдвиг влево»: свои слои P уезжают влево, свои слои S въезжают справа на ширину холста.
 * Чистые функции - unit-тест; редактор и экспорт применяют один и тот же [look].
 */
object SceneCanvas {
    const val MAX_TRANSITION = 240

    /**
     * Вид кадра: множитель прозрачности своих слоёв (нет в карте - 1), сдвиг по X в долях ширины холста,
     * затемнение кадра в цвет перехода ([overlay] 0..1, белый/чёрный) и фон кадра (null - фон проекта без изменений).
     */
    data class Look(val alpha: Map<Int, Float>, val shiftX: Map<Int, Float>, val overlay: Float, val overlayWhite: Boolean, val background: Int? = null) {
        val isNone: Boolean get() = alpha.isEmpty() && shiftX.isEmpty() && overlay <= 0f && background == null
        companion object { val NONE = Look(emptyMap(), emptyMap(), 0f, false) }
    }

    /** Все свои слои сцен. */
    fun owned(scenes: List<SceneRange>): Set<Int> = scenes.flatMapTo(HashSet()) { it.layers }

    /** Используются ли отдельные холсты или переходы (иначе вид кадра всегда [Look.NONE]). */
    fun active(scenes: List<SceneRange>): Boolean =
        scenes.any { it.layers.isNotEmpty() || it.background != null || SceneTransition.of(it.transition) != SceneTransition.CUT }

    /** Длина перехода в сцену [i] с учётом соседей (0 - перехода нет: первая сцена, зазор между сценами, «Без перехода»). */
    fun transitionLength(scenes: List<SceneRange>, i: Int): Int {
        val s = scenes.getOrNull(i) ?: return 0
        val p = scenes.getOrNull(i - 1) ?: return 0
        if (p.end != s.start || SceneTransition.of(s.transition) == SceneTransition.CUT) return 0
        return minOf(s.transitionFrames, s.length, p.length, MAX_TRANSITION).coerceAtLeast(0)
    }

    /** Покомпонентное смешивание ARGB. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        var out = 0
        for (sh in intArrayOf(24, 16, 8, 0)) {
            val ca = (a ushr sh) and 0xFF; val cb = (b ushr sh) and 0xFF
            out = out or (Math.round(ca + (cb - ca) * k).coerceIn(0, 255) shl sh)
        }
        return out
    }

    /** Вид кадра [frame]; [projectBg] - фон проекта (ARGB). */
    fun look(scenes: List<SceneRange>, frame: Int, projectBg: Int = 0): Look {
        if (scenes.isEmpty() || !active(scenes)) return Look.NONE
        val owned = owned(scenes)
        val cur = SceneMath.at(scenes, frame)
        val alpha = HashMap<Int, Float>()
        for (id in owned) if (cur == null || id !in cur.layers) alpha[id] = 0f
        fun bgOf(sc: SceneRange?): Int = sc?.background ?: projectBg
        fun bgOut(c: Int): Int? = if (c == projectBg) null else c
        // окно перехода: стык сцен i-1 | i, половина до стыка (a кадров) и после (b кадров)
        for (i in 1 until scenes.size) {
            val n = transitionLength(scenes, i)
            if (n <= 0) continue
            val s = scenes[i]; val p = scenes[i - 1]
            val a = n / 2; val b = n - a
            val k = frame - s.start
            if (k < -a || k >= b) continue
            val t = ((k + a) + 0.5f) / n   // 0..1 по окну
            val type = SceneTransition.of(s.transition)
            if (type == SceneTransition.FADE_BLACK || type == SceneTransition.FADE_WHITE) {
                val o = (1f - kotlin.math.abs(k + 0.5f) / (n / 2f)).coerceIn(0f, 1f)
                val white = type == SceneTransition.FADE_WHITE
                val bg = mix(bgOf(cur), if (white) -1 else 0xFF000000.toInt(), o)
                return Look(alpha, emptyMap(), o, white, bgOut(bg))
            }
            val shift = HashMap<Int, Float>()
            for (id in owned) {
                val inP = id in p.layers; val inS = id in s.layers
                if (inP == inS) continue   // слой обеих сцен (или ни одной) - как обычно
                when (type) {
                    SceneTransition.DISSOLVE -> alpha[id] = if (inP) 1f - t else t
                    SceneTransition.PUSH -> { alpha.remove(id); shift[id] = if (inP) -t else 1f - t }
                    else -> Unit
                }
            }
            return Look(alpha, shift, 0f, false, bgOut(mix(bgOf(p), bgOf(s), t)))
        }
        return Look(alpha, emptyMap(), 0f, false, bgOut(bgOf(cur)))
    }

    /**
     * Видимость слоя [layer] в сцене [sceneId]: общий слой (ни в одной сцене) при выключении становится своим для всех
     * остальных сцен. Слой, исчезающий из всех сцен, не допускается (null).
     */
    fun toggle(scenes: List<SceneRange>, layer: Int, sceneId: Int, visible: Boolean): List<SceneRange>? {
        if (scenes.none { it.id == sceneId }) return null
        val shared = scenes.none { layer in it.layers }
        val next = scenes.map { sc ->
            val has = if (shared) true else layer in sc.layers
            val want = if (sc.id == sceneId) visible else has
            sc.copy(layers = if (want) sc.layers + layer else sc.layers - layer)
        }
        if (next.none { layer in it.layers }) return null
        return next
    }

    /** «Во всех сценах»: слой снова общий. */
    fun share(scenes: List<SceneRange>, layer: Int): List<SceneRange> = scenes.map { it.copy(layers = it.layers - layer) }

    /** Видим ли слой на холсте сцены. */
    fun visibleIn(scenes: List<SceneRange>, layer: Int, sceneId: Int): Boolean =
        scenes.none { layer in it.layers } || scenes.any { it.id == sceneId && layer in it.layers }

    /**
     * Новая сцена «с чистым холстом»: общие верхние слои [topLevel] становятся своими для всех прежних сцен,
     * в новой сцене [newId] их нет. Без прежних сцен - нечего делать.
     */
    fun cleanCanvas(scenes: List<SceneRange>, topLevel: Collection<Int>, newId: Int): List<SceneRange> {
        val owned = owned(scenes)
        val move = topLevel.filter { it !in owned }
        if (move.isEmpty()) return scenes
        return scenes.map { if (it.id == newId) it else it.copy(layers = it.layers + move) }
    }

    fun setBackground(scenes: List<SceneRange>, sceneId: Int, argb: Int?): List<SceneRange>? {
        val s = scenes.firstOrNull { it.id == sceneId } ?: return null
        if (s.background == argb) return null
        return scenes.map { if (it.id == sceneId) it.copy(background = argb) else it }
    }

    fun setTransition(scenes: List<SceneRange>, sceneId: Int, type: SceneTransition, frames: Int): List<SceneRange>? {
        if (scenes.none { it.id == sceneId }) return null
        val f = if (type == SceneTransition.CUT) 0 else frames.coerceIn(1, MAX_TRANSITION)
        return scenes.map { if (it.id == sceneId) it.copy(transition = type.key, transitionFrames = f) else it }
    }

    /** Свои слои, которых больше нет в проекте, убираются из сцен. */
    fun prune(scenes: List<SceneRange>, existing: Set<Int>): List<SceneRange> =
        scenes.map { s -> if (s.layers.all { it in existing }) s else s.copy(layers = s.layers.filterTo(HashSet()) { it in existing }) }
}
