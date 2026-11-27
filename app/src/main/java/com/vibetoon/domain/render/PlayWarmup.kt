package com.vibetoon.domain.render

/**
 * Спринт 54: Play на тяжёлых проектах. Первые секунды тормозили: часы Play шли сразу, а в это же время грузились
 * текстуры, снимались формы символов и впервые собирались сцены - кадры пропускались, потом были микрофризы от снимков
 * форм посреди проигрывания. Теперь перед стартом часов короткий разогрев (не дольше [MAX_MS]): дождаться загрузки
 * текстур и форм первого кадра и заранее собрать сцены первых кадров. Звук и часы стартуют вместе, после разогрева.
 * Чистая логика - unit-тест.
 */
object PlayWarmup {
    const val MAX_MS = 700L
    /** Показывать «Подготовка Play…», если разогрев дольше. */
    const val NOTICE_MS = 200L
    /** Пауза между снимками форм во время Play: GL-поток успевает нарисовать кадр между ними (без микрофризов). */
    const val FORM_GAP_MS = 24L

    /** Сколько первых кадров собрать заранее: полсекунды, но не больше 15 и не дальше конца диапазона. */
    fun prebuildCount(fps: Int, startFrame: Int, lastFrame: Int): Int =
        minOf((fps.coerceAtLeast(1) + 1) / 2, 15, (lastFrame - startFrame + 1).coerceAtLeast(0))

    /** Кадры, которые собираются заранее (по циклу внутри [first]..[last]). */
    fun prebuildFrames(fps: Int, startFrame: Int, first: Int, last: Int): List<Int> {
        if (last < first) return emptyList()
        val span = last - first + 1
        val n = minOf((fps.coerceAtLeast(1) + 1) / 2, 15, span)
        return (0 until n).map { first + ((startFrame - first + it) % span + span) % span }
    }

    /** Можно запускать часы: текстуры загружены и формы готовы, либо разогрев исчерпал время. */
    fun ready(elapsedMs: Long, pendingUploadBytes: Long, formsPending: Boolean, scenesLeft: Int): Boolean =
        elapsedMs >= MAX_MS || (pendingUploadBytes <= 0L && !formsPending && scenesLeft <= 0)
}

/**
 * Спринт 54: сцены, собранные за сеанс Play (кадр -> сцена). Во время проигрывания проект не меняется, поэтому
 * повтор кадра (цикл, разогрев) не пересобирает сцену с костями, сетками и эффектами. LRU с бюджетом памяти;
 * очищается на старте и паузе. Обобщён по значению ради unit-теста.
 */
class PlaySceneCache<T : Any>(private val budgetBytes: Long, private val sizeOf: (T) -> Long) {
    private val map = LinkedHashMap<Int, T>(64, 0.75f, true)
    var bytes = 0L
        private set
    val size: Int get() = map.size

    fun get(frame: Int): T? = map[frame]

    fun put(frame: Int, value: T) {
        val s = sizeOf(value).coerceAtLeast(1L)
        if (s > budgetBytes) return
        map.remove(frame)?.let { bytes -= sizeOf(it).coerceAtLeast(1L) }
        map[frame] = value
        bytes += s
        val it = map.entries.iterator()
        while (bytes > budgetBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == frame) continue
            bytes -= sizeOf(e.value).coerceAtLeast(1L)
            it.remove()
        }
    }

    fun clear() { map.clear(); bytes = 0L }

    companion object {
        const val DEFAULT_BUDGET = 48L * 1024 * 1024

        /** Оценка памяти сцены по её массивам (int/float - 4 байта) и картам рамок. */
        fun sceneBytes(s: SceneData): Long =
            256L + 4L * (s.ops.size + s.params.size + s.textureIds.size + s.warpIds.size + s.warpData.size + s.meshIds.size +
                s.meshCounts.size + s.meshVerts.size + s.meshTris.size + s.perspIds.size + s.perspData.size + s.turbIds.size +
                s.turbData.size + s.fxIds.size + s.fxCounts.size + s.fxData.size) +
                48L * (s.selectionQuads.size + s.parentWorlds.size + s.boneDeltas.size + s.groupWorlds.size + s.groupLocalBounds.size)
    }
}
