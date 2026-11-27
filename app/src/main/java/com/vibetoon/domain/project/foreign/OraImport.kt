package com.vibetoon.domain.project.foreign

import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectArchive
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectMeta
import org.w3c.dom.Element

/**
 * Пакет 28: OpenRaster (.ora; Krita, MyPaint, GIMP, Pinta) - слои PNG по stack.xml (первый элемент стопки - верх),
 * вложенные stack - группы. Krita (.kra) хранит слои в своём формате плиток, поэтому из .kra берётся сведённая
 * картинка mergedimage.png (одним слоем).
 */
object OraImport {
    /** composite-op OpenRaster -> ключ наложения VibeToon (null - нет такого режима). */
    fun blendOf(op: String): String? = when (op.removePrefix("svg:").removePrefix("krita:")) {
        "", "src-over" -> "norm"; "multiply" -> "mul "; "screen" -> "scrn"; "overlay" -> "over"; "soft-light" -> "sLit"
        "plus", "add" -> "lddg"; "darken" -> "dark"; "lighten" -> "lite"; "color-dodge" -> "div "; "color-burn" -> "idiv"
        else -> null
    }

    private fun xml(bytes: ByteArray): Element {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        f.isNamespaceAware = false
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(bytes.inputStream()).documentElement
    }

    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>()
        val n = childNodes
        for (i in 0 until n.length) (n.item(i) as? Element)?.let { out += it }
        return out
    }

    fun convertOra(src: ForeignImport.Source, sink: ForeignImport.Sink, title: String): ForeignImport.Result {
        val stack = xml(src.read("stack.xml") ?: throw IllegalArgumentException("stack.xml"))
        val iw = stack.getAttribute("w").toIntOrNull() ?: 0
        val ih = stack.getAttribute("h").toIntOrNull() ?: 0
        require(iw > 0 && ih > 0) { "image size" }
        val (cw, ch) = ProjectArchive.psdCanvas(iw, ih)
        val sx = cw.toFloat() / iw; val sy = ch.toFloat() / ih
        val ids = ForeignImport.Ids()
        val media = ArrayList<MediaItem>()
        val regroup = ArrayList<Pair<Int, Int>>()
        val props = HashMap<Int, LayerProps>()
        val unsupported = LinkedHashSet<String>()
        val notes = ArrayList<String>()
        fun common(e: Element, group: Boolean): LayerProps {
            val op = e.getAttribute("composite-op")
            val key = blendOf(op)
            if (key == null && !group) unsupported += op.removePrefix("svg:")
            return LayerProps(opacity = (e.getAttribute("opacity").toFloatOrNull() ?: 1f).coerceIn(0f, 1f),
                blend = if (group) "norm" else key ?: "norm", visible = e.getAttribute("visibility") != "hidden",
                locked = e.getAttribute("edit-locked") == "true")
        }
        fun add(stackEl: Element, parent: Int?, depth: Int): Boolean {
            if (depth > 32) return false
            var any = false
            for (e in stackEl.children().reversed()) {
                when (e.tagName) {
                    "layer" -> {
                        val path = e.getAttribute("src")
                        val bytes = src.read(path) ?: continue
                        val (w, h) = PngIO.imageSize(bytes) ?: continue
                        val id = ids.next()
                        val ext = path.substringAfterLast('.', "png").lowercase()
                        val file = sink.put("ora_${id}_${ForeignImport.safeName(e.getAttribute("name"))}.$ext", bytes)
                        val x = e.getAttribute("x").toFloatOrNull() ?: 0f; val y = e.getAttribute("y").toFloatOrNull() ?: 0f
                        media += MediaItem(id, MediaKind.IMAGE, e.getAttribute("name").ifBlank { "Layer" }, file, w, h, x * sx, y * sy, w * sx, h * sy)
                        if (parent != null) regroup += id to parent
                        val p = common(e, false)
                        if (p != LayerProps()) props[id] = p
                        any = true
                    }
                    "stack" -> {
                        val id = ids.next()
                        val start = media.size; val startRe = regroup.size
                        if (add(e, id, depth + 1)) {
                            media += MediaItem(id, MediaKind.GROUP, e.getAttribute("name").ifBlank { "Group" }, "")
                            if (parent != null) regroup += id to parent
                            val p = common(e, true)
                            if (p != LayerProps()) props[id] = p
                            any = true
                        } else { while (media.size > start) media.removeAt(media.size - 1); while (regroup.size > startRe) regroup.removeAt(regroup.size - 1) }
                    }
                    "text" -> notes += "Текстовые элементы OpenRaster не переносятся"
                }
            }
            return any
        }
        val top = stack.children().firstOrNull { it.tagName == "stack" } ?: throw IllegalArgumentException("stack")
        if (!add(top, null, 0)) throw ForeignImport.Unsupported(ForeignImport.MSG_EMPTY)
        if (unsupported.isNotEmpty()) notes += "Режимы наложения заменены на Normal: ${unsupported.joinToString()}"
        if (iw != cw || ih != ch) notes += "Холст ${iw}×${ih} вписан в ${cw}×${ch}"
        val meta = ProjectMeta("import", title, cw, ch, 24, 120, 0L)
        return ForeignImport.Result(ProjectData(meta, media = media, regroup = regroup, layerProps = props), "OpenRaster", notes.distinct())
    }

    fun convertKra(src: ForeignImport.Source, sink: ForeignImport.Sink, title: String): ForeignImport.Result {
        val png = src.read("mergedimage.png") ?: throw ForeignImport.Unsupported("В файле Krita нет сведённой картинки (mergedimage.png): сохраните его в Krita в PSD или ORA")
        val (w, h) = PngIO.imageSize(png) ?: throw IllegalArgumentException("mergedimage.png")
        val (cw, ch) = ProjectArchive.psdCanvas(w, h)
        val id = ForeignImport.Ids().next()
        val file = sink.put("kra_${id}.png", png)
        val name = runCatching { xml(src.read("maindoc.xml")!!).getElementsByTagName("IMAGE").item(0)?.let { (it as Element).getAttribute("name") } }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: title
        val meta = ProjectMeta("import", name, cw, ch, 24, 120, 0L)
        return ForeignImport.Result(ProjectData(meta, media = listOf(MediaItem(id, MediaKind.IMAGE, name, file, w, h, 0f, 0f, cw.toFloat(), ch.toFloat()))),
            "Krita", listOf("Krita: слои хранятся во внутреннем формате - импортирована сведённая картинка. Для отдельных слоёв сохраните файл в Krita как PSD или ORA"))
    }
}

/** Пакет 28: ZIP с последовательностью кадров (экспорт ToonSquid, Rough Animator, FlipaClip...) -> покадровый слой. */
object SequenceImport {
    /** Естественный порядок имён: frame2 раньше frame10. */
    fun naturalKey(s: String): List<Comparable<*>> = Regex("\\d+|\\D+").findAll(s.lowercase()).map { m ->
        m.value.toLongOrNull()?.let { it as Comparable<*> } ?: m.value
    }.toList()

    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val ka = naturalKey(a); val kb = naturalKey(b)
        for (i in 0 until minOf(ka.size, kb.size)) {
            val x = ka[i]; val y = kb[i]
            val c = when {
                x is Long && y is Long -> x.compareTo(y)
                x is Long -> -1
                y is Long -> 1
                else -> (x as String).compareTo(y as String)
            }
            if (c != 0) return@Comparator c
        }
        ka.size.compareTo(kb.size)
    }

    fun convert(src: ForeignImport.Source, sink: ForeignImport.Sink, title: String, fps: Int = 24): ForeignImport.Result {
        val names = src.names().filter { it.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp") }.sortedWith(naturalOrder)
        val limit = com.vibetoon.domain.project.PsdInsertPlan.MAX_FRAMES
        val frames = ArrayList<Triple<String, ByteArray, Pair<Int, Int>>>()
        for (n in names) {
            if (frames.size >= limit) break
            val b = src.read(n) ?: continue
            val size = PngIO.imageSize(b) ?: continue
            frames += Triple(n, b, size)
        }
        if (frames.isEmpty()) throw ForeignImport.Unsupported(ForeignImport.MSG_EMPTY)
        val w = frames.maxOf { it.third.first }; val h = frames.maxOf { it.third.second }
        val (cw, ch) = ProjectArchive.psdCanvas(w, h)
        val id = ForeignImport.Ids().next()
        val cells = frames.mapIndexed { i, (n, b, _) -> FrameCell(i, sink.put("seq_${id}_$i.${n.substringAfterLast('.').lowercase()}", b)) }
        val notes = ArrayList<String>()
        if (names.size > frames.size) notes += "Импортировано кадров: ${frames.size} из ${names.size}"
        if (frames.any { it.third != (w to h) }) notes += "Кадры разного размера растянуты до ${w}×${h}"
        if (cells.size > 1) notes += "Кадры по порядку имён файлов, по одному кадру проекта (${fps} fps)"
        val meta = ProjectMeta("import", title, cw, ch, fps, if (cells.size > 1) cells.size else 120, 0L)
        val item = MediaItem(id, MediaKind.IMAGE, title, cells[0].file, frames[0].third.first, frames[0].third.second, 0f, 0f, cw.toFloat(), ch.toFloat(), cells = if (cells.size > 1) cells else emptyList())
        return ForeignImport.Result(ProjectData(meta, media = listOf(item)), if (cells.size > 1) "Последовательность кадров" else "Картинка", notes)
    }
}
