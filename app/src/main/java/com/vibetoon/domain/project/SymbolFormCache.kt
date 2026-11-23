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
class SymbolFormCache(private val budgetBytes: Long) {
    data class Key(val def: Int, val local: Int, val crop: Boolean)
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
        map.entries.filter { it.key.def == def && it.key.crop == crop }.maxByOrNull { (if (it.value.gen == gen) 2 else 0) + (if (it.key.local == 0) 1 else 0) }
            ?.let { it.key to it.value }

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

    /** Убирает форму (текстура не загрузилась). Возвращает id текстуры или null. */
    fun remove(key: Key): Int? = map.remove(key)?.let { bytes -= it.bytes; it.texId }

    /** Все id текстур (для удаления при закрытии проекта); кэш пустеет. */
    fun clear(): List<Int> { val ids = map.values.map { it.texId }; map.clear(); bytes = 0; gen++; return ids }
}
