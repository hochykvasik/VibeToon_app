package com.vibetoon.domain.render

/**
 * Пакет 29: память GPU под текстуры слоёв.
 *
 * Раньше бюджет был жёстким - 192 МБ на любом устройстве (слой 4K с mip-уровнями занимает ~44 МБ, т. е. влезало около
 * четырёх таких слоёв), а при переполнении новая текстура молча не создавалась. Теперь бюджет считается по памяти
 * устройства, давно не показанные текстуры вытесняются (и восстанавливаются, когда снова нужны), а если места нет
 * совсем, пользователь видит предупреждение. Здесь - чистая логика (без Android и GL), её проверяют JVM-тесты.
 */
object TextureBudget {
    const val MIB = 1024L * 1024L
    /** Прежний жёсткий бюджет: до расчёта и при неизвестной памяти. */
    const val DEFAULT = 192L * MIB
    const val MIN = 128L * MIB
    const val MAX = 1024L * MIB

    /**
     * Бюджет текстур: ~12 % всей памяти устройства, но не больше половины свободной на момент запуска; на устройствах
     * с малым объёмом памяти (isLowRamDevice) - не больше 160 МБ; при слабом GPU (GL_MAX_TEXTURE_SIZE < 4096) - не
     * больше 256 МБ. Итог в пределах [MIN, MAX]. Примеры: 4 ГБ -> ~490 МБ, 8 ГБ -> ~980 МБ, 3 ГБ -> ~370 МБ.
     */
    fun compute(totalMem: Long, availMem: Long, lowRam: Boolean, maxTextureSize: Int): Long {
        if (totalMem <= 0L) return DEFAULT
        var b = totalMem / 100L * 12L
        if (availMem > 0L) b = minOf(b, availMem / 2L)
        if (lowRam) b = minOf(b, 160L * MIB)
        if (maxTextureSize in 1..4095) b = minOf(b, 256L * MIB)
        return b.coerceIn(MIN, MAX)
    }

    /** Доля бюджета под готовые формы символов (раньше 72 из 192 МБ - та же доля 3/8). */
    fun formBudget(textureBudget: Long): Long = (textureBudget / 8L * 3L).coerceIn(48L * MIB, 384L * MIB)

    fun mb(bytes: Long): Long = (bytes + MIB / 2) / MIB
}

/** События памяти текстур от рендерера (RenderNative.takeTextureEvents): пары [вид, id]. */
object TextureEvents {
    const val EVICTED = 1
    const val REJECTED_BUDGET = 2
    const val REJECTED_SIZE = 3

    data class Event(val kind: Int, val id: Int)

    fun decode(raw: IntArray?): List<Event> {
        if (raw == null || raw.size < 2) return emptyList()
        val out = ArrayList<Event>(raw.size / 2)
        var i = 0
        while (i + 1 < raw.size) {
            val k = raw[i]
            if (k == EVICTED || k == REJECTED_BUDGET || k == REJECTED_SIZE) out += Event(k, raw[i + 1])
            i += 2
        }
        return out
    }
}

/**
 * Какие текстуры рендереру разрешено вытеснять: только те, что ViewModel точно умеет восстановить без потерь.
 * - формы символов (снимаются заново), кадры видео, текст (рисуется из свойств);
 * - картинки и покадровые слои, которые в этой сессии НИ РАЗУ не правились кистью/Liquify/выделением ([everEdited]):
 *   их содержимое совпадает с файлом проекта. Правленые слои (содержимое может быть только в GPU, ещё не сохранено)
 *   не вытесняются никогда - это защита от потери рисунка;
 * - остальное (группы, слои PSD, векторные слои, камера) - нет.
 */
object TextureEviction {
    enum class Source { VIDEO, TEXT, IMAGE_FILE, CELLS, OTHER }

    fun evictable(items: List<Pair<Int, Source>>, everEdited: Set<Int>, formTextures: Collection<Int>, pinnedForms: Set<Int> = emptySet()): IntArray {
        val out = ArrayList<Int>(items.size + formTextures.size)
        for ((id, src) in items) {
            val ok = when (src) {
                Source.VIDEO, Source.TEXT -> true
                Source.IMAGE_FILE, Source.CELLS -> id !in everEdited
                Source.OTHER -> false
            }
            if (ok) out += id
        }
        for (t in formTextures) if (t !in pinnedForms) out += t
        out.sort()
        return out.toIntArray()
    }
}

/**
 * Учёт текстур, которых сейчас нет в GPU (вытеснены или отклонены), и предупреждений.
 * - вытесненная: восстанавливается, как только снова появится в сцене ([toReload]);
 * - отклонённая по бюджету: повтор не раньше чем через [retryMs] (иначе каждый кадр Play декодировал бы её зря);
 * - отклонённая по размеру (больше предела GPU): автоматически не повторяется - тот же файл не поместится;
 * - предупреждение пользователю - не чаще раза в [warnEveryMs].
 * Только главный поток.
 */
class TextureResidency(private val retryMs: Long = 5_000L, private val warnEveryMs: Long = 20_000L) {
    data class Warning(val layerId: Int, val tooLarge: Boolean)
    data class Outcome(val evicted: List<Int>, val rejected: List<Int>, val warning: Warning?)

    private val missing = HashMap<Int, Long>()   // id -> не раньше какого времени восстанавливать
    private var lastWarnAt = Long.MIN_VALUE
    /** Отклонения за время экспорта (для строки в итоге). */
    var rejectedSinceMark = 0
        private set

    val nonResident: Set<Int> get() = missing.keys

    fun onEvents(events: List<TextureEvents.Event>, now: Long): Outcome {
        if (events.isEmpty()) return Outcome(emptyList(), emptyList(), null)
        val ev = ArrayList<Int>(); val rej = ArrayList<Int>()
        var warn: Warning? = null
        for (e in events) when (e.kind) {
            TextureEvents.EVICTED -> { missing[e.id] = 0L; ev += e.id }
            TextureEvents.REJECTED_BUDGET, TextureEvents.REJECTED_SIZE -> {
                val big = e.kind == TextureEvents.REJECTED_SIZE
                missing[e.id] = if (big) Long.MAX_VALUE else now + retryMs
                rej += e.id
                rejectedSinceMark++
                if (warn == null && (lastWarnAt == Long.MIN_VALUE || now - lastWarnAt >= warnEveryMs)) warn = Warning(e.id, big)
            }
        }
        if (warn != null) lastWarnAt = now
        return Outcome(ev, rej, warn)
    }

    /** Текстуры сцены, которые пора восстановить; они перестают считаться отсутствующими (новый отказ вернёт их). */
    fun toReload(sceneIds: IntArray, now: Long): List<Int> {
        if (missing.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        for (id in sceneIds) {
            val at = missing[id] ?: continue
            if (at <= now) { out += id; missing.remove(id) }
        }
        return out
    }

    /** Текстуру загрузили заново другим путём (правка, замена файла): больше не отсутствует. */
    fun forget(id: Int) { missing.remove(id) }

    /** Начало экспорта: считать отклонения заново. */
    fun mark() { rejectedSinceMark = 0 }

    fun clear() { missing.clear(); rejectedSinceMark = 0; lastWarnAt = Long.MIN_VALUE }
}
