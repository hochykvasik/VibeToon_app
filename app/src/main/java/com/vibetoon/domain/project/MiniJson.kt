package com.vibetoon.domain.project

class JsonException(message: String) : Exception(message)

/**
 * Минимальный JSON на чистом Kotlin: без Android и без внешних библиотек, поэтому одинаково
 * работает в приложении и в JVM-тестах. Объекты читаются как Map, массивы как List,
 * числа как Double, остальное: String, Boolean, null.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Лишние данные в позиции ${p.pos}")
        return v
    }

    fun stringify(v: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        write(sb, v, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                sb.append(if (d.isNaN() || d.isInfinite()) "0" else d.toString())
            }
            is String -> quote(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent + 1)
                    quote(sb, k.toString())
                    sb.append(if (indent >= 0) ": " else ":")
                    write(sb, value, if (indent >= 0) indent + 1 else -1)
                }
                newline(sb, indent)
                sb.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent + 1)
                    write(sb, item, if (indent >= 0) indent + 1 else -1)
                }
                newline(sb, indent)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        if (indent < 0) return
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun fail(msg: String): Nothing = throw JsonException("$msg (позиция $pos)")

        fun value(): Any? {
            if (atEnd()) fail("Неожиданный конец")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Неожиданный символ '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Ожидалось $word")
            pos += word.length
            return v
        }

        private fun num(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("Неверное число")
        }

        private fun str(): String {
            pos++ // открывающая кавычка
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Строка не закрыта")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("Обрыв экранирования")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Обрыв последовательности юникода")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("Неверная последовательность юникода"))
                                pos += 4
                            }
                            else -> fail("Неверное экранирование: $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return out }
            while (true) {
                skipWs()
                out += value()
                skipWs()
                if (atEnd()) fail("Массив не закрыт")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("Ожидалась запятая или ]")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("Ожидался ключ")
                val key = str()
                skipWs()
                if (atEnd() || s[pos++] != ':') fail("Ожидалось ':'")
                skipWs()
                out[key] = value()
                skipWs()
                if (atEnd()) fail("Объект не закрыт")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("Ожидалась запятая или }")
                }
            }
        }
    }
}
