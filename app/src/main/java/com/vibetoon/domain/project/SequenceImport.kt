package com.vibetoon.domain.project

/**
 * Импорт секвенции картинок (пакет 11): несколько выбранных файлов становятся одним покадровым слоем
 * (рисунок на кадр) или символом с этим слоем внутри. Порядок кадров - «естественная» сортировка имён:
 * frame2.png идёт раньше frame10.png. Чистые функции - проверяются unit-тестом.
 */
object SequenceImport {
    /** Больше кадров за раз не импортируем (память и время копирования). */
    const val MAX_FRAMES = 300

    private val CHUNK = Regex("""\d+|\D+""")

    /** Сравнение имён: числа сравниваются как числа, остальное - без учёта регистра. */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val pa = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
        val pb = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny).takeIf { it != 0 } ?: (x.length - y.length)
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        pa.size - pb.size
    }

    /** Индексы [names] в порядке кадров. */
    fun order(names: List<String>): List<Int> = names.indices.sortedWith { i, j -> naturalOrder.compare(names[i], names[j]) }

    /** Рисунки покадрового слоя: каждый файл держится [hold] кадров. */
    fun cells(files: List<String>, hold: Int = 1): List<FrameCell> {
        val h = hold.coerceAtLeast(1)
        return files.mapIndexed { i, f -> FrameCell(i * h, f) }
    }

    /** Название слоя по первому файлу: без расширения и хвостового номера («walk_001.png» -> «walk»). */
    fun title(firstName: String): String {
        val base = firstName.substringBeforeLast('.').ifBlank { firstName }
        val t = base.replace(Regex("""[\s._-]*\d+$"""), "").trim()
        return t.ifBlank { base }
    }
}
