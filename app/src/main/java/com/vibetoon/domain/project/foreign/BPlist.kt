package com.vibetoon.domain.project.foreign

/**
 * Пакет 28: читатель двоичных plist (bplist00) и архивов NSKeyedArchiver - нужен для Document.archive файлов Procreate.
 * Чистый Kotlin без Android. Значения: Boolean, Long, Double, String, ByteArray, List, Map (ключи-строки), [Uid], null.
 * Ошибки структуры - [IllegalArgumentException] (вызывающий превращает их в «файл повреждён»).
 */
object BPlist {
    /** Ссылка NSKeyedArchiver на объект в $objects. */
    data class Uid(val index: Int)

    fun parse(b: ByteArray): Any? {
        require(b.size >= 40 && String(b, 0, 6, Charsets.US_ASCII) == "bplist") { "not a binary plist" }
        val t = b.size - 32
        val offSize = b[t + 6].toInt() and 0xFF
        val refSize = b[t + 7].toInt() and 0xFF
        val num = be(b, t + 8, 8)
        val top = be(b, t + 16, 8)
        val tableOff = be(b, t + 24, 8)
        require(offSize in 1..8 && refSize in 1..8 && num in 1..10_000_000 && top in 0 until num) { "bad trailer" }
        require(tableOff >= 8 && tableOff + num * offSize <= t) { "bad offset table" }
        val offsets = LongArray(num.toInt()) { be(b, (tableOff + it * offSize).toInt(), offSize) }
        val cache = HashMap<Int, Any?>()
        val done = HashSet<Int>()

        fun lenAt(o: Int, info: Int): Pair<Int, Int> {
            if (info != 15) return info to o + 1
            val m = b[o + 1].toInt() and 0xFF
            require(m shr 4 == 1) { "bad length marker" }
            val n = 1 shl (m and 15)
            val len = be(b, o + 2, n)
            require(len in 0..Int.MAX_VALUE.toLong()) { "bad length" }
            return len.toInt() to o + 2 + n
        }

        fun obj(i: Int, depth: Int): Any? {
            require(depth < 512) { "plist too deep" }
            if (i in done) return cache[i]
            require(i in offsets.indices) { "bad ref" }
            val o = offsets[i].toInt()
            require(o in 8 until t) { "bad object offset" }
            val m = b[o].toInt() and 0xFF
            val type = m shr 4
            val info = m and 15
            fun ref(at: Int) = be(b, at, refSize).toInt()
            val v: Any? = when (type) {
                0x0 -> when (info) { 8 -> false; 9 -> true; else -> null }
                0x1 -> { val n = 1 shl info; require(n <= 16); if (n == 16) be(b, o + 9, 8) else if (n == 8) be(b, o + 1, 8) else be(b, o + 1, n) }
                0x2 -> if (info == 2) java.lang.Float.intBitsToFloat(be(b, o + 1, 4).toInt()).toDouble() else java.lang.Double.longBitsToDouble(be(b, o + 1, 8))
                0x3 -> java.lang.Double.longBitsToDouble(be(b, o + 1, 8))
                0x4 -> { val (n, s) = lenAt(o, info); require(s + n <= t); b.copyOfRange(s, s + n) }
                0x5 -> { val (n, s) = lenAt(o, info); require(s + n <= t); String(b, s, n, Charsets.UTF_8) }
                0x6 -> { val (n, s) = lenAt(o, info); require(s + n * 2 <= t); String(b, s, n * 2, Charsets.UTF_16BE) }
                0x8 -> Uid(be(b, o + 1, info + 1).toInt())
                0xA, 0xC -> {
                    val (n, s) = lenAt(o, info)
                    require(s + n.toLong() * refSize <= t)
                    val list = ArrayList<Any?>(n)
                    cache[i] = list; done += i
                    for (k in 0 until n) list += obj(ref(s + k * refSize), depth + 1)
                    list
                }
                0xD -> {
                    val (n, s) = lenAt(o, info)
                    require(s + 2L * n * refSize <= t)
                    val map = LinkedHashMap<String, Any?>()
                    cache[i] = map; done += i
                    for (k in 0 until n) map[obj(ref(s + k * refSize), depth + 1).toString()] = obj(ref(s + (n + k) * refSize), depth + 1)
                    map
                }
                else -> null
            }
            cache[i] = v; done += i
            return v
        }
        return obj(top.toInt(), 0)
    }

    private fun be(b: ByteArray, o: Int, n: Int): Long {
        require(o >= 0 && o + n <= b.size) { "out of range" }
        var v = 0L
        for (k in 0 until n) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        return v
    }

    /** Доступ к архиву NSKeyedArchiver: каждая ссылка [Uid] разворачивается в объект из $objects. */
    class Keyed(plist: Any?) {
        val objects: List<Any?>
        val root: Any?

        init {
            val p = plist as? Map<*, *> ?: throw IllegalArgumentException("not a keyed archive")
            objects = p["\$objects"] as? List<Any?> ?: throw IllegalArgumentException("no \$objects")
            val top = p["\$top"] as? Map<*, *> ?: throw IllegalArgumentException("no \$top")
            root = r(top["root"])
        }

        /** Объект по ссылке (одна ступень) или само значение. */
        fun r(v: Any?): Any? = if (v is Uid) objects.getOrNull(v.index) else v

        fun get(o: Any?, k: String): Any? {
            val m = r(o) as? Map<*, *> ?: return null
            val v = r(m[k])
            return if (v == "\$null") null else v
        }

        fun cls(o: Any?): String? {
            val m = r(o) as? Map<*, *> ?: return null
            return (r(m["\$class"]) as? Map<*, *>)?.get("\$classname") as? String
        }

        fun str(o: Any?, k: String): String? = when (val v = get(o, k)) {
            is String -> v
            is Map<*, *> -> (r(v["NS.string"]) as? String) ?: (r(v["NS.bytes"]) as? ByteArray)?.toString(Charsets.UTF_8)
            else -> null
        }

        fun num(o: Any?, k: String): Double? = when (val v = get(o, k)) { is Long -> v.toDouble(); is Double -> v; is Boolean -> if (v) 1.0 else 0.0; else -> null }

        fun bool(o: Any?, k: String): Boolean = when (val v = get(o, k)) { is Boolean -> v; is Long -> v != 0L; is Double -> v != 0.0; else -> false }

        fun bytes(o: Any?, k: String): ByteArray? = when (val v = get(o, k)) { is ByteArray -> v; is Map<*, *> -> r(v["NS.data"]) as? ByteArray; else -> null }

        /** Элементы NSArray (развёрнутые). */
        fun arr(o: Any?, k: String): List<Any?> {
            val v = get(o, k) as? Map<*, *> ?: return emptyList()
            return (v["NS.objects"] as? List<*>)?.map { r(it) } ?: emptyList()
        }
    }
}
