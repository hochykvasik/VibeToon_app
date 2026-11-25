package com.vibetoon.domain.project

/**
 * Кэш готовых форм символа (текстура на GPU на каждую пару «символ, внутренний кадр»). Экземпляр на кадре сцены
 * просто показывает текстуру нужной формы - переключение мгновенное и всегда совпадает с кадром сцены
 * (раньше каждая смена формы снималась заново, асинхронно, и холст отставал от таймлайна или показывал чужую форму).
 *
 * Поколение [gen]: правка содержимого символа делает формы устаревшими, но они остаются запасной картинкой, пока
 * не снимется новая (без мигания пустотой). Бюджет памяти - LRU, формы, которые сейчас на экране, не вытесняются.
 * Только данные и политика: текстуры создаёт и удаляет вызывающий (ViewModel), проверяется JVM-тестами.
 */
class SymbolFormCache(budgetBytes: Long) {
    /** Пакет 29: бюджет меняется вместе с бюджетом текстур рендерера (считается по памяти устройства). */
    var budgetBytes: Long = budgetBytes
        set(v) { field = v.coerceAtLeast(1L) }

    /**
     * [local] - внутренний кадр формы; спринт 47: [to] и [mix] - растворение в форму [to] (доля [mix] из
     * [SymbolForms.MIX_STEPS]); у обычной формы to = -1, mix = 0.
     */
    data class Key(val def: Int, val local: Int, val crop: Boolean, val to: Int = -1, val mix: Int = 0) {
        val blended: Boolean get() = mix > 0 && to >= 0
    }
    /** [rect] - под-прямоугольник квадрата экземпляра (x, y, w, h в долях), где лежит обрезанная форма. */
    class Entry(val texId: Int, val rect: FloatArray, val bytes: Long, val gen: Int)

    private val map = LinkedHashMap<Key, Entry>(16, 0.75f, true)   // порядок доступа = LRU
    var gen = 0
        private set
    var bytes = 0L
        private set

    val size: Int get() = map.size

    /** Свежая форма (текущего поколения) или null. */
    fun fresh(key: Key): Entry? = map[key]?.takeIf { it.gen == gen }

    /** Любая форма по ключу, в том числе устаревшая (запасная картинка). */
    fun any(key: Key): Entry? = map[key]

    /** Какая-нибудь форма символа [def] (для экземпляра, у которого ещё ничего не показано), свежие первыми. */
    fun anyOf(def: Int, crop: Boolean): Pair<Key, Entry>? =
        anyOf(def, crop, emptySet())

    /**
     * Спринт 47: запасная форма символа с предпочтением форм [prefer] (маркеры символа - непустые рисунки). Раньше
     * первым шёл внутренний кадр 0, а у символа «Слои как кадры» с формами на 30, 31, 39 кадр 0 пустой - пока
     * нужная форма снималась, рот пропадал. Свежие формы важнее устаревших, обычные формы - смесей.
     */
    fun anyOf(def: Int, crop: Boolean, prefer: kotlin.collections.Set<Int>): Pair<Key, Entry>? =
        map.entries.filter { it.key.def == def && it.key.crop == crop }.maxByOrNull {
            (if (it.value.gen == gen) 8 else 0) + (if (!it.key.blended) 4 else 0) + (if (it.key.local in prefer) 2 else 0) + (if (it.key.local == 0) 1 else 0)
        }?.let { it.key to it.value }

    /** Содержимое символов изменилось: все формы становятся запасными. */
    fun invalidate() { gen++ }

    /**
     * Кладёт форму. Возвращает id текстур, которые надо удалить: прежняя форма с тем же ключом и вытесненные по
     * бюджету (кроме [pinned] - ключей, которые сейчас на экране, и самой новой формы).
     */
    fun put(key: Key, entry: Entry, pinned: Set<Key>): List<Int> {
        val out = ArrayList<Int>()
        map.remove(key)?.let { bytes -= it.bytes; if (it.texId != entry.texId) out += it.texId }
        map[key] = entry
        bytes += entry.bytes
        if (bytes > budgetBytes) {
            // сначала устаревшие, затем самые давно использованные
            val order = map.entries.filter { it.key != key && it.key !in pinned }.sortedBy { if (it.value.gen == gen) 1 else 0 }
            for (e in order) {
                if (bytes <= budgetBytes) break
                map.remove(e.key); bytes -= e.value.bytes; out += e.value.texId
            }
        }
        return out
    }

    /** Пакет 29: рендерер вытеснил текстуру [texId] - форма с ней больше не готова. Возвращает её ключ или null. */
    fun removeTex(texId: Int): Key? {
        val k = map.entries.firstOrNull { it.value.texId == texId }?.key ?: return null
        map.remove(k)?.let { bytes -= it.bytes }
        return k
    }

    /** id текстур всех форм (кандидаты на вытеснение рендерером). */
    fun texIds(): List<Int> = map.values.map { it.texId }

    /** id текстур форм с ключами из [keys]. */
    fun texIdsOf(keys: Collection<Key>): Set<Int> = keys.mapNotNullTo(HashSet()) { map[it]?.texId }

    /** Убирает форму (текстура не загрузилась). Возвращает id текстуры или null. */
    fun remove(key: Key): Int? = map.remove(key)?.let { bytes -= it.bytes; it.texId }

    /** Все id текстур (для удаления при закрытии проекта); кэш пустеет. */
    fun clear(): List<Int> { val ids = map.values.map { it.texId }; map.clear(); bytes = 0; gen++; return ids }
}

/**
 * Пакет 29: формы символов для экспорта. Экспорт показывает экземпляры символов готовыми текстурами форм на GPU (как
 * холст при Play): каждая форма рисуется один раз, без прежнего круга «снимок -> PNG в памяти -> декодирование PNG и
 * загрузка на каждом кадре экспорта». Здесь - какие формы нужны экспорту (по порядку первого появления).
 */
object ExportSymbolForms {
    /** Готовая текстура формы на GPU и её прямоугольник в квадрате экземпляра (x, y, w, h в долях). */
    class Form(val texId: Int, val rect: FloatArray)

    /**
     * Формы символов одного экспорта: [forms] - готовые текстуры (формы редактора и снятые для экспорта прямо в FBO),
     * [pngs] - запасной путь «картинка в памяти» для форм, которым не хватило памяти GPU (определение -> внутренний
     * кадр -> PNG), [owned] - текстуры, созданные для экспорта (удаляются после него), [pinned] - формы редактора,
     * которые экспорт использует (не вытесняются до конца экспорта).
     */
    class Set(
        val forms: Map<SymbolFormCache.Key, Form>,
        val pngs: Map<Int, List<ByteArray?>>,
        val owned: List<Int>,
        val pinned: kotlin.collections.Set<SymbolFormCache.Key>
    ) {
        val isEmpty: Boolean get() = forms.isEmpty() && pngs.isEmpty()
        fun form(def: Int, local: Int, crop: Boolean): Form? = forms[SymbolFormCache.Key(def, local, crop)]
        fun png(def: Int, local: Int): ByteArray? = pngs[def]?.getOrNull(local)
        companion object { val EMPTY = Set(emptyMap(), emptyMap(), emptyList(), emptySet()) }
    }

    val FULL_RECT: FloatArray get() = floatArrayOf(0f, 0f, 1f, 1f)

    /** Байты текстуры с mip-уровнями (как считает рендерер). */
    fun textureBytes(w: Int, h: Int): Long = w.toLong() * h * 4L * 4L / 3L

    /**
     * Размер снимка формы в текстуру: [w] x [h] (2x холста - как у форм редактора), при нехватке [roomBytes] - вдвое
     * меньше, но не меньше [minLongSide] по длинной стороне (разрешение холста). null - не помещается: запасной путь.
     */
    fun gpuSize(w: Int, h: Int, roomBytes: Long, minLongSide: Int): Pair<Int, Int>? {
        var cw = w.coerceAtLeast(1); var ch = h.coerceAtLeast(1)
        val floor = minOf(minLongSide, maxOf(cw, ch)).coerceAtLeast(1)
        while (textureBytes(cw, ch) > roomBytes) {
            if (maxOf(cw, ch) / 2 < floor) return null
            cw = maxOf(1, cw / 2); ch = maxOf(1, ch / 2)
        }
        return cw to ch
    }

    /**
     * Форма редактора годится для экспорта, только если её не уменьшали при подготовке (иначе в экспорте была бы
     * мягче, чем прежний полный снимок): исходный размер вырезанной части [rect] от снимка [dimW] x [dimH] не больше [maxBytes].
     */
    fun editorFormFullRes(rect: FloatArray, dimW: Int, dimH: Int, maxBytes: Long): Boolean {
        if (rect.size < 4) return false
        val px = (rect[2] * dimW).toDouble() * (rect[3] * dimH).toDouble() * 4.0
        return px <= maxBytes
    }

    /** Таблица «экземпляр -> текстура формы» для сцены кадра экспорта (основная и запасная - одна и та же форма). */
    fun aliases(entries: List<Pair<Int, Form>>): com.vibetoon.domain.render.TextureAliases {
        if (entries.isEmpty()) return com.vibetoon.domain.render.TextureAliases.EMPTY
        val n = entries.size
        val ids = IntArray(n); val prim = IntArray(n); val rects = FloatArray(n * 8)
        for ((i, e) in entries.withIndex()) {
            ids[i] = e.first; prim[i] = e.second.texId
            e.second.rect.copyInto(rects, i * 8, 0, 4); e.second.rect.copyInto(rects, i * 8 + 4, 0, 4)
        }
        return com.vibetoon.domain.render.TextureAliases(ids, prim, prim.copyOf(), rects)
    }

    /** Ключи форм на кадрах [frames] (кадры проекта); [keysAt] - ключи всех экземпляров на кадре. Не больше [limit]. */
    fun neededKeys(frames: Iterable<Int>, keysAt: (Int) -> List<SymbolFormCache.Key>, limit: Int = 512): List<SymbolFormCache.Key> {
        val seen = LinkedHashSet<SymbolFormCache.Key>()
        var lastFrame = Int.MIN_VALUE
        for (f in frames) {
            if (f == lastFrame) continue   // повтор кадра проекта (экспорт с fps выше проекта)
            lastFrame = f
            for (k in keysAt(f)) { if (seen.size >= limit && k !in seen) return seen.toList(); seen += k }
        }
        return seen.toList()
    }
}
