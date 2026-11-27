package com.vibetoon.domain.vector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Спринт 36: импорт SVG в векторные слои VibeToon (без Android: чистый Kotlin, проверяется юнит-тестами).
 *
 * Переносится: path (все команды M L H V C S Q T A Z, относительные и абсолютные), rect (со скруглением), circle,
 * ellipse, line, polyline, polygon; группы g/a/switch и вложенные svg; use со ссылкой на элемент или symbol;
 * transform (matrix, translate, scale, rotate, skewX, skewY) на любом уровне; viewBox и preserveAspectRatio;
 * заливка и обводка (цвет #rgb/#rrggbb/#rrggbbaa, rgb()/rgba()/hsl(), имена CSS, currentColor), fill-opacity,
 * stroke-opacity, opacity (группы - множителем), stroke-width (с масштабом трансформации), fill-rule (дыры букв и
 * колец - вычитанием, как булевы операции); стили из атрибутов, style="..." и простых правил &lt;style&gt;
 * (тег, .класс, #id). Кривые Безье остаются кривыми: узлы и ручки сразу можно править «Пером».
 * Верхние группы становятся отдельными слоями (имя - inkscape:label или id).
 *
 * Не переносится (с понятной заметкой в итоге): текст, встроенные картинки, градиенты (средний цвет), узоры,
 * обтравка/маски, фильтры, пунктир, маркеры, SMIL-анимация, сложные CSS-селекторы, сущности DOCTYPE.
 */
object SvgImport {
    data class Layer(val name: String, val shapes: List<VectorShape>)

    /** [width] x [height] - размер рисунка SVG в px (viewport), фигуры - в этих координатах. */
    data class Result(val width: Float, val height: Float, val layers: List<Layer>, val notes: List<String>) {
        val shapeCount: Int get() = layers.sumOf { it.shapes.size }
    }

    class SvgException(message: String) : IllegalArgumentException(message)

    const val MSG_NOT_SVG = "Файл не похож на SVG: нет элемента <svg>"
    const val MSG_DAMAGED = "SVG повреждён: ошибка в разметке XML"
    const val MSG_EMPTY = "В SVG нет фигур, которые можно перенести"
    const val MSG_TOO_BIG = "SVG слишком большой для импорта"

    const val N_TEXT = "Текст SVG не переносится: переведите текст в кривые в редакторе SVG"
    const val N_IMAGE = "Встроенные картинки SVG не переносятся"
    const val N_GRADIENT = "Градиенты SVG заменены сплошным цветом"
    const val N_PATTERN = "Узоры (pattern) SVG заменены серым цветом"
    const val N_CLIP = "Обтравочные контуры и маски SVG не применяются"
    const val N_FILTER = "Фильтры SVG (размытие, тени) не переносятся"
    const val N_ANIM = "Анимация SVG (SMIL) не переносится"
    const val N_DASH = "Пунктир обводки не переносится"
    const val N_MARKER = "Маркеры линий (стрелки на концах) не переносятся"
    const val N_CAP = "Концы и углы обводки всегда скруглены"
    const val N_CSS = "Сложные CSS-селекторы SVG не поддерживаются"
    const val N_ENTITY = "Сущности DOCTYPE в SVG не поддерживаются"
    const val N_LIMIT = "SVG очень сложный: перенесены первые {} фигур"
    const val N_LAYERS = "Слишком много групп верхнего уровня: все фигуры в одном слое"

    const val MAX_SHAPES = 5000
    const val MAX_NODES = 400_000
    const val MAX_LAYERS = 32
    private const val MAX_ELEMENTS = 200_000
    private const val MAX_DEPTH = 256
    private const val KAPPA = 0.5522847498

    /** SVG по началу текста (BOM, пробелы, пролог XML, комментарии, DOCTYPE - затем &lt;svg). */
    fun looksLike(text: String): Boolean {
        val head = text.take(8192).trimStart('\uFEFF').trimStart()
        if (!head.startsWith("<")) return false
        return Regex("<(?:[A-Za-z_][\\w.-]*:)?svg[\\s>/]").containsMatchIn(head)
    }

    // ------------------------------------------------------------------ XML

    internal class XEl(val name: String, val attrs: Map<String, String>) {
        val kids = ArrayList<XEl>()
        val text = StringBuilder()
    }

    private fun local(name: String) = name.substringAfterLast(':')

    private fun decode(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val e = s.indexOf(';', i)
                if (e > i && e - i <= 10) {
                    val ent = s.substring(i + 1, e)
                    val r: String? = when {
                        ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "amp" -> "&"; ent == "quot" -> "\""; ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.drop(2).toIntOrNull(16)?.let { cp -> if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null }
                        ent.startsWith("#") -> ent.drop(1).toIntOrNull()?.let { cp -> if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null }
                        else -> null
                    }
                    if (r != null) { sb.append(r); i = e + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** Небольшой разборщик XML без внешних сущностей (безопасен к XXE): элементы, атрибуты, текст, CDATA. */
    internal fun parseXml(s: String, notes: MutableSet<String>): XEl {
        var i = 0
        val n = s.length
        val stack = ArrayList<XEl>()
        var root: XEl? = null
        var count = 0
        fun bad(): Nothing = throw SvgException(MSG_DAMAGED)
        while (i < n) {
            if (s[i] != '<') {
                val j = s.indexOf('<', i).let { if (it < 0) n else it }
                stack.lastOrNull()?.text?.append(decode(s.substring(i, j)))
                i = j
                continue
            }
            when {
                s.startsWith("<!--", i) -> { val j = s.indexOf("-->", i + 4); if (j < 0) bad(); i = j + 3 }
                s.startsWith("<![CDATA[", i) -> { val j = s.indexOf("]]>", i + 9); if (j < 0) bad(); stack.lastOrNull()?.text?.append(s, i + 9, j); i = j + 3 }
                s.startsWith("<?", i) -> { val j = s.indexOf("?>", i + 2); if (j < 0) bad(); i = j + 2 }
                s.startsWith("<!", i) -> {
                    var j = i + 2; var depth = 0
                    while (j < n) { val ch = s[j]; if (ch == '[') depth++ else if (ch == ']') depth-- else if (ch == '>' && depth <= 0) break; j++ }
                    if (j >= n) bad()
                    if (s.substring(i, j).contains("<!ENTITY")) notes += N_ENTITY
                    i = j + 1
                }
                s.startsWith("</", i) -> {
                    val j = s.indexOf('>', i)
                    if (j < 0) bad()
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    i = j + 1
                }
                else -> {
                    var j = i + 1
                    while (j < n && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
                    val name = local(s.substring(i + 1, j))
                    if (name.isEmpty()) bad()
                    val attrs = LinkedHashMap<String, String>()
                    var self = false
                    while (true) {
                        while (j < n && s[j].isWhitespace()) j++
                        if (j >= n) bad()
                        if (s[j] == '>') { j++; break }
                        if (s[j] == '/') { if (j + 1 < n && s[j + 1] == '>') { self = true; j += 2; break } else bad() }
                        val a0 = j
                        while (j < n && s[j] != '=' && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
                        val an = s.substring(a0, j)
                        if (an.isEmpty()) bad()
                        while (j < n && s[j].isWhitespace()) j++
                        if (j < n && s[j] == '=') {
                            j++
                            while (j < n && s[j].isWhitespace()) j++
                            if (j >= n) bad()
                            val q = s[j]
                            if (q == '"' || q == '\'') {
                                val e = s.indexOf(q, j + 1); if (e < 0) bad()
                                attrs[an] = decode(s.substring(j + 1, e)); j = e + 1
                            } else {
                                val v0 = j
                                while (j < n && !s[j].isWhitespace() && s[j] != '>') j++
                                attrs[an] = decode(s.substring(v0, j))
                            }
                        } else attrs[an] = ""
                    }
                    if (++count > MAX_ELEMENTS) throw SvgException(MSG_TOO_BIG)
                    val el = XEl(name, attrs)
                    if (stack.isEmpty()) { if (root == null) root = el } else stack.last().kids += el
                    if (!self && (stack.isNotEmpty() || root === el)) { if (stack.size >= MAX_DEPTH) bad(); stack += el }
                    i = j
                }
            }
        }
        return root ?: throw SvgException(MSG_NOT_SVG)
    }

    // ------------------------------------------------------------------ числа, длины, матрицы

    private data class Mat(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val e: Double = 0.0, val f: Double = 0.0) {
        operator fun times(o: Mat) = Mat(a * o.a + c * o.b, b * o.a + d * o.b, a * o.c + c * o.d, b * o.c + d * o.d, a * o.e + c * o.f + e, b * o.e + d * o.f + f)
        fun x(x: Double, y: Double) = a * x + c * y + e
        fun y(x: Double, y: Double) = b * x + d * y + f
        fun scale() = sqrt(abs(a * d - b * c))
        fun finite() = listOf(a, b, c, d, e, f).all { it.isFinite() }
    }

    private val NUM = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private fun numbers(s: String?): List<Double> = if (s == null) emptyList() else NUM.findAll(s).mapNotNull { it.value.toDoubleOrNull() }.toList()

    /** Длина с единицами (px, pt, pc, mm, cm, in, em, ex, %); [ref] - база для процентов. */
    private fun len(v: String?, ref: Double, def: Double = 0.0): Double {
        val t = v?.trim()?.takeIf { it.isNotEmpty() } ?: return def
        val m = NUM.find(t) ?: return def
        if (m.range.first != 0) return def
        val x = m.value.toDoubleOrNull() ?: return def
        val k = when (t.substring(m.range.last + 1).trim().lowercase()) {
            "", "px" -> 1.0; "pt" -> 4.0 / 3.0; "pc" -> 16.0; "mm" -> 96.0 / 25.4; "cm" -> 96.0 / 2.54; "in" -> 96.0
            "em" -> 16.0; "ex" -> 8.0; "%" -> ref / 100.0
            else -> return def
        }
        return (x * k).takeIf { it.isFinite() } ?: def
    }

    private fun isPercent(v: String?) = v?.trim()?.endsWith("%") == true

    private val TRANSFORM = Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)")

    private fun transform(s: String?): Mat {
        if (s.isNullOrBlank()) return Mat()
        var m = Mat()
        for (t in TRANSFORM.findAll(s)) {
            val v = numbers(t.groupValues[2])
            val op = when (t.groupValues[1]) {
                "matrix" -> if (v.size >= 6) Mat(v[0], v[1], v[2], v[3], v[4], v[5]) else Mat()
                "translate" -> Mat(e = v.getOrElse(0) { 0.0 }, f = v.getOrElse(1) { 0.0 })
                "scale" -> v.getOrElse(0) { 1.0 }.let { sx -> Mat(a = sx, d = v.getOrElse(1) { sx }) }
                "rotate" -> {
                    val r = Math.toRadians(v.getOrElse(0) { 0.0 })
                    val rot = Mat(cos(r), sin(r), -sin(r), cos(r))
                    if (v.size >= 3) Mat(e = v[1], f = v[2]) * rot * Mat(e = -v[1], f = -v[2]) else rot
                }
                "skewX" -> Mat(c = tan(Math.toRadians(v.getOrElse(0) { 0.0 })))
                "skewY" -> Mat(b = tan(Math.toRadians(v.getOrElse(0) { 0.0 })))
                else -> Mat()
            }
            m *= op
        }
        return if (m.finite()) m else Mat()
    }

    // ------------------------------------------------------------------ цвет

    private val NAMED: Map<String, Int> by lazy {
        ("black:000000,silver:c0c0c0,gray:808080,grey:808080,white:ffffff,maroon:800000,red:ff0000,purple:800080,fuchsia:ff00ff," +
            "magenta:ff00ff,green:008000,lime:00ff00,olive:808000,yellow:ffff00,navy:000080,blue:0000ff,teal:008080,aqua:00ffff," +
            "cyan:00ffff,orange:ffa500,aliceblue:f0f8ff,antiquewhite:faebd7,aquamarine:7fffd4,azure:f0ffff,beige:f5f5dc,bisque:ffe4c4," +
            "blanchedalmond:ffebcd,blueviolet:8a2be2,brown:a52a2a,burlywood:deb887,cadetblue:5f9ea0,chartreuse:7fff00,chocolate:d2691e," +
            "coral:ff7f50,cornflowerblue:6495ed,cornsilk:fff8dc,crimson:dc143c,darkblue:00008b,darkcyan:008b8b,darkgoldenrod:b8860b," +
            "darkgray:a9a9a9,darkgrey:a9a9a9,darkgreen:006400,darkkhaki:bdb76b,darkmagenta:8b008b,darkolivegreen:556b2f,darkorange:ff8c00," +
            "darkorchid:9932cc,darkred:8b0000,darksalmon:e9967a,darkseagreen:8fbc8f,darkslateblue:483d8b,darkslategray:2f4f4f," +
            "darkslategrey:2f4f4f,darkturquoise:00ced1,darkviolet:9400d3,deeppink:ff1493,deepskyblue:00bfff,dimgray:696969,dimgrey:696969," +
            "dodgerblue:1e90ff,firebrick:b22222,floralwhite:fffaf0,forestgreen:228b22,gainsboro:dcdcdc,ghostwhite:f8f8ff,gold:ffd700," +
            "goldenrod:daa520,greenyellow:adff2f,honeydew:f0fff0,hotpink:ff69b4,indianred:cd5c5c,indigo:4b0082,ivory:fffff0,khaki:f0e68c," +
            "lavender:e6e6fa,lavenderblush:fff0f5,lawngreen:7cfc00,lemonchiffon:fffacd,lightblue:add8e6,lightcoral:f08080,lightcyan:e0ffff," +
            "lightgoldenrodyellow:fafad2,lightgray:d3d3d3,lightgrey:d3d3d3,lightgreen:90ee90,lightpink:ffb6c1,lightsalmon:ffa07a," +
            "lightseagreen:20b2aa,lightskyblue:87cefa,lightslategray:778899,lightslategrey:778899,lightsteelblue:b0c4de,lightyellow:ffffe0," +
            "limegreen:32cd32,linen:faf0e6,mediumaquamarine:66cdaa,mediumblue:0000cd,mediumorchid:ba55d3,mediumpurple:9370db," +
            "mediumseagreen:3cb371,mediumslateblue:7b68ee,mediumspringgreen:00fa9a,mediumturquoise:48d1cc,mediumvioletred:c71585," +
            "midnightblue:191970,mintcream:f5fffa,mistyrose:ffe4e1,moccasin:ffe4b5,navajowhite:ffdead,oldlace:fdf5e6,olivedrab:6b8e23," +
            "orangered:ff4500,orchid:da70d6,palegoldenrod:eee8aa,palegreen:98fb98,paleturquoise:afeeee,palevioletred:db7093," +
            "papayawhip:ffefd5,peachpuff:ffdab9,peru:cd853f,pink:ffc0cb,plum:dda0dd,powderblue:b0e0e6,rebeccapurple:663399," +
            "rosybrown:bc8f8f,royalblue:4169e1,saddlebrown:8b4513,salmon:fa8072,sandybrown:f4a460,seagreen:2e8b57,seashell:fff5ee," +
            "sienna:a0522d,skyblue:87ceeb,slateblue:6a5acd,slategray:708090,slategrey:708090,snow:fffafa,springgreen:00ff7f," +
            "steelblue:4682b4,tan:d2b48c,thistle:d8bfd8,tomato:ff6347,turquoise:40e0d0,violet:ee82ee,wheat:f5deb3,whitesmoke:f5f5f5," +
            "yellowgreen:9acd32").split(',').associate { it.substringBefore(':') to it.substringAfter(':').toInt(16) }
    }

    /** Цвет CSS -> ARGB; 0 - прозрачный/none; null - не цвет. */
    internal fun parseColor(v0: String, current: Long = 0xFF000000L): Long? {
        val v = v0.trim().lowercase()
        if (v == "none" || v == "transparent") return 0L
        if (v == "currentcolor") return current
        if (v.startsWith("#")) {
            val h = v.drop(1)
            if (h.any { it !in "0123456789abcdef" }) return null
            fun d(c: Char) = c.digitToInt(16) * 17
            return when (h.length) {
                3 -> 0xFF000000L or (d(h[0]).toLong() shl 16) or (d(h[1]).toLong() shl 8) or d(h[2]).toLong()
                4 -> (d(h[3]).toLong() shl 24) or (d(h[0]).toLong() shl 16) or (d(h[1]).toLong() shl 8) or d(h[2]).toLong()
                6 -> 0xFF000000L or h.toLong(16)
                8 -> (h.substring(6).toLong(16) shl 24) or h.substring(0, 6).toLong(16)
                else -> null
            }
        }
        val fn = Regex("^(rgba?|hsla?)\\((.*)\\)$").find(v)
        if (fn != null) {
            val parts = fn.groupValues[2].split(',', '/', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            fun comp(s: String, scale: Double): Double? = if (s.endsWith("%")) s.dropLast(1).toDoubleOrNull()?.let { it / 100.0 * scale } else s.toDoubleOrNull()
            val alpha = parts.getOrNull(3)?.let { comp(it, 1.0) } ?: 1.0
            val rgb: DoubleArray = if (fn.groupValues[1].startsWith("rgb")) {
                doubleArrayOf(comp(parts[0], 255.0) ?: return null, comp(parts[1], 255.0) ?: return null, comp(parts[2], 255.0) ?: return null)
            } else {
                val hh = ((parts[0].removeSuffix("deg").toDoubleOrNull() ?: return null) % 360 + 360) % 360 / 360.0
                val s = comp(parts[1], 1.0) ?: return null; val l = comp(parts[2], 1.0) ?: return null
                val q = if (l < 0.5) l * (1 + s) else l + s - l * s; val p = 2 * l - q
                fun hue(t0: Double): Double { val t = (t0 % 1.0 + 1.0) % 1.0; return when { t < 1 / 6.0 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2 / 3.0 -> p + (q - p) * (2 / 3.0 - t) * 6; else -> p } }
                doubleArrayOf(hue(hh + 1 / 3.0) * 255, hue(hh) * 255, hue(hh - 1 / 3.0) * 255)
            }
            fun b(x: Double) = (x.coerceIn(0.0, 255.0) + 0.5).toLong()
            return (b(alpha * 255) shl 24) or (b(rgb[0]) shl 16) or (b(rgb[1]) shl 8) or b(rgb[2])
        }
        return NAMED[v]?.let { 0xFF000000L or it.toLong() }
    }

    /** Доля 0..1 из числа или процентов («0.5», «50%»); нет/ошибка - 1. */
    private fun frac(v: String?): Double {
        val t = v?.trim() ?: return 1.0
        val x = (if (t.endsWith("%")) t.dropLast(1).toDoubleOrNull()?.div(100.0) else t.toDoubleOrNull()) ?: return 1.0
        return if (x.isFinite()) x.coerceIn(0.0, 1.0) else 1.0
    }

    private fun withAlpha(c: Long, k: Double): Long {
        val a = (((c ushr 24) and 0xFF) * k.coerceIn(0.0, 1.0) + 0.5).toLong().coerceIn(0L, 255L)
        return if (a == 0L) 0L else (a shl 24) or (c and 0xFFFFFFL)
    }

    // ------------------------------------------------------------------ CSS

    private class Sel(val tag: String?, val cls: List<String>, val id: String?) {
        val spec = (if (id != null) 100 else 0) + cls.size * 10 + (if (tag != null) 1 else 0)
        fun matches(el: XEl, classes: Set<String>): Boolean =
            (tag == null || tag == el.name) && (id == null || el.attrs["id"] == id) && cls.all { it in classes }
    }

    private class Rule(val sel: Sel, val decls: Map<String, String>, val order: Int)

    private val SIMPLE_SEL = Regex("^(\\*|[A-Za-z][\\w-]*)?((?:[.#][\\w-]+)*)$")

    private fun decls(s: String): Map<String, String> = s.split(';').mapNotNull { d ->
        val k = d.substringBefore(':', "").trim().lowercase()
        val v = d.substringAfter(':', "").replace("!important", "").trim()
        if (k.isEmpty() || v.isEmpty()) null else k to v
    }.toMap()

    private fun css(text: String, notes: MutableSet<String>, rules: MutableList<Rule>) {
        val s = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        var i = 0
        while (i < s.length) {
            val open = s.indexOf('{', i)
            if (open < 0) break
            val head = s.substring(i, open).trim()
            // блок до парной скобки (у @media внутри свои блоки - пропускаются целиком)
            var depth = 1; var j = open + 1
            while (j < s.length && depth > 0) { if (s[j] == '{') depth++ else if (s[j] == '}') depth--; j++ }
            val body = s.substring(open + 1, (j - 1).coerceAtLeast(open + 1))
            i = j
            if (head.startsWith("@")) { if (!head.startsWith("@font-face") && !head.startsWith("@charset")) notes += N_CSS; continue }
            val d = decls(body)
            for (part in head.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val m = SIMPLE_SEL.matchEntire(part)
                if (m == null) { notes += N_CSS; continue }
                val tag = m.groupValues[1].takeIf { it.isNotEmpty() && it != "*" }
                val rest = Regex("[.#][\\w-]+").findAll(m.groupValues[2]).map { it.value }.toList()
                val ids = rest.filter { it.startsWith("#") }.map { it.drop(1) }
                if (ids.size > 1) { notes += N_CSS; continue }
                rules += Rule(Sel(tag, rest.filter { it.startsWith(".") }.map { it.drop(1) }, ids.firstOrNull()), d, rules.size)
            }
        }
    }

    // ------------------------------------------------------------------ контуры

    /** Контур: узлы (x, y) и ручки по 4 числа на узел (вход dx,dy, выход dx,dy), замкнут ли. */
    internal class Sub {
        val pts = ArrayList<Double>()
        val h = ArrayList<Double>()
        var closed = false
        val nodes get() = pts.size / 2
        fun add(x: Double, y: Double, inX: Double = 0.0, inY: Double = 0.0) { pts += x; pts += y; h += inX; h += inY; h += 0.0; h += 0.0 }
        fun lastX() = pts[pts.size - 2]
        fun lastY() = pts[pts.size - 1]
        fun setOut(dx: Double, dy: Double) { h[h.size - 2] = dx; h[h.size - 1] = dy }
        fun curved() = h.any { abs(it) > 1e-9 }
    }

    private class Lexer(val s: String) {
        var i = 0
        fun sep() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun cmd(): Char? { sep(); return if (i < s.length && s[i].isLetter() && s[i] != 'e' && s[i] != 'E') s[i++] else null }
        fun hasNum(): Boolean { sep(); return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.') }
        fun num(): Double? {
            sep()
            val m = NUM.find(s, i) ?: return null
            if (m.range.first != i) return null
            i = m.range.last + 1
            return m.value.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        fun flag(): Double? { sep(); return if (i < s.length && (s[i] == '0' || s[i] == '1')) (s[i++] - '0').toDouble() else null }
    }

    private class Builder {
        val subs = ArrayList<Sub>()
        var cur: Sub? = null
        var sx = 0.0; var sy = 0.0
        var x = 0.0; var y = 0.0
        fun flush() { cur?.let { if (it.nodes >= 2) subs += it }; cur = null }
        fun ensure(): Sub = cur ?: Sub().also { it.add(x, y); cur = it }
        fun move(nx: Double, ny: Double) { flush(); x = nx; y = ny; sx = nx; sy = ny; cur = Sub().also { it.add(nx, ny) } }
        fun line(nx: Double, ny: Double) { ensure().add(nx, ny); x = nx; y = ny }
        fun cubic(c1x: Double, c1y: Double, c2x: Double, c2y: Double, nx: Double, ny: Double) {
            val s = ensure()
            s.setOut(c1x - s.lastX(), c1y - s.lastY())
            s.add(nx, ny, c2x - nx, c2y - ny)
            x = nx; y = ny
        }
        fun close() {
            val s = cur ?: return
            if (s.nodes >= 3 && abs(s.lastX() - s.pts[0]) < 1e-6 && abs(s.lastY() - s.pts[1]) < 1e-6) {
                // последний узел совпал с первым: ручка входа переходит к первому узлу
                s.h[0] = s.h[s.h.size - 4]; s.h[1] = s.h[s.h.size - 3]
                repeat(2) { s.pts.removeAt(s.pts.lastIndex) }
                repeat(4) { s.h.removeAt(s.h.lastIndex) }
            }
            s.closed = true
            flush()
            x = sx; y = sy
        }
    }

    /** SVG-дуга -> кубические сегменты (не больше 90° на сегмент), как в приложении F.6 спецификации SVG. */
    private fun arc(b: Builder, rx0: Double, ry0: Double, phiDeg: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double) {
        val x1 = b.x; val y1 = b.y
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rx0); var ry = abs(ry0)
        if (rx < 1e-9 || ry < 1e-9) { b.line(x2, y2); return }
        val phi = Math.toRadians(phiDeg); val cp = cos(phi); val sp = sin(phi)
        val dx2 = (x1 - x2) / 2; val dy2 = (y1 - y2) / 2
        val x1p = cp * dx2 + sp * dy2; val y1p = -sp * dx2 + cp * dy2
        val lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lam > 1) { val k = sqrt(lam); rx *= k; ry *= k }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val coef = (if (large == sweep) -1.0 else 1.0) * sqrt(max(0.0, if (den == 0.0) 0.0 else num / den))
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2; val cy = sp * cxp + cp * cyp + (y1 + y2) / 2
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
        val th1 = ang(1.0, 0.0, ux, uy)
        var dth = ang(ux, uy, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI
        if (sweep && dth < 0) dth += 2 * PI
        val segs = ceil(abs(dth) / (PI / 2) - 1e-9).toInt().coerceIn(1, 8)
        val delta = dth / segs
        val t = 4.0 / 3.0 * tan(delta / 4)
        fun mx(px: Double, py: Double) = cx + rx * px * cp - ry * py * sp
        fun my(px: Double, py: Double) = cy + rx * px * sp + ry * py * cp
        for (k in 0 until segs) {
            val a1 = th1 + k * delta; val a2 = a1 + delta
            val c1x = cos(a1) - t * sin(a1); val c1y = sin(a1) + t * cos(a1)
            val c2x = cos(a2) + t * sin(a2); val c2y = sin(a2) - t * cos(a2)
            val last = k == segs - 1
            b.cubic(mx(c1x, c1y), my(c1x, c1y), mx(c2x, c2y), my(c2x, c2y), if (last) x2 else mx(cos(a2), sin(a2)), if (last) y2 else my(cos(a2), sin(a2)))
        }
    }

    /** Данные пути d -> контуры. Ошибка посреди данных - как в браузере: всё до неё рисуется. */
    internal fun pathData(d: String): List<Sub> {
        val lx = Lexer(d)
        val b = Builder()
        var cmd: Char? = null
        var lastC2x = 0.0; var lastC2y = 0.0; var lastQx = 0.0; var lastQy = 0.0
        var prev = ' '
        loop@ while (true) {
            val c = lx.cmd()
            if (c != null) cmd = c
            else if (cmd == null || !lx.hasNum()) break   // конец данных или мусор: всё, что до него, остаётся
            val k = cmd ?: break
            val rel = k.isLowerCase()
            val ox = if (rel) b.x else 0.0; val oy = if (rel) b.y else 0.0
            when (k.uppercaseChar()) {
                'M' -> {
                    val x = lx.num() ?: break@loop; val y = lx.num() ?: break@loop
                    b.move(ox + x, oy + y)
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> { val x = lx.num() ?: break@loop; val y = lx.num() ?: break@loop; b.line(ox + x, oy + y) }
                'H' -> { val x = lx.num() ?: break@loop; b.line(ox + x, b.y) }
                'V' -> { val y = lx.num() ?: break@loop; b.line(b.x, oy + y) }
                'C' -> {
                    val v = DoubleArray(6) { lx.num() ?: return@DoubleArray Double.NaN }
                    if (v.any { it.isNaN() }) break@loop
                    b.cubic(ox + v[0], oy + v[1], ox + v[2], oy + v[3], ox + v[4], oy + v[5])
                    lastC2x = ox + v[2]; lastC2y = oy + v[3]
                }
                'S' -> {
                    val v = DoubleArray(4) { lx.num() ?: return@DoubleArray Double.NaN }
                    if (v.any { it.isNaN() }) break@loop
                    val r = prev.uppercaseChar() == 'C' || prev.uppercaseChar() == 'S'
                    val c1x = if (r) 2 * b.x - lastC2x else b.x; val c1y = if (r) 2 * b.y - lastC2y else b.y
                    b.cubic(c1x, c1y, ox + v[0], oy + v[1], ox + v[2], oy + v[3])
                    lastC2x = ox + v[0]; lastC2y = oy + v[1]
                }
                'Q', 'T' -> {
                    val qx: Double; val qy: Double; val x: Double; val y: Double
                    if (k.uppercaseChar() == 'Q') {
                        val v = DoubleArray(4) { lx.num() ?: return@DoubleArray Double.NaN }
                        if (v.any { it.isNaN() }) break@loop
                        qx = ox + v[0]; qy = oy + v[1]; x = ox + v[2]; y = oy + v[3]
                    } else {
                        val v = DoubleArray(2) { lx.num() ?: return@DoubleArray Double.NaN }
                        if (v.any { it.isNaN() }) break@loop
                        val r = prev.uppercaseChar() == 'Q' || prev.uppercaseChar() == 'T'
                        qx = if (r) 2 * b.x - lastQx else b.x; qy = if (r) 2 * b.y - lastQy else b.y; x = ox + v[0]; y = oy + v[1]
                    }
                    val x0 = b.x; val y0 = b.y
                    b.cubic(x0 + 2.0 / 3 * (qx - x0), y0 + 2.0 / 3 * (qy - y0), x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), x, y)
                    lastQx = qx; lastQy = qy
                }
                'A' -> {
                    val rx = lx.num() ?: break@loop; val ry = lx.num() ?: break@loop; val rot = lx.num() ?: break@loop
                    val large = lx.flag() ?: break@loop; val sweep = lx.flag() ?: break@loop
                    val x = lx.num() ?: break@loop; val y = lx.num() ?: break@loop
                    arc(b, rx, ry, rot, large != 0.0, sweep != 0.0, ox + x, oy + y)
                }
                'Z' -> { b.close(); prev = 'Z'; cmd = null; continue@loop }
                else -> break@loop
            }
            prev = k
        }
        b.flush()
        return b.subs
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double, rx0: Double?, ry0: Double?): Sub? {
        if (w <= 0 || h <= 0) return null
        var rx = rx0 ?: ry0 ?: 0.0; var ry = ry0 ?: rx0 ?: 0.0
        rx = rx.coerceIn(0.0, w / 2); ry = ry.coerceIn(0.0, h / 2)
        val s = Sub(); s.closed = true
        if (rx < 1e-6 || ry < 1e-6) { s.add(x, y); s.add(x + w, y); s.add(x + w, y + h); s.add(x, y + h); return s }
        val kx = KAPPA * rx; val ky = KAPPA * ry
        val pts = listOf(x + rx, y, x + w - rx, y, x + w, y + ry, x + w, y + h - ry, x + w - rx, y + h, x + rx, y + h, x, y + h - ry, x, y + ry)
        val hs = listOf(-kx, 0.0, 0.0, 0.0, 0.0, 0.0, kx, 0.0, 0.0, -ky, 0.0, 0.0, 0.0, 0.0, 0.0, ky,
            kx, 0.0, 0.0, 0.0, 0.0, 0.0, -kx, 0.0, 0.0, ky, 0.0, 0.0, 0.0, 0.0, 0.0, -ky)
        s.pts += pts; s.h += hs
        return s
    }

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): Sub? {
        if (rx <= 0 || ry <= 0) return null
        val kx = KAPPA * rx; val ky = KAPPA * ry
        val s = Sub(); s.closed = true
        s.pts += listOf(cx, cy - ry, cx + rx, cy, cx, cy + ry, cx - rx, cy)
        s.h += listOf(-kx, 0.0, kx, 0.0, 0.0, -ky, 0.0, ky, kx, 0.0, -kx, 0.0, 0.0, ky, 0.0, -ky)
        return s
    }

    private fun mapSub(s: Sub, m: Mat): Sub {
        val r = Sub(); r.closed = s.closed
        for (k in 0 until s.nodes) {
            val x = s.pts[k * 2]; val y = s.pts[k * 2 + 1]
            r.pts += m.x(x, y); r.pts += m.y(x, y)
            for (w in 0..1) { val hx = s.h[k * 4 + w * 2]; val hy = s.h[k * 4 + w * 2 + 1]; r.h += m.a * hx + m.c * hy; r.h += m.b * hx + m.d * hy }
        }
        return r
    }

    /** Точки контура с кривыми (8 шагов на сегмент) - для площади и проверки вложенности. */
    private fun flat(s: Sub): DoubleArray {
        val n = s.nodes
        val out = ArrayList<Double>(n * 16)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = s.pts[i * 2]; val y0 = s.pts[i * 2 + 1]; val x1 = s.pts[j * 2]; val y1 = s.pts[j * 2 + 1]
            val c1x = x0 + s.h[i * 4 + 2]; val c1y = y0 + s.h[i * 4 + 3]; val c2x = x1 + s.h[j * 4]; val c2y = y1 + s.h[j * 4 + 1]
            val curved = c1x != x0 || c1y != y0 || c2x != x1 || c2y != y1
            val steps = if (curved) 8 else 1
            for (t0 in 0 until steps) {
                val t = t0.toDouble() / steps; val u = 1 - t
                out += u * u * u * x0 + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x1
                out += u * u * u * y0 + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * y1
            }
        }
        return out.toDoubleArray()
    }

    private fun area(p: DoubleArray): Double {
        var s = 0.0; val n = p.size / 2
        for (i in 0 until n) { val j = (i + 1) % n; s += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1] }
        return s / 2
    }

    private fun inside(p: DoubleArray, x: Double, y: Double): Boolean {
        var c = false; val n = p.size / 2; var j = n - 1
        for (i in 0 until n) {
            val xi = p[i * 2]; val yi = p[i * 2 + 1]; val xj = p[j * 2]; val yj = p[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    private fun shape(s: Sub, kind: VecKind, stroke: Long, fill: Long, width: Float): VectorShape {
        val h = if (s.curved()) s.h.map { it.toFloat() } else emptyList()
        return VectorShape(kind, s.pts.map { it.toFloat() }, stroke, fill, width, handles = h)
    }

    private fun bool(a: VectorShape, b: VectorShape, op: String, fill: Long) =
        VectorShape(VecKind.BOOL, a.pts + b.pts, 0L, fill, 1f, "", op, listOf(a, b))

    /**
     * Заливка составного контура: дерево вложенности, по правилу заливки (nonzero/evenodd) - какие контуры
     * переключают «залито/пусто». Внешние контуры - отдельные фигуры, дыры вычитаются (а острова в дырах снова
     * добавляются). Контуры, не меняющие заливку (вложенные в ту же сторону при nonzero), пропускаются.
     */
    private fun fillShapes(subs: List<Sub>, fill: Long, evenOdd: Boolean): List<VectorShape> {
        val areas = subs.filter { it.nodes >= 3 || (it.nodes >= 2 && it.curved()) }
        if (areas.isEmpty()) return emptyList()
        if (areas.size == 1 || areas.size > 600) return areas.map { shape(it, VecKind.POLYGON, 0L, fill, 1f) }
        val polys = areas.map { flat(it) }
        val ar = polys.map { area(it) }
        val boxes = polys.map { p ->
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (k in 0 until p.size / 2) { x0 = min(x0, p[k * 2]); x1 = max(x1, p[k * 2]); y0 = min(y0, p[k * 2 + 1]); y1 = max(y1, p[k * 2 + 1]) }
            doubleArrayOf(x0, y0, x1, y1)
        }
        val n = areas.size
        val parent = IntArray(n) { -1 }
        for (i in 0 until n) {
            var best = -1
            for (j in 0 until n) {
                if (j == i || abs(ar[j]) <= abs(ar[i])) continue
                val bi = boxes[i]; val bj = boxes[j]
                if (bi[0] < bj[0] - 1e-6 || bi[1] < bj[1] - 1e-6 || bi[2] > bj[2] + 1e-6 || bi[3] > bj[3] + 1e-6) continue
                if (!inside(polys[j], polys[i][0], polys[i][1])) continue
                if (best < 0 || abs(ar[j]) < abs(ar[best])) best = j
            }
            parent[i] = best
        }
        fun sign(i: Int) = if (ar[i] >= 0) 1 else -1
        val wOut = IntArray(n); val depth = IntArray(n)
        for (i in 0 until n) {
            var p = parent[i]; var w = 0; var d = 0; var guard = 0
            while (p >= 0 && guard++ < n) { w += sign(p); d++; p = parent[p] }
            wOut[i] = w; depth[i] = d
        }
        val toggles = BooleanArray(n) { i -> evenOdd || ((wOut[i] + sign(i) != 0) != (wOut[i] != 0)) }
        val eff = IntArray(n) { i -> var p = parent[i]; var guard = 0; while (p >= 0 && !toggles[p] && guard++ < n) p = parent[p]; p }
        val kids = (0 until n).groupBy { eff[it] }
        fun base(i: Int) = shape(areas[i], VecKind.POLYGON, 0L, fill, 1f)
        fun build(i: Int, level: Int): VectorShape {
            val ch = kids[i].orEmpty().filter { toggles[it] }
            if (ch.isEmpty() || level > 6) return base(i)
            val inner = VectorWarp.balanced(ch.map { build(it, level + 1) }, VectorBool.UNION, 0L, fill, 1f)
            return bool(base(i), inner, VectorBool.SUBTRACT, fill)
        }
        val roots = (0 until n).filter { toggles[it] && eff[it] < 0 }
        return roots.map { r -> build(r, 0).let { if (VectorWarp.depth(it) > 14) base(r) else it } }
    }

    // ------------------------------------------------------------------ обход документа

    private val INHERITED = setOf("fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity", "fill-rule", "color", "visibility",
        "stroke-dasharray", "stroke-linecap", "stroke-linejoin", "marker", "marker-start", "marker-mid", "marker-end")
    private val OWN = setOf("opacity", "display", "clip-path", "mask", "filter", "stop-color", "stop-opacity")
    private val SKIP = setOf("defs", "symbol", "clippath", "mask", "pattern", "marker", "lineargradient", "radialgradient", "filter", "style",
        "title", "desc", "metadata", "script", "foreignobject", "namedview", "font", "font-face", "glyph", "cursor", "view", "solidcolor")
    private val ANIM = setOf("animate", "animatetransform", "animatemotion", "animatecolor", "set")

    private class Ctx(val notes: MutableSet<String>, val ids: Map<String, XEl>, val rules: List<Rule>, val vw: Double, val vh: Double) {
        var shapes = 0
        var nodes = 0
        var limited = false
        val gradientCache = HashMap<String, Long?>()
    }

    private fun props(el: XEl, parent: Map<String, String>, ctx: Ctx): Map<String, String> {
        val own = HashMap<String, String>()
        for ((k, v) in el.attrs) { val key = k.lowercase(); if (key in INHERITED || key in OWN) own[key] = v.trim() }
        if (ctx.rules.isNotEmpty()) {
            val classes = el.attrs["class"]?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet().orEmpty()
            for (r in ctx.rules.filter { it.sel.matches(el, classes) }.sortedWith(compareBy({ it.sel.spec }, { it.order }))) own.putAll(r.decls)
        }
        el.attrs["style"]?.let { own.putAll(decls(it)) }
        val out = HashMap<String, String>()
        for ((k, v) in parent) if (k in INHERITED) out[k] = v
        for ((k, v) in own) if (v != "inherit") out[k] = v
        return out
    }

    private fun href(el: XEl): String? = (el.attrs["href"] ?: el.attrs["xlink:href"])?.trim()?.takeIf { it.startsWith("#") }?.drop(1)

    /** Средний цвет градиента (по стопам, со ссылками href на другой градиент). */
    private fun gradientColor(id: String, ctx: Ctx, depth: Int = 0): Long? {
        if (depth > 8) return null
        ctx.gradientCache[id]?.let { return it }
        val g = ctx.ids[id] ?: return null
        val stops = g.kids.filter { it.name == "stop" }
        val r: Long? = if (stops.isEmpty()) href(g)?.let { gradientColor(it, ctx, depth + 1) } else {
            var a = 0.0; var rr = 0.0; var gg = 0.0; var bb = 0.0
            for (s in stops) {
                val st = HashMap<String, String>()
                s.attrs["stop-color"]?.let { st["stop-color"] = it }; s.attrs["stop-opacity"]?.let { st["stop-opacity"] = it }
                s.attrs["style"]?.let { st.putAll(decls(it)) }
                val c = parseColor(st["stop-color"] ?: "black") ?: 0xFF000000L
                val op = frac(st["stop-opacity"])
                a += ((c ushr 24) and 0xFF) * op; rr += (c ushr 16) and 0xFF; gg += (c ushr 8) and 0xFF; bb += c and 0xFF
            }
            val k = stops.size.toDouble()
            fun b(x: Double) = (x / k + 0.5).toLong().coerceIn(0L, 255L)
            (b(a) shl 24) or (b(rr) shl 16) or (b(gg) shl 8) or b(bb)
        }
        ctx.gradientCache[id] = r
        return r
    }

    /** Заливка/обводка: цвет, url(#градиент) (средний цвет), url(#узор) (серый), none. */
    private fun paint(v: String?, current: Long, ctx: Ctx, def: String): Long {
        val s = (v ?: def).trim()
        if (s.startsWith("url(")) {
            val id = s.substringAfter("#", "").substringBefore(")").trim().trim('"', '\'')
            val target = ctx.ids[id]
            val fallback = s.substringAfter(")", "").trim().takeIf { it.isNotEmpty() }
            return when (target?.name?.lowercase()) {
                "lineargradient", "radialgradient" -> { ctx.notes += N_GRADIENT; gradientColor(id, ctx) ?: 0xFF808080L }
                "pattern" -> { ctx.notes += N_PATTERN; fallback?.let { parseColor(it, current) } ?: 0xFF808080L }
                else -> fallback?.let { parseColor(it, current) } ?: 0L
            }
        }
        return parseColor(s, current) ?: parseColor(def, current) ?: 0L
    }

    private fun emit(subs0: List<Sub>, m: Mat, p: Map<String, String>, alpha: Double, ctx: Ctx, out: MutableList<VectorShape>) {
        if (subs0.isEmpty() || ctx.limited) return
        if (p["visibility"] == "hidden" || p["visibility"] == "collapse") return
        val subs = subs0.map { mapSub(it, m) }
        if (subs.any { s -> s.pts.any { !it.isFinite() } || s.h.any { !it.isFinite() } }) return
        val current = parseColor(p["color"] ?: "black") ?: 0xFF000000L
        val op = alpha * frac(p["opacity"])
        val fill = withAlpha(paint(p["fill"], current, ctx, "black"), op * frac(p["fill-opacity"]))
        var stroke = withAlpha(paint(p["stroke"], current, ctx, "none"), op * frac(p["stroke-opacity"]))
        val width = (len(p["stroke-width"], sqrt((ctx.vw * ctx.vw + ctx.vh * ctx.vh) / 2), 1.0) * m.scale()).toFloat()
        if (width <= 0f || !width.isFinite()) stroke = 0L
        if (fill == 0L && stroke == 0L) return
        if (stroke != 0L) {
            if ((p["stroke-dasharray"] ?: "none") != "none") ctx.notes += N_DASH
            if ((p["stroke-linecap"] ?: "butt") != "round" || (p["stroke-linejoin"] ?: "miter") != "round") ctx.notes += N_CAP
        }
        if (listOf("marker", "marker-start", "marker-mid", "marker-end").any { (p[it] ?: "none") != "none" }) ctx.notes += N_MARKER
        val w = width.coerceIn(0.1f, 1500f)
        val made = ArrayList<VectorShape>()
        if (subs.size == 1 && fill != 0L && stroke != 0L && subs[0].closed && subs[0].nodes >= 2) {
            made += shape(subs[0], VecKind.POLYGON, stroke, fill, w)
        } else {
            if (fill != 0L) made += fillShapes(subs, fill, p["fill-rule"] == "evenodd")
            if (stroke != 0L) for (s in subs) if (s.nodes >= 2) made += shape(s, if (s.closed && s.nodes >= 3 || (s.closed && s.curved())) VecKind.POLYGON else VecKind.POLY, stroke, 0L, w)
        }
        for (s in made) {
            val nodes = s.pts.size / 2
            if (ctx.shapes >= MAX_SHAPES || ctx.nodes + nodes > MAX_NODES) { ctx.limited = true; return }
            ctx.shapes++; ctx.nodes += nodes
            out += s
        }
    }

    private fun attrNum(el: XEl, name: String, ref: Double) = len(el.attrs[name], ref)

    private fun geometry(el: XEl, ctx: Ctx): List<Sub>? {
        val vw = ctx.vw; val vh = ctx.vh; val vd = sqrt((vw * vw + vh * vh) / 2)
        return when (el.name.lowercase()) {
            "path" -> pathData(el.attrs["d"] ?: return emptyList())
            "rect" -> listOfNotNull(rect(attrNum(el, "x", vw), attrNum(el, "y", vh), attrNum(el, "width", vw), attrNum(el, "height", vh),
                el.attrs["rx"]?.let { len(it, vw) }, el.attrs["ry"]?.let { len(it, vh) }))
            "circle" -> attrNum(el, "r", vd).let { r -> listOfNotNull(ellipse(attrNum(el, "cx", vw), attrNum(el, "cy", vh), r, r)) }
            "ellipse" -> {
                val rx = el.attrs["rx"]?.takeIf { it != "auto" }?.let { len(it, vw) }
                val ry = el.attrs["ry"]?.takeIf { it != "auto" }?.let { len(it, vh) }
                listOfNotNull(ellipse(attrNum(el, "cx", vw), attrNum(el, "cy", vh), rx ?: ry ?: 0.0, ry ?: rx ?: 0.0))
            }
            "line" -> listOf(Sub().apply { add(attrNum(el, "x1", vw), attrNum(el, "y1", vh)); add(attrNum(el, "x2", vw), attrNum(el, "y2", vh)) })
            "polyline", "polygon" -> {
                val v = numbers(el.attrs["points"])
                if (v.size < 4) emptyList() else listOf(Sub().apply {
                    for (k in 0 until v.size / 2) add(v[k * 2], v[k * 2 + 1])
                    closed = el.name.lowercase() == "polygon"
                })
            }
            else -> null
        }
    }

    private fun walk(el: XEl, m0: Mat, parent: Map<String, String>, alpha: Double, ctx: Ctx, out: MutableList<VectorShape>, depth: Int, uses: Set<XEl>) {
        if (depth > MAX_DEPTH || ctx.limited) return
        val name = el.name.lowercase()
        if (name in SKIP) return
        if (name in ANIM) { ctx.notes += N_ANIM; return }
        val p = props(el, parent, ctx)
        if (p["display"] == "none") return
        if ((p["clip-path"] ?: "none") != "none" || (p["mask"] ?: "none") != "none") ctx.notes += N_CLIP
        if ((p["filter"] ?: "none") != "none") ctx.notes += N_FILTER
        val m = m0 * transform(el.attrs["transform"])
        val op = frac(p["opacity"])
        when (name) {
            "g", "a", "switch" -> for (k in el.kids) walk(k, m, p - "opacity", alpha * op, ctx, out, depth + 1, uses)
            "svg" -> {
                // вложенный svg: сдвиг x/y и viewBox -> width/height
                val x = attrNum(el, "x", ctx.vw); val y = attrNum(el, "y", ctx.vh)
                val inner = m * Mat(e = x, f = y) * viewBoxMat(el, len(el.attrs["width"], ctx.vw, ctx.vw), len(el.attrs["height"], ctx.vh, ctx.vh))
                for (k in el.kids) walk(k, inner, p - "opacity", alpha * op, ctx, out, depth + 1, uses)
            }
            "use" -> {
                val target = href(el)?.let { ctx.ids[it] } ?: return
                if (target in uses || uses.size > 8) return
                val mu = m * Mat(e = attrNum(el, "x", ctx.vw), f = attrNum(el, "y", ctx.vh))
                if (target.name.lowercase() == "symbol") {
                    for (k in target.kids) walk(k, mu, props(target, p - "opacity", ctx) - "opacity", alpha * op, ctx, out, depth + 1, uses + target)
                } else walk(target, mu, p - "opacity", alpha * op, ctx, out, depth + 1, uses + target)
            }
            "text", "tspan", "textpath", "flowroot" -> ctx.notes += N_TEXT
            "image" -> ctx.notes += N_IMAGE
            else -> {
                val g = geometry(el, ctx) ?: return
                if (el.kids.any { it.name.lowercase() in ANIM }) ctx.notes += N_ANIM
                emit(g, m, p, alpha, ctx, out)
            }
        }
    }

    /** viewBox -> область w x h с preserveAspectRatio (по умолчанию xMidYMid meet). */
    private fun viewBoxMat(el: XEl, w: Double, h: Double): Mat {
        val vb = numbers(el.attrs["viewBox"] ?: el.attrs["viewbox"])
        if (vb.size < 4 || vb[2] <= 0 || vb[3] <= 0 || w <= 0 || h <= 0) return Mat()
        val par = (el.attrs["preserveAspectRatio"] ?: "xMidYMid meet").trim()
        var sx = w / vb[2]; var sy = h / vb[3]
        var tx = 0.0; var ty = 0.0
        if (!par.startsWith("none")) {
            val s = if (par.contains("slice")) max(sx, sy) else min(sx, sy)
            sx = s; sy = s
            val ax = when { par.contains("xMin") -> 0.0; par.contains("xMax") -> 1.0; else -> 0.5 }
            val ay = when { par.contains("YMin") -> 0.0; par.contains("YMax") -> 1.0; else -> 0.5 }
            tx = (w - vb[2] * s) * ax; ty = (h - vb[3] * s) * ay
        }
        return Mat(sx, 0.0, 0.0, sy, tx - vb[0] * sx, ty - vb[1] * sy)
    }

    /**
     * Разбор SVG. [defaultName] - имя слоя для фигур вне групп (обычно имя файла).
     * Ошибки - [SvgException] с понятным сообщением (не SVG, повреждённая разметка, нет фигур, слишком большой).
     */
    fun parse(text: String, defaultName: String = "SVG"): Result {
        val notes = LinkedHashSet<String>()
        val root = parseXml(text.trimStart('\uFEFF'), notes)
        if (root.name.lowercase() != "svg") throw SvgException(MSG_NOT_SVG)
        val ids = HashMap<String, XEl>()
        val rules = ArrayList<Rule>()
        fun index(e: XEl, d: Int) {
            if (d > MAX_DEPTH) return
            e.attrs["id"]?.let { ids.putIfAbsent(it, e) }
            if (e.name.lowercase() == "style") css(e.text.toString(), notes, rules)
            for (k in e.kids) index(k, d + 1)
        }
        index(root, 0)
        val vb = numbers(root.attrs["viewBox"] ?: root.attrs["viewbox"])
        val hasVb = vb.size >= 4 && vb[2] > 0 && vb[3] > 0
        val wAttr = root.attrs["width"]?.takeIf { !isPercent(it) }?.let { len(it, 0.0) }?.takeIf { it > 0 }
        val hAttr = root.attrs["height"]?.takeIf { !isPercent(it) }?.let { len(it, 0.0) }?.takeIf { it > 0 }
        var w = wAttr ?: (if (hasVb) (hAttr?.let { it * vb[2] / vb[3] } ?: vb[2]) else 0.0)
        var h = hAttr ?: (if (hasVb) (wAttr?.let { it * vb[3] / vb[2] } ?: vb[3]) else 0.0)
        val sizeKnown = w > 0 && h > 0
        if (!sizeKnown) { w = 512.0; h = 512.0 }
        val rootM = if (hasVb) viewBoxMat(root, w, h) else Mat()
        val ctx = Ctx(notes, ids, rules, if (hasVb) vb[2] else w, if (hasVb) vb[3] else h)
        val rootProps = props(root, emptyMap(), ctx)
        val rootAlpha = frac(rootProps["opacity"])
        val layers = ArrayList<Pair<String, MutableList<VectorShape>>>()
        var looseOpen = false
        var groupNo = 0
        for (k in root.kids) {
            val list = ArrayList<VectorShape>()
            walk(k, rootM, rootProps - "opacity", rootAlpha, ctx, list, 1, emptySet())
            if (list.isEmpty()) continue
            val isGroup = k.name.lowercase() in setOf("g", "a", "switch", "svg")
            if (isGroup) {
                groupNo++
                val label = (k.attrs["inkscape:label"] ?: k.attrs["data-name"] ?: k.attrs["id"])?.trim()?.takeIf { it.isNotEmpty() }?.take(64)
                layers += (label ?: "$defaultName $groupNo") to list
                looseOpen = false
            } else if (looseOpen) layers.last().second += list
            else { layers += defaultName to list; looseOpen = true }
        }
        if (ctx.limited) notes += N_LIMIT.replace("{}", ctx.shapes.toString())
        if (layers.isEmpty()) throw SvgException(MSG_EMPTY)
        var out = layers.map { Layer(it.first, it.second.toList()) }
        if (out.size > MAX_LAYERS) { notes += N_LAYERS; out = listOf(Layer(defaultName, out.flatMap { it.shapes })) }
        if (!sizeKnown) {
            // размера нет ни в width/height, ни в viewBox - рамка по содержимому
            val b = VectorWarp.bounds(out.flatMap { it.shapes })
            if (b != null) { w = max(1.0, b[2]); h = max(1.0, b[3]) }
        }
        return Result(w.toFloat(), h.toFloat(), out, notes.toList())
    }

    /**
     * Фигуры рисунка в холсте [cw] x [ch]: рисунок по центру, крупный - уменьшается до размера холста (пропорции
     * сохраняются), мелкий - остаётся в своём размере. Толщина обводки масштабируется вместе с рисунком.
     */
    fun fit(r: Result, cw: Int, ch: Int): List<Layer> {
        val s = min(1.0, min(cw / r.width.toDouble().coerceAtLeast(1e-6), ch / r.height.toDouble().coerceAtLeast(1e-6)))
        val tx = (cw - r.width * s) / 2; val ty = (ch - r.height * s) / 2
        val f = VectorWarp.affine(s, 0.0, 0.0, s, tx, ty)
        return r.layers.map { l -> Layer(l.name, l.shapes.mapNotNull { VectorWarp.map(it, f, s) }) }.filter { it.shapes.isNotEmpty() }
    }

    /** Холст нового проекта для SVG: размер рисунка; мелкие иконки увеличиваются до 1024 по длинной стороне. */
    fun canvasFor(r: Result): Pair<Int, Int> {
        val longSide = max(r.width, r.height).toDouble()
        val k = if (longSide < 512.0) 1024.0 / longSide.coerceAtLeast(1.0) else 1.0
        return (r.width * k).toInt().coerceAtLeast(16) to (r.height * k).toInt().coerceAtLeast(16)
    }

    /** Фигуры на холсте [cw] x [ch] нового проекта (рисунок заполняет холст, обводка в том же масштабе). */
    fun scaleTo(r: Result, cw: Int, ch: Int): List<Layer> {
        val s = min(cw / r.width.toDouble().coerceAtLeast(1e-6), ch / r.height.toDouble().coerceAtLeast(1e-6))
        val tx = (cw - r.width * s) / 2; val ty = (ch - r.height * s) / 2
        val f = VectorWarp.affine(s, 0.0, 0.0, s, tx, ty)
        return r.layers.map { l -> Layer(l.name, l.shapes.mapNotNull { VectorWarp.map(it, f, s) }) }.filter { it.shapes.isNotEmpty() }
    }
}
