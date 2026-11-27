package com.vibetoon.domain.anim

/**
 * Спринт 49: свои кривые плавности (пружина, отскок, свой разгон…), сохранённые пользователем. Хранятся в настройках
 * приложения (строка `user_curves` в `vt_app`), поэтому общие для всех проектов, попадают в «Резервную копию
 * вручную» и переживают обновление APK. Формат строки: по записи на строку, `имя<TAB>числа кривой через запятую`
 * (формат [Curve.toList]); неверные записи при чтении пропускаются.
 */
object UserCurves {
    const val PREF = "user_curves"
    const val MAX = 60
    const val NAME_MAX = 40
    /** Имя по умолчанию для новой кривой (русский ключ словаря; номер дописывается). */
    const val DEFAULT_NAME = "Моя кривая"

    class Saved(val name: String, val data: List<Float>) {
        val curve: Curve? get() = Curve.parse(data)
        override fun equals(other: Any?) = other is Saved && other.name == name && other.data == data
        override fun hashCode() = name.hashCode() * 31 + data.hashCode()
    }

    /** Имя без переводов строк и табуляций, обрезанное; пустое - null. */
    fun sanitize(name: String): String? {
        val s = name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().replace(Regex(" {2,}"), " ")
        return s.take(NAME_MAX).trim().ifEmpty { null }
    }

    fun encode(list: List<Saved>): String = list.joinToString("\n") { s ->
        (sanitize(s.name) ?: DEFAULT_NAME) + "\t" + s.data.joinToString(",") { it.toString() }
    }

    fun decode(text: String?): List<Saved> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<Saved>()
        val seen = HashSet<String>()
        for (line in text.split('\n')) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val name = sanitize(line.substring(0, tab)) ?: continue
            val nums = line.substring(tab + 1).split(',').map { it.trim().toFloatOrNull() ?: Float.NaN }
            val c = Curve.parse(nums) ?: continue
            if (!seen.add(name.lowercase())) continue
            out += Saved(name, c.toList())
            if (out.size >= MAX) break
        }
        return out
    }

    /**
     * Сохраняет кривую [curve] под именем [name]: такое же имя (без учёта регистра) перезаписывается на том же месте,
     * новое - в конец списка (не больше [MAX]: при переполнении самая старая запись уходит).
     */
    fun save(list: List<Saved>, name: String, curve: Curve): List<Saved> {
        val n = sanitize(name) ?: uniqueName(list)
        val item = Saved(n, curve.toList())
        val i = list.indexOfFirst { it.name.equals(n, ignoreCase = true) }
        if (i >= 0) return list.toMutableList().also { it[i] = item }
        val grown = list + item
        return if (grown.size > MAX) grown.drop(grown.size - MAX) else grown
    }

    fun delete(list: List<Saved>, name: String): List<Saved> = list.filterNot { it.name.equals(name, ignoreCase = true) }

    fun exists(list: List<Saved>, name: String): Boolean {
        val n = sanitize(name) ?: return false
        return list.any { it.name.equals(n, ignoreCase = true) }
    }

    /** Свободное имя «Моя кривая N» ([base] - переведённое имя по умолчанию). */
    fun uniqueName(list: List<Saved>, base: String = DEFAULT_NAME): String {
        var k = list.size + 1
        while (list.any { it.name.equals("$base $k", ignoreCase = true) }) k++
        return "$base $k"
    }
}

/**
 * Спринт 49: буфер обмена кривой - «Копировать» в редакторе кривой или на таймлайне, «Вставить» в другой ключ или набор
 * ключей (на любых слоях, костях и ключах времени символов). Хранит числа кривой ([Curve.toList]); живёт весь сеанс,
 * между проектами тоже.
 */
object CurveClipboard {
    /** Число ключей, получивших вставленную кривую: ключи слоёв/костей и ключи времени символов. */
    fun pastedCount(layerKeys: Int, timeKeys: Int): Int = layerKeys.coerceAtLeast(0) + timeKeys.coerceAtLeast(0)

    /** Скопированная кривая, пригодная для вставки (проверена тем же разбором, что и при чтении проекта). */
    fun valid(data: List<Float>?): Curve? = Curve.parse(data)
}
