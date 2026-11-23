package com.vibetoon.domain.anim

/**
 * Сцена проекта (пакет 9): именованная часть общего таймлайна [start, end) в кадрах. Сцены не пересекаются и идут
 * по порядку; содержимое (слои, ключи) общее - сцена задаёт, какой отрезок проигрывать, показывать и экспортировать.
 */
data class SceneRange(val id: Int, val name: String, val start: Int, val end: Int) {
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
            out += s.copy(start = start, end = end, name = cleanName(s.name).ifEmpty { "Сцена ${out.size + 1}" })
            prevEnd = end
            if (out.size >= MAX_SCENES) break
        }
        return out
    }

    fun cleanName(name: String): String = name.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_NAME).trim()

    /** Сцена, в которую попадает кадр, или null (кадр вне сцен). */
    fun at(scenes: List<SceneRange>, frame: Int): SceneRange? = scenes.firstOrNull { frame in it }

    fun nextId(scenes: List<SceneRange>): Int = (scenes.maxOfOrNull { it.id } ?: 0) + 1

    /** Имя «Сцена N», не занятое другими сценами. */
    fun defaultName(scenes: List<SceneRange>): String {
        var n = scenes.size + 1
        while (scenes.any { it.name == "Сцена $n" }) n++
        return "Сцена $n"
    }

    /**
     * «+ Новая сцена»: если сцен нет, весь текущий таймлайн становится «Сцена 1»; новая сцена длиной [length]
     * добавляется в конец, при необходимости проект удлиняется. Результат: сцены и новая длительность проекта.
     */
    data class Added(val scenes: List<SceneRange>, val duration: Int, val sceneId: Int)

    fun add(scenes: List<SceneRange>, duration: Int, length: Int): Added? {
        val cur = normalize(scenes, duration)
        if (cur.size >= MAX_SCENES || length <= 0) return null
        val base = if (cur.isEmpty()) listOf(SceneRange(1, "Сцена 1", 0, duration)) else cur
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
        val cur = normalize(scenes, duration).ifEmpty { listOf(SceneRange(1, "Сцена 1", 0, duration)) }
        if (cur.size >= MAX_SCENES) return null
        val s = at(cur, frame) ?: return null
        if (frame <= s.start || frame >= s.end) return null
        val second = SceneRange(nextId(cur), defaultName(cur), frame, s.end)
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
        if (i > 0) out[i - 1] = out[i - 1].copy(end = maxOf(out[i - 1].end, s.end))
        else out[0] = out[0].copy(start = minOf(out[0].start, s.start))
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
