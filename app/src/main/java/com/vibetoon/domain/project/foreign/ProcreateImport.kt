package com.vibetoon.domain.project.foreign

import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectArchive
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectMeta
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Пакет 28: файл Procreate (.procreate) -> проект VibeToon.
 *
 * Формат: ZIP с Document.archive (NSKeyedArchiver, корень SilicaDocument) и плитками слоёв
 * `<UUID слоя>/<столбец>~<строка>.chunk|.lz4` (LZO1X или Apple LZ4). Плитки - премультиплицированный RGBA (маски -
 * 1 байт на пиксель), строки снизу вверх, крайние плитки обрезаны по холсту, пустые не хранятся.
 * unwrappedLayers[0] - верх стопки; SilicaGroup - группа с children.
 *
 * Каждый слой становится картинкой (PNG по рамке нарисованного), группы - группами VibeToon. Маска слоя
 * запекается в альфу, поворот/отражение холста применяется к пикселям. Холст больше 4096 уменьшается.
 * Animation Assist (featureSet == 2): каждый верхний слой/группа - кадр одного покадрового слоя (снизу вверх).
 */
object ProcreateImport {
    /** Код наложения Procreate -> название (для заметок) и ключ VibeToon (null - нет такого режима). */
    val BLEND: Map<Int, Pair<String, String?>> = mapOf(
        0 to ("Normal" to "norm"), 1 to ("Multiply" to "mul "), 2 to ("Screen" to "scrn"), 3 to ("Add" to "lddg"),
        4 to ("Lighten" to "lite"), 5 to ("Exclusion" to null), 6 to ("Difference" to null), 7 to ("Subtract" to null),
        8 to ("Linear Burn" to null), 9 to ("Color Dodge" to "div "), 10 to ("Color Burn" to "idiv"), 11 to ("Overlay" to "over"),
        12 to ("Hard Light" to null), 13 to ("Color" to null), 14 to ("Luminosity" to null), 15 to ("Hue" to null),
        16 to ("Saturation" to null), 17 to ("Soft Light" to "sLit"), 19 to ("Darken" to "dark"), 20 to ("Hard Mix" to null),
        21 to ("Vivid Light" to null), 22 to ("Linear Light" to null), 23 to ("Pin Light" to null), 24 to ("Lighter Color" to null),
        25 to ("Darker Color" to null), 26 to ("Divide" to null)
    )

    /** orientation Procreate -> поворот по часовой (градусы). */
    fun rotationOf(code: Int): Int = when (code) { 2 -> 180; 3 -> 270; 4 -> 90; else -> 0 }

    private val TILE = Regex("^([^/]+)/(\\d+)~(\\d+)\\.(chunk|lz4)$")

    sealed interface Item { val name: String; val hidden: Boolean }
    data class Group(override val name: String, override val hidden: Boolean, val opacity: Float, val children: List<Item>) : Item
    data class Layer(
        override val name: String, override val hidden: Boolean, val uuid: String, val opacity: Float, val blend: Int,
        val clipped: Boolean, val locked: Boolean, val text: Boolean, val mask: String?
    ) : Item

    data class Tile(val col: Int, val row: Int, val name: String, val lz4: Boolean)

    /** Геометрия документа: исходный размер, плитка, поворот/отражения и шаг уменьшения [k]. */
    class Geo(val w0: Int, val h0: Int, val ts: Int, val rot: Int, val fh: Boolean, val fv: Boolean) {
        val cols = (w0 + ts - 1) / ts
        val rows = (h0 + ts - 1) / ts
        val swap = rot == 90 || rot == 270
        /** Размер после поворота. */
        val w = if (swap) h0 else w0
        val h = if (swap) w0 else h0
        /** Целый шаг уменьшения: длинная сторона результата не больше 4096. */
        val k = max(1, ceil(max(w, h) / 4096.0).toInt())
        val outW = (w + k - 1) / k
        val outH = (h + k - 1) / k

        /** Пиксель плитки (x, yr - строка от низа) -> координаты повёрнутого холста. */
        fun u(x: Int, yr: Int): Int { val y = h0 - 1 - yr; var u = when (rot) { 90 -> h0 - 1 - y; 180 -> w0 - 1 - x; 270 -> y; else -> x }; if (fh) u = w - 1 - u; return u }
        fun v(x: Int, yr: Int): Int { val y = h0 - 1 - yr; var v = when (rot) { 90 -> x; 180 -> h0 - 1 - y; 270 -> w0 - 1 - x; else -> y }; if (fv) v = h - 1 - v; return v }
    }

    /** Растр слоя в пикселях результата: рамка (x, y, w, h) и RGBA без премультипликации. */
    class Raster(val x: Int, val y: Int, val w: Int, val h: Int, val rgba: ByteArray)

    class Doc(val k: BPlist.Keyed, val geo: Geo, val tree: List<Item>, val tiles: Map<String, List<Tile>>, val src: ForeignImport.Source) {
        val root get() = k.root
    }

    fun open(src: ForeignImport.Source): Doc {
        val arc = src.read("Document.archive") ?: throw IllegalArgumentException("Document.archive")
        val k = BPlist.Keyed(BPlist.parse(arc))
        val root = k.root
        val m = Regex("(\\d+)\\D+(\\d+)").find(k.str(root, "size") ?: "") ?: throw IllegalArgumentException("size")
        val w0 = m.groupValues[1].toInt(); val h0 = m.groupValues[2].toInt()
        require(w0 in 1..32768 && h0 in 1..32768) { "size" }
        val ts = (k.num(root, "tileSize")?.toInt() ?: 256).takeIf { it in 16..4096 } ?: 256
        val geo = Geo(w0, h0, ts, rotationOf(k.num(root, "orientation")?.toInt() ?: 1), k.bool(root, "flippedHorizontally"), k.bool(root, "flippedVertically"))
        val tiles = HashMap<String, MutableList<Tile>>()
        for (n in src.names()) {
            val t = TILE.find(n) ?: continue
            tiles.getOrPut(t.groupValues[1]) { ArrayList() } += Tile(t.groupValues[2].toInt(), t.groupValues[3].toInt(), n, t.groupValues[4] == "lz4")
        }
        fun walk(list: List<Any?>, depth: Int): List<Item> = list.mapNotNull { o ->
            if (depth > 32) return@mapNotNull null
            if (k.cls(o) == "SilicaGroup") Group(k.str(o, "name") ?: "Group", k.bool(o, "isHidden"),
                (k.num(o, "opacity") ?: 1.0).toFloat().coerceIn(0f, 1f), walk(k.arr(o, "children"), depth + 1))
            else {
                val uuid = k.str(o, "UUID") ?: return@mapNotNull null
                val ext = k.num(o, "extendedBlend")?.toInt()
                val bl = k.num(o, "blend")?.toInt()
                val blend = if (ext != null && ext in BLEND) ext else if (bl != null && bl in BLEND) bl else 0
                val maskObj = k.get(o, "mask")
                val mu = maskObj?.let { k.str(it, "UUID") }?.takeIf { !k.bool(maskObj, "hidden") }
                Layer(k.str(o, "name") ?: "Layer", k.bool(o, "hidden"), uuid, (k.num(o, "opacity") ?: 1.0).toFloat().coerceIn(0f, 1f),
                    blend, k.bool(o, "clipped"), k.bool(o, "locked"), k.get(o, "text") != null, mu)
            }
        }
        return Doc(k, geo, walk(k.arr(root, "unwrappedLayers"), 0), tiles, src)
    }

    /** Плитка: распаковка и выравнивание; null - повреждена (слой остаётся без неё). */
    private fun decodeTile(doc: Doc, t: Tile, bpp: Int): ByteArray? {
        val g = doc.geo
        val tw = if (t.col == g.cols - 1) g.w0 - t.col * g.ts else g.ts
        val th = if (t.row == g.rows - 1) g.h0 - t.row * g.ts else g.ts
        val data = doc.src.read(t.name) ?: return null
        val out = ByteArray(tw * th * bpp)
        val n = try { if (t.lz4) TileCodecs.bv4(data, out) else TileCodecs.lzo(data, out) } catch (e: IllegalArgumentException) { return null } catch (e: IndexOutOfBoundsException) { return null }
        return if (n >= out.size) out else null
    }

    /** Пиксели слоя [uuid] (маска [mask] запекается) в рамке нарисованного; null - пустой слой. */
    fun raster(doc: Doc, uuid: String, mask: String?): Raster? {
        val g = doc.geo
        val list = doc.tiles[uuid].orEmpty().filter { it.col < g.cols && it.row < g.rows }
        if (list.isEmpty()) return null
        val masks = if (mask != null) doc.tiles[mask].orEmpty().filter { it.col < g.cols && it.row < g.rows }.associateBy { it.row * g.cols + it.col } else emptyMap()
        // рамка плиток в повёрнутом холсте (пиксели результата)
        var ux0 = Int.MAX_VALUE; var uy0 = Int.MAX_VALUE; var ux1 = -1; var uy1 = -1
        for (t in list) {
            val x0 = t.col * g.ts; val x1 = min(g.w0, x0 + g.ts) - 1
            val y0 = t.row * g.ts; val y1 = min(g.h0, y0 + g.ts) - 1
            for ((x, y) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) {
                val u = g.u(x, y) / g.k; val v = g.v(x, y) / g.k
                ux0 = min(ux0, u); ux1 = max(ux1, u); uy0 = min(uy0, v); uy1 = max(uy1, v)
            }
        }
        val bw = ux1 - ux0 + 1; val bh = uy1 - uy0 + 1
        require(bw.toLong() * bh * 4 < Int.MAX_VALUE) { "layer too large" }
        val buf = ByteArray(bw * bh * 4)
        var px0 = Int.MAX_VALUE; var py0 = Int.MAX_VALUE; var px1 = -1; var py1 = -1
        for (t in list) {
            val tile = decodeTile(doc, t, 4) ?: continue
            val mt = masks[t.row * g.cols + t.col]?.let { decodeTile(doc, it, 1) }
            val x0 = t.col * g.ts; val y0 = t.row * g.ts
            val tw = min(g.ts, g.w0 - x0); val th = min(g.ts, g.h0 - y0)
            for (y in 0 until th) {
                var s = y * tw * 4
                for (x in 0 until tw) {
                    val a = tile[s + 3].toInt() and 0xFF
                    if (a != 0) {
                        val uu = g.u(x0 + x, y0 + y); val vv = g.v(x0 + x, y0 + y)
                        if (g.k == 1 || (uu % g.k == 0 && vv % g.k == 0)) {
                            val m = if (mt != null) mt[y * tw + x].toInt() and 0xFF else 255
                            val na = a * m / 255
                            if (na > 0) {
                                val ou = uu / g.k - ux0; val ov = vv / g.k - uy0
                                val o = (ov * bw + ou) * 4
                                for (c in 0 until 3) buf[o + c] = min(255, ((tile[s + c].toInt() and 0xFF) * 255 + a / 2) / a).toByte()
                                buf[o + 3] = na.toByte()
                                if (ou < px0) px0 = ou; if (ou > px1) px1 = ou; if (ov < py0) py0 = ov; if (ov > py1) py1 = ov
                            }
                        }
                    }
                    s += 4
                }
            }
        }
        if (px1 < 0) return null
        val cw = px1 - px0 + 1; val ch = py1 - py0 + 1
        if (cw == bw && ch == bh) return Raster(ux0, uy0, bw, bh, buf)
        val out = ByteArray(cw * ch * 4)
        for (y in 0 until ch) System.arraycopy(buf, ((py0 + y) * bw + px0) * 4, out, y * cw * 4, cw * 4)
        return Raster(ux0 + px0, uy0 + py0, cw, ch, out)
    }

    /** Наложение [r] на холст [dst] (RGBA без премультипликации, размер outW x outH) с прозрачностью [opacity]. */
    fun over(dst: ByteArray, dw: Int, r: Raster, opacity: Float) {
        val dh = dst.size / 4 / dw
        for (y in 0 until r.h) {
            val dy = r.y + y; if (dy !in 0 until dh) continue
            for (x in 0 until r.w) {
                val dx = r.x + x; if (dx !in 0 until dw) continue
                val s = (y * r.w + x) * 4
                val sa = (r.rgba[s + 3].toInt() and 0xFF) / 255f * opacity
                if (sa <= 0f) continue
                val d = (dy * dw + dx) * 4
                val da = (dst[d + 3].toInt() and 0xFF) / 255f
                val oa = sa + da * (1 - sa)
                for (c in 0 until 3) {
                    val sc = (r.rgba[s + c].toInt() and 0xFF) / 255f; val dc = (dst[d + c].toInt() and 0xFF) / 255f
                    dst[d + c] = (((sc * sa + dc * da * (1 - sa)) / oa) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
                dst[d + 3] = (oa * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
    }

    fun convert(src: ForeignImport.Source, sink: ForeignImport.Sink, title: String): ForeignImport.Result {
        val doc = open(src)
        val g = doc.geo
        val k = doc.k
        val root = doc.root
        val notes = LinkedHashSet<String>()
        val (cw, ch) = ProjectArchive.psdCanvas(g.w, g.h)
        val sx = cw.toFloat() / g.outW; val sy = ch.toFloat() / g.outH
        if (g.k > 1) notes += "Холст ${g.w}×${g.h} уменьшен до ${cw}×${ch}"
        val bgHidden = k.bool(root, "backgroundHidden")
        val bgBytes = k.bytes(root, "backgroundColor")
        val bg = if (bgHidden) 0L else if (bgBytes != null && bgBytes.size >= 16) {
            fun f(i: Int) = java.lang.Float.intBitsToFloat((bgBytes[i].toInt() and 0xFF) or ((bgBytes[i + 1].toInt() and 0xFF) shl 8) or ((bgBytes[i + 2].toInt() and 0xFF) shl 16) or ((bgBytes[i + 3].toInt() and 0xFF) shl 24))
            fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toLong()
            (0xFFL shl 24) or (c(f(0)) shl 16) or (c(f(4)) shl 8) or c(f(8))
        } else 0xFFFFFFFFL
        val name = k.str(root, "name")?.trim()?.takeIf { it.isNotEmpty() && it != "Untitled Artwork" } ?: title
        val ids = ForeignImport.Ids()
        val media = ArrayList<MediaItem>()
        val regroup = ArrayList<Pair<Int, Int>>()
        val props = HashMap<Int, LayerProps>()
        val animation = k.get(root, "animation") != null && k.num(root, "featureSet")?.toInt() == 2
        var fps = 24
        var duration = 120

        if (animation) {
            val anim = k.get(root, "animation")
            fps = (anim as? Map<*, *>)?.keys?.map { it.toString() }?.firstOrNull { Regex("(?i)(frame.?rate|fps|frames.?per.?second)").containsMatchIn(it) }
                ?.let { k.num(anim, it)?.toInt() }?.takeIf { it in 1..120 } ?: 15
            val frames = doc.tree.reversed().filter { !it.hidden }
            val id = ids.next()
            val cells = ArrayList<FrameCell>()
            val limit = com.vibetoon.domain.project.PsdInsertPlan.MAX_FRAMES
            for (fr in frames.take(limit)) {
                val canvas = ByteArray(g.outW * g.outH * 4)
                fun paint(it: Item, alpha: Float) {
                    if (it.hidden) return
                    when (it) {
                        is Layer -> raster(doc, it.uuid, it.mask)?.let { r -> over(canvas, g.outW, r, alpha * it.opacity) }
                        is Group -> for (c in it.children.reversed()) paint(c, alpha * it.opacity)
                    }
                }
                paint(fr, 1f)
                val file = sink.write("pc_${id}_${cells.size}.png") { PngIO.write(it, g.outW, g.outH, canvas) }
                cells += FrameCell(cells.size, file)
            }
            if (cells.isEmpty()) throw ForeignImport.Unsupported(ForeignImport.MSG_EMPTY)
            if (frames.size > limit) notes += "Импортировано кадров: $limit из ${frames.size}"
            notes += "Animation Assist: кадры сведены (режимы наложения внутри кадра - как Normal), настройки кальки и удержания кадров не переносятся"
            media += MediaItem(id, MediaKind.IMAGE, name, cells[0].file, g.outW, g.outH, 0f, 0f, cw.toFloat(), ch.toFloat(),
                cells = if (cells.size > 1) cells else emptyList())
            duration = cells.size
        } else {
            val unsupported = LinkedHashSet<String>()
            var texts = 0
            /** Добавляет поддерево снизу вверх; true - что-то добавлено. */
            fun add(items: List<Item>, parent: Int?): Boolean {
                var any = false
                for (it in items.reversed()) {
                    when (it) {
                        is Layer -> {
                            val r = raster(doc, it.uuid, it.mask) ?: continue
                            val id = ids.next()
                            val file = sink.write("pc_${id}_${ForeignImport.safeName(it.name)}.png") { o -> PngIO.write(o, r.w, r.h, r.rgba) }
                            media += MediaItem(id, MediaKind.IMAGE, it.name.ifBlank { "Layer" }, file, r.w, r.h, r.x * sx, r.y * sy, r.w * sx, r.h * sy)
                            if (parent != null) regroup += id to parent
                            val (bname, bkey) = BLEND[it.blend] ?: ("Normal" to "norm")
                            if (bkey == null) unsupported += bname
                            if (it.text) texts++
                            val p = LayerProps(opacity = it.opacity, blend = bkey ?: "norm", clip = it.clipped, visible = !it.hidden, locked = it.locked)
                            if (p != LayerProps()) props[id] = p
                            any = true
                        }
                        is Group -> {
                            val id = ids.next()
                            val start = media.size; val startRe = regroup.size
                            if (add(it.children, id)) {
                                media += MediaItem(id, MediaKind.GROUP, it.name.ifBlank { "Group" }, "")
                                if (parent != null) regroup += id to parent
                                val p = LayerProps(opacity = it.opacity, visible = !it.hidden)
                                if (p != LayerProps()) props[id] = p
                                any = true
                            } else { while (media.size > start) media.removeAt(media.size - 1); while (regroup.size > startRe) regroup.removeAt(regroup.size - 1) }
                        }
                    }
                }
                return any
            }
            if (!add(doc.tree, null)) throw ForeignImport.Unsupported(ForeignImport.MSG_EMPTY)
            if (unsupported.isNotEmpty()) notes += "Режимы наложения заменены на Normal: ${unsupported.joinToString()}"
            if (texts > 0) notes += "Текстовые слои импортированы картинками: $texts"
            if (doc.tree.any { hasMask(it) }) notes += "Маски слоёв запечены в прозрачность"
        }
        val meta = ProjectMeta("import", name, cw, ch, fps, duration.coerceAtLeast(1), bg)
        return ForeignImport.Result(ProjectData(meta, media = media, regroup = regroup, layerProps = props), "Procreate", notes.toList())
    }

    private fun hasMask(it: Item): Boolean = when (it) { is Layer -> it.mask != null; is Group -> it.children.any(::hasMask) }
}
