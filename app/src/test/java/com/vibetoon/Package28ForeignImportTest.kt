package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.ProjectSettings
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectArchive
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.foreign.BPlist
import com.vibetoon.domain.project.foreign.ForeignImport
import com.vibetoon.domain.project.foreign.LottieImport
import com.vibetoon.domain.project.foreign.OraImport
import com.vibetoon.domain.project.foreign.PngIO
import com.vibetoon.domain.project.foreign.ProcreateImport
import com.vibetoon.domain.project.foreign.SequenceImport
import com.vibetoon.domain.project.foreign.TileCodecs
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.vector.VecKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs

/** Пакет 28: импорт проектов других приложений - bplist, плитки Procreate, PNG, Procreate, Lottie, ORA/KRA, кадры. */
class Package28ForeignImportTest {

    // ------------------------------------------------------------------ вспомогательное: запись bplist00 (как пишет Apple)

    private object PlistWriter {
        fun write(root: Any?): ByteArray {
            val objs = ArrayList<Any?>()
            val refs = HashMap<Int, List<Int>>()
            fun flat(v: Any?): Int {
                val id = objs.size
                objs += v
                when (v) {
                    is List<*> -> refs[id] = v.map { flat(it) }
                    is Map<*, *> -> { val ks = v.keys.map { flat(it.toString()) }; val vs = v.values.map { flat(it) }; refs[id] = ks + vs }
                }
                return id
            }
            flat(root)
            val refSize = if (objs.size < 256) 1 else 2
            val out = ByteArrayOutputStream()
            out.write("bplist00".toByteArray())
            val offsets = IntArray(objs.size)
            fun be(v: Long, n: Int) { for (i in n - 1 downTo 0) out.write(((v shr (i * 8)) and 0xFF).toInt()) }
            fun marker(type: Int, len: Int) { if (len < 15) out.write((type shl 4) or len) else { out.write((type shl 4) or 15); out.write(0x11); be(len.toLong(), 2) } }
            for ((i, v) in objs.withIndex()) {
                offsets[i] = out.size()
                when (v) {
                    null -> out.write(0)
                    is Boolean -> out.write(if (v) 9 else 8)
                    is Int -> { out.write(0x13); be(v.toLong(), 8) }
                    is Long -> { out.write(0x13); be(v, 8) }
                    is Double -> { out.write(0x23); be(java.lang.Double.doubleToLongBits(v), 8) }
                    is Float -> { out.write(0x22); be(java.lang.Float.floatToIntBits(v).toLong() and 0xFFFFFFFFL, 4) }
                    is String -> if (v.all { it.code < 128 }) { marker(5, v.length); out.write(v.toByteArray(Charsets.US_ASCII)) } else { marker(6, v.length); out.write(v.toByteArray(Charsets.UTF_16BE)) }
                    is ByteArray -> { marker(4, v.size); out.write(v) }
                    is BPlist.Uid -> { out.write(0x81); be(v.index.toLong(), 2) }
                    is List<*> -> { marker(0xA, v.size); refs[i]!!.forEach { be(it.toLong(), refSize) } }
                    is Map<*, *> -> { marker(0xD, v.size); refs[i]!!.forEach { be(it.toLong(), refSize) } }
                    else -> error("type")
                }
            }
            val table = out.size()
            for (o in offsets) be(o.toLong(), 4)
            repeat(6) { out.write(0) }
            out.write(4); out.write(refSize)
            be(objs.size.toLong(), 8); be(0, 8); be(table.toLong(), 8)
            return out.toByteArray()
        }
    }

    /** Архив NSKeyedArchiver: объекты добавляются по одному, ссылки - Uid. */
    private class Archive {
        val objects = arrayListOf<Any?>("\$null")
        fun add(v: Any?): BPlist.Uid { objects += v; return BPlist.Uid(objects.size - 1) }
        fun cls(name: String) = add(mapOf("\$classname" to name, "\$classes" to listOf(name, "NSObject")))
        fun array(items: List<BPlist.Uid>) = add(mapOf("NS.objects" to items, "\$class" to cls("NSArray")))
        fun bytes(root: BPlist.Uid) = PlistWriter.write(mapOf("\$archiver" to "NSKeyedArchiver", "\$version" to 100000, "\$top" to mapOf("root" to root), "\$objects" to objects))
    }

    // ------------------------------------------------------------------ кодеки

    private fun lzoLiterals(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        if (data.size <= 238) out.write(17 + data.size)
        else {
            val l = data.size - 18
            val z = (l - 1) / 255
            out.write(0); repeat(z) { out.write(0) }; out.write(l - z * 255)
        }
        out.write(data)
        out.write(0x11); out.write(0); out.write(0)
        return out.toByteArray()
    }

    @Test fun lzoDecodesLiteralsMatchesAndLongRuns() {
        // "abcd" + совпадение длины 4 на расстоянии 4 + конец потока
        val src = byteArrayOf(21, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(), 0x6C, 0, 0x11, 0, 0)
        val dst = ByteArray(8)
        assertEquals(8, TileCodecs.lzo(src, dst))
        assertEquals("abcdabcd", String(dst))
        val long = ByteArray(1000) { (it * 7).toByte() }
        val out = ByteArray(1000)
        assertEquals(1000, TileCodecs.lzo(lzoLiterals(long), out))
        assertArrayEquals(long, out)
        // обрыв и ссылка назад за начало - ошибка, а не мусор
        try { TileCodecs.lzo(byteArrayOf(21, 1, 2), ByteArray(4)); fail() } catch (e: IllegalArgumentException) {}
        try { TileCodecs.lzo(byteArrayOf(0x6C, 9), ByteArray(4)); fail() } catch (e: IllegalArgumentException) {}
    }

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    @Test fun bv4ChainReferencesPreviousBlocksAndRawBlocks() {
        val b1 = byteArrayOf(0x40, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(), 4, 0, 0x10, 'x'.code.toByte())
        val b2 = byteArrayOf(0x00, 9, 0)   // совпадение 4 байта на расстоянии 9 - из прошлого блока
        val raw = "RAW".toByteArray()
        val src = "bv41".toByteArray() + le(9) + le(b1.size) + b1 + "bv41".toByteArray() + le(4) + le(b2.size) + b2 +
            "bv4-".toByteArray() + le(raw.size) + raw + "bv4$".toByteArray()
        val dst = ByteArray(16)
        assertEquals(16, TileCodecs.bv4(src, dst))
        assertEquals("abcdabcdxabcdRAW", String(dst))
        try { TileCodecs.bv4("zzzz".toByteArray() + le(1), ByteArray(4)); fail() } catch (e: IllegalArgumentException) {}
    }

    private fun bv4Raw(data: ByteArray) = "bv4-".toByteArray() + le(data.size) + data + "bv4$".toByteArray()

    // ------------------------------------------------------------------ PNG и размеры картинок

    @Test fun pngRoundTripAndHeaders() {
        val w = 5; val h = 3
        val px = ByteArray(w * h * 4) { (it * 37 % 256).toByte() }
        val png = PngIO.encode(w, h, px)
        val img = javax.imageio.ImageIO.read(png.inputStream())
        assertEquals(w, img.width); assertEquals(h, img.height)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            val argb = img.getRGB(x, y)
            val a = px[o + 3].toInt() and 0xFF
            assertEquals(a, argb ushr 24)
            if (a == 255) assertEquals(px[o].toInt() and 0xFF, (argb shr 16) and 0xFF)
        }
        assertEquals(w to h, PngIO.imageSize(png))
        val jpg = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(7, 9, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        assertEquals(7 to 9, PngIO.imageSize(jpg))
        assertEquals(3 to 2, PngIO.imageSize("GIF89a".toByteArray() + byteArrayOf(3, 0, 2, 0)))
        assertNull(PngIO.imageSize("hello world".toByteArray()))
    }

    // ------------------------------------------------------------------ bplist

    @Test fun bplistReadsTypesAndKeyedArchive() {
        val a = Archive()
        val root = a.add(mapOf("\$class" to a.cls("SilicaDocument"), "size" to a.add("{20, 18}"), "n" to 7, "f" to 0.5, "b" to true,
            "s" to a.add("Привет"), "d" to byteArrayOf(1, 2, 3), "arr" to a.array(listOf(a.add("x"), a.add("y")))))
        val k = BPlist.Keyed(BPlist.parse(a.bytes(root)))
        assertEquals("SilicaDocument", k.cls(k.root))
        assertEquals("{20, 18}", k.str(k.root, "size"))
        assertEquals(7.0, k.num(k.root, "n")!!, 0.0)
        assertEquals(0.5, k.num(k.root, "f")!!, 0.0)
        assertTrue(k.bool(k.root, "b"))
        assertEquals("Привет", k.str(k.root, "s"))
        assertArrayEquals(byteArrayOf(1, 2, 3), k.bytes(k.root, "d"))
        assertEquals(listOf("x", "y"), k.arr(k.root, "arr"))
        assertNull(k.get(k.root, "missing"))
        try { BPlist.parse("bplist00 broken".toByteArray()); fail() } catch (e: IllegalArgumentException) {}
    }

    // ------------------------------------------------------------------ Procreate

    private fun floats(vararg f: Float): ByteArray { val b = java.nio.ByteBuffer.allocate(f.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN); f.forEach { b.putFloat(it) }; return b.array() }

    /** Документ 20x18, плитка 16: слой Ink (LZ4, маска 50 %), группа G со скрытым замкнутым слоем Sketch (LZO), пустой Paper. */
    private fun procreateFixture(animation: Boolean = false, orientation: Int = 1): ForeignImport.MapSource {
        val a = Archive()
        fun layer(name: String, uuid: String, extra: Map<String, Any?> = emptyMap()) =
            a.add(mapOf("\$class" to a.cls("SilicaLayer"), "UUID" to a.add(uuid), "name" to a.add(name), "opacity" to 1.0, "blend" to 0, "hidden" to false) + extra)
        val mask = a.add(mapOf("\$class" to a.cls("SilicaLayer"), "UUID" to a.add("MMM"), "hidden" to false))
        val ink = layer("Ink", "AAA", mapOf("opacity" to 0.5, "blend" to 1, "mask" to mask))
        val sketch = layer("Sketch", "BBB", mapOf("hidden" to true, "locked" to true, "blend" to 5, "extendedBlend" to 5))
        val group = a.add(mapOf("\$class" to a.cls("SilicaGroup"), "name" to a.add("G"), "isHidden" to false, "children" to a.array(listOf(sketch))))
        val paper = layer("Paper", "CCC")
        val rootMap = HashMap<String, Any?>(mapOf("\$class" to a.cls("SilicaDocument"), "size" to a.add("{20, 18}"), "tileSize" to 16,
            "orientation" to orientation, "name" to a.add("Untitled Artwork"), "backgroundColor" to a.add(floats(0f, 0f, 1f, 1f)),
            "unwrappedLayers" to a.array(listOf(ink, group, paper))))
        if (animation) { rootMap["featureSet"] = 2; rootMap["animation"] = a.add(mapOf("\$class" to a.cls("ValkyrieDocumentAnimation"), "frameRate" to 12)) }
        val root = a.add(rootMap)
        // плитка (0,0) слоя Ink: один пиксель x=2, нижняя строка (yr=0), премультиплицированный (100,50,0,200)
        val t0 = ByteArray(16 * 16 * 4); t0[2 * 4] = 100; t0[2 * 4 + 1] = 50; t0[2 * 4 + 3] = 200.toByte()
        // плитка маски (0,0): 1 байт на пиксель, 128
        val m0 = ByteArray(16 * 16) { 128.toByte() }
        // плитка (1,1) слоя Sketch обрезана до 4x2: непрозрачный красный
        val t1 = ByteArray(4 * 2 * 4); for (i in 0 until 8) { t1[i * 4] = 255.toByte(); t1[i * 4 + 3] = 255.toByte() }
        return ForeignImport.MapSource(mapOf("Document.archive" to a.bytes(root), "AAA/0~0.lz4" to bv4Raw(t0), "MMM/0~0.lz4" to bv4Raw(m0),
            "BBB/1~1.chunk" to lzoLiterals(t1), "QuickLook/Thumbnail.png" to PngIO.encode(1, 1, ByteArray(4))))
    }

    private fun near(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-3f) }
    }

    private fun pixels(png: ByteArray): java.awt.image.BufferedImage = javax.imageio.ImageIO.read(png.inputStream())

    @Test fun procreateLayersGroupsPropsAndPixels() {
        val src = procreateFixture()
        assertEquals(ForeignImport.Kind.PROCREATE, ForeignImport.detect(src))
        val sink = ForeignImport.MapSink()
        val r = ForeignImport.convert(src, sink, "Котик.procreate")
        val d = r.data
        assertEquals("Procreate", r.app)
        assertEquals("Котик", d.meta.name)   // «Untitled Artwork» заменяется именем файла
        // холст меньше 64 px растягивается, как при импорте PSD; координаты слоёв масштабируются вместе с ним
        val (cw, ch) = ProjectArchive.psdCanvas(20, 18)
        assertEquals(cw, d.meta.width); assertEquals(ch, d.meta.height)
        val sx = cw / 20f; val sy = ch / 18f
        assertEquals(0xFF0000FFL, d.meta.background)
        // снизу вверх: Sketch, группа G, Ink; пустой Paper не создаётся
        assertEquals(listOf("Sketch", "G", "Ink"), d.media.map { it.name })
        val (sketch, g, ink) = d.media
        assertEquals(MediaKind.GROUP, g.kind)
        assertEquals(listOf(sketch.id to g.id), d.regroup)
        // Ink: 1 пиксель на x=2, нижняя строка документа -> y=17; маска 50 % запечена в альфу, цвет без премультипликации
        near(listOf(2f * sx, 17f * sy, 1f * sx, 1f * sy), listOf(ink.x, ink.y, ink.w, ink.h))
        val px = pixels(sink.files[ink.file.removePrefix("assets/")]!!).getRGB(0, 0)
        assertEquals(100, px ushr 24)
        assertEquals(128, (px shr 16) and 0xFF); assertEquals(64, (px shr 8) and 0xFF)
        assertEquals(0.5f, d.layerProps[ink.id]!!.opacity); assertEquals("mul ", d.layerProps[ink.id]!!.blend)
        // Sketch: обрезанная плитка (1,1) - правый верхний угол 4x2; скрыт, замок; Exclusion -> Normal с заметкой
        near(listOf(16f * sx, 0f, 4f * sx, 2f * sy), listOf(sketch.x, sketch.y, sketch.w, sketch.h))
        val sp = d.layerProps[sketch.id]!!
        assertFalse(sp.visible); assertTrue(sp.locked); assertEquals("norm", sp.blend)
        assertTrue(r.notes.any { "Exclusion" in it })
        assertTrue(r.notes.any { "Маски" in it })
        assertEquals(0xFFFF0000.toInt(), pixels(sink.files[sketch.file.removePrefix("assets/")]!!).getRGB(3, 1))
    }

    @Test fun procreateOrientationRotatesPixelsAndCanvas() {
        val g = ProcreateImport.Geo(20, 18, 16, ProcreateImport.rotationOf(4), false, false)   // 90° по часовой
        assertEquals(18, g.w); assertEquals(20, g.h)
        // левый нижний пиксель документа (x=0, yr=0) после поворота на 90° по часовой - левый верхний
        assertEquals(0, g.u(0, 0)); assertEquals(0, g.v(0, 0))
        // правый нижний (x=19, yr=0) -> левый нижний
        assertEquals(0, g.u(19, 0)); assertEquals(19, g.v(19, 0))
        val r = ForeignImport.convert(procreateFixture(orientation = 4), ForeignImport.MapSink(), "x")
        val (rw, rh) = ProjectArchive.psdCanvas(18, 20)
        assertEquals(rw, r.data.meta.width); assertEquals(rh, r.data.meta.height)
        val ink = r.data.media.last()
        near(listOf(0f, 2f * rh / 20f), listOf(ink.x, ink.y))
        val flipped = ProcreateImport.Geo(20, 18, 16, 0, true, false)
        assertEquals(19, flipped.u(0, 0)); assertEquals(17, flipped.v(0, 0))
        // огромный холст уменьшается целым шагом до 4096
        val big = ProcreateImport.Geo(10000, 3000, 256, 0, false, false)
        assertEquals(3, big.k); assertTrue(big.outW <= 4096)
    }

    @Test fun procreateAnimationAssistBecomesFrameByFrameLayer() {
        val sink = ForeignImport.MapSink()
        val r = ForeignImport.convert(procreateFixture(animation = true), sink, "anim.procreate")
        val d = r.data
        assertEquals(12, d.meta.fps)
        assertEquals(1, d.media.size)
        val m = d.media[0]
        // кадры снизу вверх: Paper (пустой, но кадр), G, Ink
        assertEquals(3, m.cells.size)
        assertEquals(listOf(0, 1, 2), m.cells.map { it.start })
        assertEquals(3, d.meta.durationFrames)
        // кадр G: его слой скрыт - кадр прозрачный; кадр Ink - пиксель с прозрачностью слоя 0.5
        val inkFrame = pixels(sink.files[m.cells[2].file.removePrefix("assets/")]!!)
        assertEquals(50, inkFrame.getRGB(2, 17) ushr 24)
        assertEquals(0, pixels(sink.files[m.cells[1].file.removePrefix("assets/")]!!).getRGB(17, 0) ushr 24)
    }

    @Test fun procreateDamagedTileIsSkippedAndBrokenArchiveIsDamaged() {
        val files = procreateFixture().let { s -> s.names().associateWith { s.read(it)!! } }.toMutableMap()
        files["AAA/0~0.lz4"] = "bv41garbage".toByteArray()
        val r = ForeignImport.convert(ForeignImport.MapSource(files), ForeignImport.MapSink(), "x")
        assertEquals(listOf("Sketch", "G"), r.data.media.map { it.name })   // Ink без целых плиток - пустой
        files["Document.archive"] = "bplist00garbage-garbage-garbage-garbage-garbage".toByteArray()
        val dir = File(System.getProperty("java.io.tmpdir"), "vt28_" + System.nanoTime())
        try {
            ForeignImport.toProjectDir(ForeignImport.MapSource(files), dir, "x.procreate"); fail()
        } catch (e: ProjectArchive.ImportException) {
            assertEquals(ProjectArchive.Reason.FOREIGN_DAMAGED, e.reason)
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------------ Lottie

    private val png10x8 = java.util.Base64.getEncoder().encodeToString(PngIO.encode(10, 8, ByteArray(320) { -1 }))

    private val lottie = """
    {"v":"5.7.4","fr":30,"ip":0,"op":60,"w":400,"h":300,"nm":"Demo","assets":[
      {"id":"img_0","w":10,"h":8,"u":"","p":"data:image/png;base64,$png10x8","e":1},
      {"id":"comp_0","layers":[{"ind":1,"ty":4,"nm":"Inner","ks":{"p":{"a":0,"k":[5,5]},"a":{"a":0,"k":[0,0]}},"ip":0,"op":60,"st":0,
        "shapes":[{"ty":"el","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[10,10]}},{"ty":"fl","c":{"a":0,"k":[0,1,0,1]},"o":{"a":0,"k":100}}]}]}
    ],
    "layers":[
      {"ind":1,"ty":4,"nm":"Box","parent":2,"ks":{"p":{"a":1,"k":[{"t":0,"s":[100,100],"o":{"x":[0.4],"y":[0]},"i":{"x":[0.6],"y":[1]}},{"t":30,"s":[200,100]}]},
        "a":{"a":0,"k":[10,10]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}},"ip":0,"op":60,"st":0,
        "shapes":[{"ty":"gr","it":[{"ty":"rc","p":{"a":0,"k":[10,10]},"s":{"a":0,"k":[20,20]},"r":{"a":0,"k":0}},
          {"ty":"rc","p":{"a":0,"k":[10,10]},"s":{"a":0,"k":[8,8]},"r":{"a":0,"k":0}},
          {"ty":"fl","c":{"a":0,"k":[1,0,0,1]},"o":{"a":0,"k":100},"r":2},
          {"ty":"st","c":{"a":0,"k":[0,0,0,1]},"o":{"a":0,"k":50},"w":{"a":0,"k":3}},
          {"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}}]}]},
      {"ind":2,"ty":3,"nm":"Ctrl","ks":{"p":{"a":0,"k":[50,20]},"r":{"a":1,"k":[{"t":0,"s":[0],"h":1},{"t":10,"s":[90]}]}},"ip":0,"op":60,"st":0},
      {"ind":3,"ty":2,"nm":"Pic","refId":"img_0","ks":{"p":{"a":0,"k":[300,200]},"a":{"a":0,"k":[5,4]}},"ip":10,"op":40,"st":0},
      {"ind":4,"ty":0,"nm":"Pre","refId":"comp_0","ks":{"p":{"a":0,"k":[20,250]},"a":{"a":0,"k":[0,0]}},"ip":0,"op":60,"st":0,"w":50,"h":50},
      {"ind":5,"ty":4,"nm":"Arc","ks":{"p":{"a":1,"k":[{"t":0,"s":[0,0],"to":[10,0],"ti":[0,10],"o":{"x":0.3,"y":0.3},"i":{"x":0.7,"y":0.7}},{"t":4,"s":[40,40]}]}},
        "ip":0,"op":60,"st":0,"shapes":[{"ty":"sh","ks":{"a":0,"k":{"c":false,"v":[[0,0],[30,0]],"i":[[0,0],[-5,-5]],"o":[[5,5],[0,0]]}}},
          {"ty":"st","c":{"a":0,"k":[0,0,1,1]},"o":{"a":0,"k":100},"w":{"a":0,"k":2}},{"ty":"tm","s":{"a":0,"k":0},"e":{"a":0,"k":50}}]},
      {"ind":6,"ty":5,"nm":"Title","ks":{"p":{"a":0,"k":[200,50]}},"ip":0,"op":60,"st":0,
        "t":{"d":{"k":[{"s":{"s":24,"f":"Arial","t":"Hello","j":2,"fc":[0,0,1]},"t":0}]}}}
    ]}
    """.trimIndent()

    private fun lottieResult() = ForeignImport.convert(ForeignImport.MapSource(mapOf("demo.json" to lottie.toByteArray())), ForeignImport.MapSink(), "demo.json")

    @Test fun lottieDetectsAndBuildsLayers() {
        assertTrue(LottieImport.looksLike(lottie))
        assertFalse(LottieImport.looksLike("{\"format\":1,\"media\":[],\"layers\":[]}"))
        val r = lottieResult()
        val d = r.data
        assertEquals("Lottie", r.app)
        assertEquals("Demo", d.meta.name)
        assertEquals(listOf(400, 300, 30, 60), listOf(d.meta.width, d.meta.height, d.meta.fps, d.meta.durationFrames))
        assertEquals(0L, d.meta.background)
        // снизу вверх (в Lottie первый слой - верхний)
        assertEquals(listOf("Title", "Arc", "Inner", "Pre", "Pic", "Ctrl", "Box"), d.media.map { it.name })
        val byName = d.media.associateBy { it.name }
        assertEquals(MediaKind.TEXT, byName["Title"]!!.kind)
        assertEquals(MediaKind.GROUP, byName["Pre"]!!.kind)
        assertEquals(MediaKind.NULLOBJ, byName["Ctrl"]!!.kind)
        assertEquals(MediaKind.IMAGE, byName["Pic"]!!.kind)
        assertTrue(byName["Box"]!!.vector)
        assertEquals(listOf(byName["Inner"]!!.id to byName["Pre"]!!.id), d.regroup)
        assertEquals(mapOf(byName["Box"]!!.id to byName["Ctrl"]!!.id), d.links)
        assertEquals(com.vibetoon.domain.project.Clip(10, 40), d.clips[byName["Pic"]!!.id])
        assertTrue(r.notes.any { "Trim Paths" in it })
        assertTrue(r.notes.any { "Шрифты" in it })
    }

    @Test fun lottieShapesHolesStrokesAndRestPose() {
        val d = lottieResult().data
        val box = d.media.first { it.name == "Box" }
        // покой Box: родитель Ctrl в (50,20), якорь (10,10), позиция в начале (100,100) -> рамка 140..160
        assertEquals(150f to 120f, d.pivots[box.id])
        // снизу вверх: обводка (ниже в списке Lottie), затем заливка с дыркой (even-odd -> вычитание)
        assertEquals(3, box.vectors.size)
        val fill = box.vectors.last()
        assertEquals(VecKind.BOOL, fill.kind)
        assertEquals(0xFFFF0000L, fill.fill)
        assertEquals(listOf(140f, 110f, 160f, 110f, 160f, 130f, 140f, 130f), fill.parts[0].pts)
        val stroke = box.vectors[0]
        assertEquals(0x80000000L, stroke.stroke)
        assertEquals(3f, stroke.width)
        // открытый контур с ручками -> POLY с ручками (вход/выход относительно узла)
        val arc = d.media.first { it.name == "Arc" }.vectors.single()
        assertEquals(VecKind.POLY, arc.kind)
        assertEquals(listOf(0f, 0f, 5f, 5f, -5f, -5f, 0f, 0f), arc.handles)
        // картинка: рамка = позиция - якорь, опорная точка - позиция
        val pic = d.media.first { it.name == "Pic" }
        assertEquals(listOf(295f, 196f, 10f, 8f), listOf(pic.x, pic.y, pic.w, pic.h))
        assertEquals(10 to 8, pic.srcWidth to pic.srcHeight)
        // вложенная композиция: точка эллипса (0,-5) -> (20,250) + (5,5) + (0,-5)
        val inner = d.media.first { it.name == "Inner" }
        assertEquals(25f, inner.vectors.single().pts[0], 1e-3f); assertEquals(250f, inner.vectors.single().pts[1], 1e-3f)
        // текст: по центру (j=2), синий, рамку измерит редактор
        val title = d.media.first { it.name == "Title" }
        assertEquals("Hello", title.text); assertEquals(1, title.textAlign); assertEquals(0xFF0000FFL, title.textColor)
        assertEquals(0f, title.w); assertEquals(200f to 26f, title.x to title.y)
    }

    @Test fun lottieKeysKeepCurvesHoldsAndBakeArcs() {
        val d = lottieResult().data
        val box = d.media.first { it.name == "Box" }.id
        val ctrl = d.media.first { it.name == "Ctrl" }.id
        val arc = d.media.first { it.name == "Arc" }.id
        val bk = d.tracks[box]!!.keys
        assertEquals(listOf(0, 30), bk.map { it.frame })
        assertEquals(listOf(0.4f, 0f, 0.6f, 1f), bk[0].curve)
        assertEquals(100f, bk[1].dx, 1e-4f)
        val ck = d.tracks[ctrl]!!.keys
        assertEquals(EasingType.STEP, ck[0].easing)
        assertEquals((PI / 2).toFloat(), ck[1].rotation, 1e-5f)
        // дуга позиции - ключ на каждом кадре
        assertEquals(listOf(0, 1, 2, 3, 4), d.tracks[arc]!!.keys.map { it.frame })
    }

    /** Мир точки покоя по модели VibeToon (опорная точка, ключи, привязка к родителю) против формулы Lottie. */
    @Test fun lottieMotionMatchesSource() {
        val d = lottieResult().data
        val anim = Animation(ProjectSettings(d.meta.fps, d.meta.durationFrames), d.tracks)
        fun local(id: Int, f: Int): Affine2D {
            val t = anim.evaluate(id, f, d.base[id])
            val (px, py) = d.pivots[id]!!
            return Affine2D.translate(px + t.dx, py + t.dy) * Affine2D.rotate(t.rotation) * Affine2D.scale(t.scaleX, t.scaleY) * Affine2D.translate(-px, -py)
        }
        val box = d.media.first { it.name == "Box" }.id
        val ctrl = d.media.first { it.name == "Ctrl" }.id
        val arc = d.media.first { it.name == "Arc" }.id
        val root = com.vibetoon.domain.project.MiniJson.parse(lottie) as Map<*, *>
        val layers = root["layers"] as List<*>
        val boxTr = LottieImport.tr((layers[0] as Map<*, *>)["ks"])
        val ctrlTr = LottieImport.tr((layers[1] as Map<*, *>)["ks"])
        val arcTr = LottieImport.tr((layers[4] as Map<*, *>)["ks"])
        fun lot(tr: LottieImport.Tr, t: Double, x: Double, y: Double): Pair<Double, Double> {
            val p = tr.pos(t); val a = tr.a.at(t); val s = tr.s.at(t); val r = tr.r.at(t)[0] * PI / 180
            val lx = (x - a[0]) * s[0] / 100; val ly = (y - a[1]) * s[1] / 100
            return (p[0] + kotlin.math.cos(r) * lx - kotlin.math.sin(r) * ly) to (p[1] + kotlin.math.sin(r) * lx + kotlin.math.cos(r) * ly)
        }
        // угол квадрата Box в локальных (0,0): покой (140,110)
        for (f in listOf(0, 5, 9, 10, 12, 15, 22, 30, 45)) {
            val w = local(ctrl, f) * local(box, f)
            val ours = w.applyX(140f, 110f) to w.applyY(140f, 110f)
            val (bx, by) = lot(boxTr, f.toDouble(), 0.0, 0.0)
            val (ex, ey) = lot(ctrlTr, f.toDouble(), bx, by)
            assertEquals("x@$f", ex, ours.first.toDouble(), 0.6)
            assertEquals("y@$f", ey, ours.second.toDouble(), 0.6)
        }
        for (f in 0..6) {
            val w = local(arc, f)
            val (ex, ey) = lot(arcTr, f.toDouble(), 0.0, 0.0)
            val rest = d.pivots[arc]!!
            assertEquals(ex, w.applyX(rest.first, rest.second).toDouble(), 1e-3)
            assertEquals(ey, w.applyY(rest.first, rest.second).toDouble(), 1e-3)
        }
    }

    @Test fun lottieEaseAndAnimEvaluation() {
        assertEquals(0.5, LottieImport.ease(0.0, 0.0, 1.0, 1.0, 0.5), 1e-9)
        val e = LottieImport.ease(0.42, 0.0, 0.58, 1.0, 0.5)
        assertEquals(0.5, e, 1e-3)
        assertTrue(LottieImport.ease(0.42, 0.0, 1.0, 1.0, 0.25) < 0.25)
        val a = LottieImport.anim(mapOf("a" to 1, "k" to listOf(mapOf("t" to 0.0, "s" to listOf(0.0), "e" to listOf(10.0)), mapOf("t" to 10.0))), doubleArrayOf(0.0))
        assertEquals(5.0, a.at(5.0)[0], 1e-6)   // старый формат с "e"
        assertEquals(10.0, a.at(20.0)[0], 1e-6)
        val split = LottieImport.tr(mapOf("p" to mapOf("s" to true, "x" to mapOf("a" to 0, "k" to 7.0), "y" to mapOf("a" to 0, "k" to 9.0))))
        assertArrayEquals(doubleArrayOf(7.0, 9.0), split.pos(0.0), 0.0)
    }

    @Test fun dotLottieReadsManifestAndImages() {
        val anim = """{"v":"5","fr":24,"ip":0,"op":24,"w":64,"h":64,"assets":[{"id":"i","w":4,"h":4,"u":"/images/","p":"a.png","e":0}],
            "layers":[{"ind":1,"ty":2,"refId":"i","nm":"P","ks":{"p":{"a":0,"k":[32,32]},"a":{"a":0,"k":[2,2]}},"ip":0,"op":24,"st":0}]}"""
        val src = ForeignImport.MapSource(mapOf("manifest.json" to """{"animations":[{"id":"main"}]}""".toByteArray(),
            "animations/main.json" to anim.toByteArray(), "images/a.png" to PngIO.encode(4, 4, ByteArray(64))))
        assertEquals(ForeignImport.Kind.DOTLOTTIE, ForeignImport.detect(src))
        val sink = ForeignImport.MapSink()
        val r = ForeignImport.convert(src, sink, "x.lottie")
        assertEquals(24, r.data.meta.fps)
        val p = r.data.media.single()
        assertEquals(listOf(30f, 30f, 4f, 4f), listOf(p.x, p.y, p.w, p.h))
        assertTrue(sink.files.keys.single().endsWith(".png"))
    }

    // ------------------------------------------------------------------ ORA, Krita, кадры, отказы

    @Test fun openRasterStackAndKritaMerged() {
        val stack = """<?xml version="1.0"?><image w="40" h="30"><stack>
            <layer name="Top" src="data/1.png" x="5" y="6" opacity="0.5" composite-op="svg:multiply"/>
            <stack name="Grp" visibility="hidden"><layer name="In" src="data/2.png" composite-op="svg:hue"/></stack>
            <layer name="Bg" src="data/3.png" edit-locked="true"/></stack></image>"""
        val src = ForeignImport.MapSource(mapOf("mimetype" to "image/openraster".toByteArray(), "stack.xml" to stack.toByteArray(),
            "data/1.png" to PngIO.encode(3, 2, ByteArray(24)), "data/2.png" to PngIO.encode(1, 1, ByteArray(4)), "data/3.png" to PngIO.encode(40, 30, ByteArray(4800))))
        assertEquals(ForeignImport.Kind.ORA, ForeignImport.detect(src))
        val r = ForeignImport.convert(src, ForeignImport.MapSink(), "pic.ora")
        val d = r.data
        assertEquals(listOf("Bg", "In", "Grp", "Top"), d.media.map { it.name })
        val (bg, inn, grp, top) = d.media
        assertTrue(d.layerProps[bg.id]!!.locked)
        assertFalse(d.layerProps[grp.id]!!.visible)
        assertEquals(listOf(inn.id to grp.id), d.regroup)
        val (ow, oh) = ProjectArchive.psdCanvas(40, 30)
        val ox = ow / 40f; val oy = oh / 30f
        near(listOf(5f * ox, 6f * oy, 3f * ox, 2f * oy), listOf(top.x, top.y, top.w, top.h))
        assertEquals("mul ", d.layerProps[top.id]!!.blend); assertEquals(0.5f, d.layerProps[top.id]!!.opacity)
        assertTrue(r.notes.any { "hue" in it })
        assertEquals("mul ", OraImport.blendOf("svg:multiply")); assertNull(OraImport.blendOf("svg:hue"))

        val kra = ForeignImport.MapSource(mapOf("mimetype" to "application/x-krita".toByteArray(), "maindoc.xml" to "<DOC><IMAGE name=\"Sky\"/></DOC>".toByteArray(),
            "mergedimage.png" to PngIO.encode(8, 6, ByteArray(192))))
        val k = ForeignImport.convert(kra, ForeignImport.MapSink(), "a.kra")
        assertEquals("Krita", k.app); assertEquals("Sky", k.data.meta.name)
        assertEquals(1, k.data.media.size); assertTrue(k.notes.single().contains("PSD"))
    }

    @Test fun imageSequenceZipBecomesFrameByFrameInNaturalOrder() {
        val names = listOf("frame10.png", "frame2.png", "frame1.png")
        val src = ForeignImport.MapSource(names.associateWith { PngIO.encode(6, 4, ByteArray(96)) } + ("readme.txt" to "hi".toByteArray()))
        assertEquals(ForeignImport.Kind.SEQUENCE, ForeignImport.detect(src))
        val sink = ForeignImport.MapSink()
        val r = ForeignImport.convert(src, sink, "walk.zip")
        val m = r.data.media.single()
        assertEquals(3, m.cells.size)
        assertEquals(listOf("frame1.png", "frame2.png", "frame10.png"), names.sortedWith(SequenceImport.naturalOrder))
        assertEquals(3, r.data.meta.durationFrames)
        assertEquals("walk", r.data.meta.name)
    }

    @Test fun toonSquidAndUnknownGiveClearMessages() {
        val ts = ForeignImport.MapSource(mapOf("document.tsdata" to ByteArray(10), "x.bin" to ByteArray(3)))
        assertEquals(ForeignImport.Kind.TOONSQUID, ForeignImport.detect(ts))
        try { ForeignImport.convert(ts, ForeignImport.MapSink(), "a.tsproj"); fail() } catch (e: ForeignImport.Unsupported) { assertEquals(ForeignImport.MSG_TOONSQUID, e.message) }
        val unk = ForeignImport.MapSource(mapOf("a.bin" to ByteArray(3), "b.bin" to ByteArray(3)))
        try { ForeignImport.convert(unk, ForeignImport.MapSink(), "a.tsproj"); fail() } catch (e: ForeignImport.Unsupported) { assertEquals(ForeignImport.MSG_TOONSQUID, e.message) }
        try { ForeignImport.convert(unk, ForeignImport.MapSink(), "a.zip"); fail() } catch (e: ForeignImport.Unsupported) { assertEquals(ForeignImport.MSG_UNKNOWN, e.message) }
        assertEquals(ForeignImport.MSG_TOONSQUID, ForeignImport.unknownMessage("Мой.TSPROJ"))
        assertNull(ForeignImport.unknownMessage("a.procreate"))
        assertEquals(ForeignImport.MSG_ROUGH, ForeignImport.unknownMessage("walk.roughanimator"))
        assertEquals(ForeignImport.MSG_UNKNOWN, ForeignImport.unknownMessage("a.bin"))
        val dir = File(System.getProperty("java.io.tmpdir"), "vt28u_" + System.nanoTime())
        try { ForeignImport.toProjectDir(unk, dir, "a.zip"); fail() } catch (e: ProjectArchive.ImportException) {
            assertEquals(ProjectArchive.Reason.NOT_PROJECT, e.reason); assertEquals(ForeignImport.MSG_UNKNOWN, e.text)
        } finally { dir.deleteRecursively() }
    }

    /** Итог - обычный проект: project.json читается, общий импорт не считает картинки векторных слоёв потерянными. */
    @Test fun convertedProjectPassesCommonImport() {
        val dir = File(System.getProperty("java.io.tmpdir"), "vt28p_" + System.nanoTime())
        try {
            val r = ForeignImport.toProjectDir(ForeignImport.MapSource(mapOf("demo.json" to lottie.toByteArray())), dir, "demo.json")
            val report = ProjectArchive.prepare(dir, "new1", 5L, listOf("Demo"))
            assertEquals("Demo (2)", report.data.meta.name)
            assertTrue(report.missing.toString(), report.missing.isEmpty())
            assertEquals(r.data.media.size, report.data.media.size)
            assertEquals(r.data.tracks.keys, report.data.tracks.keys)
            assertEquals(r.data.media.first { it.name == "Box" }.vectors, report.data.media.first { it.name == "Box" }.vectors)
            assertTrue(File(dir, "assets").listFiles()!!.any { it.name.endsWith(".png") })   // картинка слоя Pic
            val pc = File(dir.parentFile, dir.name + "pc")
            try {
                ForeignImport.toProjectDir(procreateFixture(), pc, "art.procreate")
                val rp = ProjectArchive.prepare(pc, "new2", 5L, emptyList())
                assertTrue(rp.missing.isEmpty()); assertEquals(3, rp.data.media.size)
                assertEquals(ProjectJson.decode(File(pc, "project.json").readText()).getOrThrow().regroup, rp.data.regroup)
            } finally { pc.deleteRecursively() }
        } finally { dir.deleteRecursively() }
    }

    @Test fun detectionPrefersVibeToonAndKnownApps() {
        assertEquals(ForeignImport.Kind.UNKNOWN, ForeignImport.detect(ForeignImport.MapSource(emptyMap())))
        val lottieZip = ForeignImport.MapSource(mapOf("anim.json" to lottie.toByteArray()))
        assertEquals(ForeignImport.Kind.LOTTIE, ForeignImport.detect(lottieZip))
        assertNotNull(ProcreateImport.BLEND[17]); assertEquals("sLit", ProcreateImport.BLEND[17]!!.second)
        assertNull(ProcreateImport.BLEND[12]!!.second)
        assertEquals(LayerTransform(), LayerTransform())
        assertTrue(MediaItem(1, MediaKind.IMAGE, "a", "f").isVisual)
        assertEquals(ProjectData::class, ProjectData(com.vibetoon.domain.project.ProjectMeta("a", "b", 2, 2))::class)
        assertTrue(abs(0f) < 1f)
    }
}
