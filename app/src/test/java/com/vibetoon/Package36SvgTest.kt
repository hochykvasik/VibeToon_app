package com.vibetoon

import com.vibetoon.domain.project.ProjectArchive
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.foreign.ForeignImport
import com.vibetoon.domain.vector.SvgImport
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorBezier
import com.vibetoon.domain.vector.VectorBool
import com.vibetoon.domain.vector.VectorShape
import com.vibetoon.domain.vector.VectorWarp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Спринт 36: импорт SVG - геометрия, кривые, трансформации, стили, заметки о неподдерживаемом, проект. */
class Package36SvgTest {
    private fun svg(body: String, attrs: String = "width=\"200\" height=\"100\"") =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" $attrs>$body</svg>"

    private fun all(r: SvgImport.Result) = r.layers.flatMap { it.shapes }

    private fun near(e: Float, a: Float, d: Float = 0.01f, m: String = "") = assertTrue("$m: expected $e got $a", kotlin.math.abs(e - a) <= d)

    @Test fun detectsSvgByContent() {
        assertTrue(SvgImport.looksLike(svg("")))
        assertTrue(SvgImport.looksLike("\uFEFF  <!-- c --><!DOCTYPE svg><svg viewBox='0 0 1 1'/>"))
        assertTrue(SvgImport.looksLike("<svg:svg xmlns:svg='http://www.w3.org/2000/svg'></svg:svg>"))
        assertFalse(SvgImport.looksLike("{\"v\":\"5.7\"}"))
        assertFalse(SvgImport.looksLike("<html><body></body></html>"))
        assertEquals(ProjectArchive.Kind.XML, ProjectArchive.detect(svg("").toByteArray()))
        assertEquals(ProjectArchive.Kind.XML, ProjectArchive.detect(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), ' '.code.toByte(), '<'.code.toByte())))
        assertEquals(ProjectArchive.Kind.UNKNOWN, ProjectArchive.detect("hello".toByteArray()))
        assertEquals(ProjectArchive.Kind.JSON, ProjectArchive.detect("{}".toByteArray()))
    }

    @Test fun basicShapesKeepGeometryAndStyle() {
        val r = SvgImport.parse(svg("""
            <rect x="10" y="20" width="30" height="40" fill="#ff0000"/>
            <circle cx="100" cy="50" r="10" fill="none" stroke="blue" stroke-width="4"/>
            <line x1="0" y1="0" x2="50" y2="50" stroke="#0f0"/>
            <polygon points="0,0 10,0 10,10" fill="rgb(0, 0, 255)" fill-opacity="0.5"/>
            <polyline points="1 2 3 4 5 6" stroke="black" fill="none"/>
            <ellipse cx="50" cy="50" rx="20" ry="10" fill="yellow" stroke="black" stroke-width="2"/>
        """), "Test")
        assertEquals(200f, r.width); assertEquals(100f, r.height)
        val s = all(r)
        assertEquals(6, s.size)
        val rect = s[0]
        assertEquals(VecKind.POLYGON, rect.kind)
        assertEquals(listOf(10f, 20f, 40f, 20f, 40f, 60f, 10f, 60f), rect.pts)
        assertEquals(0xFFFF0000L, rect.fill); assertEquals(0L, rect.stroke)
        val circle = s[1]
        assertEquals(VecKind.POLYGON, circle.kind)
        assertEquals(0xFF0000FFL, circle.stroke); assertEquals(0L, circle.fill); assertEquals(4f, circle.width)
        assertTrue("эллипс - кривые Безье", VectorBezier.hasHandles(circle))
        assertEquals(listOf(100f, 40f, 110f, 50f, 100f, 60f, 90f, 50f), circle.pts)
        assertEquals(VecKind.POLY, s[2].kind); assertEquals(0xFF00FF00L, s[2].stroke); assertEquals(1f, s[2].width)
        assertEquals(0x800000FFL, s[3].fill)
        assertEquals(VecKind.POLY, s[4].kind); assertEquals(6, s[4].pts.size)
        val el = s[5]
        assertEquals(0xFFFFFF00L, el.fill); assertEquals(0xFF000000L, el.stroke); assertEquals(2f, el.width)
    }

    @Test fun pathCommandsAbsoluteRelativeCurvesAndArcs() {
        val subs = SvgImport.pathData("M10 10 h20 v20 H10 z m40 0 l10 0 0 10 Z")
        assertEquals(2, subs.size)
        assertEquals(listOf(10.0, 10.0, 30.0, 10.0, 30.0, 30.0, 10.0, 30.0), subs[0].pts)
        assertTrue(subs[0].closed)
        assertEquals(listOf(50.0, 10.0, 60.0, 10.0, 60.0, 20.0), subs[1].pts)
        // кубическая кривая: ручки относительно узлов
        val c = SvgImport.pathData("M0,0 C10,0 20,10 20,20")[0]
        assertEquals(listOf(0.0, 0.0, 20.0, 20.0), c.pts)
        assertEquals(10.0, c.h[2], 1e-9); assertEquals(0.0, c.h[3], 1e-9)        // выход узла 0
        assertEquals(0.0, c.h[4], 1e-9); assertEquals(-10.0, c.h[5], 1e-9)      // вход узла 1
        // S отражает предыдущую ручку, Q/T переводятся в кубические
        val s = SvgImport.pathData("M0 0 C0 10 10 10 10 0 S20 -10 20 0")[0]
        assertEquals(3, s.nodes)
        assertEquals(0.0, s.h[6], 1e-9); assertEquals(-10.0, s.h[7], 1e-9)     // отражённая ручка выхода узла 1
        val q = SvgImport.pathData("M0 0 Q10 10 20 0 T40 0")[0]
        assertEquals(3, q.nodes)
        assertEquals(20.0 / 3, q.h[2], 1e-9); assertEquals(20.0 / 3, q.h[3], 1e-9)
        // компактная запись чисел и экспонента
        val compact = SvgImport.pathData("M.5.5l1e1-2.5.5.5")[0]
        assertEquals(listOf(0.5, 0.5, 10.5, -2.0, 11.0, -1.5), compact.pts)
        // дуга-полуокружность: конец точный, середина на окружности
        val a = SvgImport.pathData("M0 0 A10 10 0 0 1 20 0")[0]
        assertEquals(20.0, a.pts[a.pts.size - 2], 1e-6); assertEquals(0.0, a.pts.last(), 1e-6)
        assertEquals(3, a.nodes)
        assertEquals(10.0, a.pts[2], 1e-6); assertEquals(-10.0, a.pts[3], 1e-6)
        // мусор посреди данных: то, что до него, остаётся
        val bad = SvgImport.pathData("M0 0 L10 10 L20 x30")
        assertEquals(1, bad.size); assertEquals(2, bad[0].nodes)
        assertTrue(SvgImport.pathData("").isEmpty())
        assertTrue(SvgImport.pathData("Z Z").isEmpty())
    }

    @Test fun transformsAndViewBoxAreBakedIntoCanvasPixels() {
        val r = SvgImport.parse(svg("""
            <g transform="translate(10 20)">
              <rect width="10" height="10" transform="scale(2)" fill="red"/>
            </g>
            <rect x="0" y="0" width="10" height="10" transform="rotate(90 5 5)" fill="red"/>
            <rect x="0" y="0" width="10" height="10" transform="matrix(1 0 0 1 100 0) skewX(45)" fill="red" stroke="red" stroke-width="1"/>
        """, "width=\"100mm\" height=\"50mm\" viewBox=\"0 0 200 100\""))
        near(377.95f, r.width, 0.05f); near(188.98f, r.height, 0.05f)
        val k = r.width / 200f
        val s = all(r)
        near(10f * k, s[0].pts[0], 0.01f, "translate x"); near(20f * k, s[0].pts[1], 0.01f)
        near(30f * k, s[0].pts[2], 0.01f, "scale")
        // поворот на 90° вокруг центра: (0,0) -> (10,0)
        near(10f * k, s[1].pts[0], 0.01f); near(0f, s[1].pts[1], 0.01f)
        // skewX(45): нижний левый угол сдвинут на высоту
        near((100f + 10f) * k, s[2].pts[6], 0.01f); near(10f * k, s[2].pts[7], 0.01f)
        near(1f * k, s[2].width, 0.01f, "толщина обводки масштабируется")
    }

    @Test fun preserveAspectRatioAndPercentSizes() {
        val r = SvgImport.parse(svg("<rect width=\"100\" height=\"100\" fill=\"red\"/>", "viewBox=\"0 0 100 100\" width=\"200\" height=\"100\""))
        val p = all(r)[0].pts
        assertEquals(50f, p[0]); assertEquals(0f, p[1]); assertEquals(150f, p[2])   // xMidYMid meet
        val n = SvgImport.parse(svg("<rect width=\"100\" height=\"100\" fill=\"red\"/>", "viewBox=\"0 0 100 100\" width=\"200\" height=\"100\" preserveAspectRatio=\"none\""))
        assertEquals(200f, all(n)[0].pts[2]); assertEquals(100f, all(n)[0].pts[5])
        val pc = SvgImport.parse(svg("<rect width=\"50%\" height=\"10\" fill=\"red\"/>", "viewBox=\"0 0 300 150\" width=\"100%\""))
        assertEquals(300f, pc.width); assertEquals(150f, pc.height)
        assertEquals(150f, all(pc)[0].pts[2])
        // без размеров - рамка по содержимому
        val none = SvgImport.parse(svg("<rect x=\"10\" y=\"10\" width=\"40\" height=\"20\" fill=\"red\"/>", ""))
        assertEquals(50f, none.width); assertEquals(30f, none.height)
    }

    @Test fun stylesInheritanceCssAndColors() {
        val r = SvgImport.parse(svg("""
            <style>.a { fill: #123456; } #b { stroke: red; stroke-width: 3 } rect.c { fill-opacity: .5 }</style>
            <g fill="green" stroke-width="5" opacity="0.5">
              <rect width="1" height="1"/>
              <rect class="a" width="1" height="1"/>
              <rect id="b" class="a c" width="1" height="1" style="fill:hsl(0, 100%, 50%)"/>
              <rect width="1" height="1" fill="currentColor" color="#abc"/>
              <rect width="1" height="1" fill="cornflowerblue" fill-opacity="50%"/>
            </g>
        """))
        val s = all(r)
        assertEquals(5, s.size)
        assertEquals(0x80008000L, s[0].fill)
        assertEquals(0x80123456L, s[1].fill)
        assertEquals(0x40FF0000L, s[2].fill)           // style важнее CSS; fill-opacity .5 * opacity .5
        assertEquals(0x80FF0000L, s[2].stroke); assertEquals(3f, s[2].width)
        assertEquals(0x80AABBCCL, s[3].fill)
        assertEquals(0x406495EDL, s[4].fill)
        assertEquals(0xFF112233L, SvgImport.parseColor("#123"))
        assertEquals(0x44112233L, SvgImport.parseColor("#11223344"))
        assertEquals(0L, SvgImport.parseColor("none"))
        assertEquals(null, SvgImport.parseColor("nonsense"))
        assertEquals(0x80FF0000L, SvgImport.parseColor("rgba(255,0,0,0.5)"))
        assertEquals(0xFF00FF00L, SvgImport.parseColor("rgb(0% 100% 0%)"))
    }

    @Test fun compoundPathHolesBecomeBooleanSubtraction() {
        // кольцо: внешний контур по часовой, внутренний - против (nonzero) -> дыра
        val ring = "M0 0 H100 V100 H0 Z M25 25 V75 H75 V25 Z"
        val r = SvgImport.parse(svg("<path d=\"$ring\" fill=\"black\"/>"))
        val s = all(r)
        assertEquals(1, s.size)
        assertEquals(VecKind.BOOL, s[0].kind); assertEquals(VectorBool.SUBTRACT, s[0].op)
        assertEquals(2, VectorBool.parts(s[0]).size)
        // тот же обход внутреннего контура при nonzero - не дыра; при evenodd - дыра
        val same = "M0 0 H100 V100 H0 Z M25 25 H75 V75 H25 Z"
        assertEquals(VecKind.POLYGON, all(SvgImport.parse(svg("<path d=\"$same\" fill=\"black\"/>")))[0].kind)
        val eo = all(SvgImport.parse(svg("<path d=\"$same\" fill=\"black\" fill-rule=\"evenodd\"/>")))
        assertEquals(VecKind.BOOL, eo[0].kind)
        // два отдельных контура - две фигуры; обводка составного - каждый контур
        val two = all(SvgImport.parse(svg("<path d=\"M0 0 H10 V10 Z M50 50 H60 V60 Z\" fill=\"red\" stroke=\"black\"/>")))
        assertEquals(4, two.size)
        assertEquals(2, two.count { it.fill != 0L }); assertEquals(2, two.count { it.stroke != 0L })
    }

    @Test fun groupsBecomeLayersUseAndSymbolsAreResolved() {
        val r = SvgImport.parse(svg("""
            <defs><rect id="r" width="10" height="10"/><symbol id="s"><circle cx="5" cy="5" r="5"/></symbol></defs>
            <rect width="5" height="5" fill="red"/>
            <g id="head" inkscape:label="Голова" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape"><use href="#r" x="20" fill="blue"/></g>
            <g id="body"><use xlink:href="#s" x="40" xmlns:xlink="http://www.w3.org/1999/xlink"/><use href="#missing"/></g>
        """), "Файл")
        assertEquals(listOf("Файл", "Голова", "body"), r.layers.map { it.name })
        val head = r.layers[1].shapes[0]
        assertEquals(20f, head.pts[0]); assertEquals(0xFF0000FFL, head.fill)
        assertEquals(45f, r.layers[2].shapes[0].pts[0])
        // ссылка на себя не зацикливается
        val loop = SvgImport.parse(svg("<g id=\"a\"><rect width=\"1\" height=\"1\"/><use href=\"#a\"/></g>"))
        assertTrue(loop.shapeCount in 1..10)
    }

    @Test fun unsupportedFeaturesProduceNotes() {
        val r = SvgImport.parse(svg("""
            <defs><linearGradient id="g"><stop offset="0" stop-color="#000"/><stop offset="1" stop-color="#fff"/></linearGradient>
            <filter id="f"><feGaussianBlur stdDeviation="2"/></filter><clipPath id="c"><rect width="1" height="1"/></clipPath></defs>
            <rect width="10" height="10" fill="url(#g)"/>
            <rect width="10" height="10" fill="red" filter="url(#f)" clip-path="url(#c)"/>
            <text x="0" y="10">Привет</text>
            <image href="a.png" width="10" height="10"/>
            <path d="M0 0 L10 10" stroke="black" stroke-dasharray="2 2"/>
            <circle r="5" fill="red"><animate attributeName="r" from="5" to="10" dur="1s"/></circle>
            <style>g > rect { fill: red }</style>
        """))
        for (n in listOf(SvgImport.N_GRADIENT, SvgImport.N_FILTER, SvgImport.N_CLIP, SvgImport.N_TEXT, SvgImport.N_IMAGE, SvgImport.N_DASH, SvgImport.N_ANIM, SvgImport.N_CSS, SvgImport.N_CAP))
            assertTrue("заметка: $n", n in r.notes)
        assertEquals(0xFF808080L, all(r)[0].fill)   // средний цвет градиента
        assertEquals(r.notes.size, r.notes.toSet().size)
    }

    @Test fun errorsAreClear() {
        fun msg(text: String): String = try { SvgImport.parse(text); fail("ожидалась ошибка"); "" } catch (e: SvgImport.SvgException) { e.message!! }
        assertEquals(SvgImport.MSG_DAMAGED, msg("<svg><rect width=\"1\""))
        assertEquals(SvgImport.MSG_NOT_SVG, msg("<html></html>"))
        assertEquals(SvgImport.MSG_EMPTY, msg(svg("<text>t</text>")))
        assertEquals(SvgImport.MSG_EMPTY, msg(svg("<rect width=\"0\" height=\"10\"/><rect width=\"10\" height=\"10\" fill=\"none\"/>")))
        assertEquals(SvgImport.MSG_EMPTY, msg(svg("<rect width=\"10\" height=\"10\" display=\"none\"/>")))
        // внешние сущности не раскрываются (XXE)
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\" fill=\"red\"/><text>&x;</text></svg>"
        val r = SvgImport.parse(xxe)
        assertTrue(SvgImport.N_ENTITY in r.notes)
        assertEquals(1, r.shapeCount)
    }

    @Test fun hugeDocumentsAreCapped() {
        val sb = StringBuilder()
        repeat(SvgImport.MAX_SHAPES + 50) { sb.append("<rect x=\"$it\" width=\"1\" height=\"1\" fill=\"red\"/>") }
        val r = SvgImport.parse(svg(sb.toString()))
        assertEquals(SvgImport.MAX_SHAPES, r.shapeCount)
        assertTrue(r.notes.any { it.startsWith("SVG очень сложный") })
        val groups = StringBuilder()
        repeat(SvgImport.MAX_LAYERS + 3) { groups.append("<g><rect width=\"1\" height=\"1\" fill=\"red\"/></g>") }
        val g = SvgImport.parse(svg(groups.toString()))
        assertEquals(1, g.layers.size); assertTrue(SvgImport.N_LAYERS in g.notes)
    }

    @Test fun fitCentersAndScalesIntoCanvas() {
        val r = SvgImport.parse(svg("<rect width=\"200\" height=\"100\" fill=\"red\" stroke=\"black\" stroke-width=\"10\"/>"))
        val big = SvgImport.fit(r, 100, 100)[0].shapes[0]
        assertEquals(listOf(0f, 25f, 100f, 25f, 100f, 75f, 0f, 75f), big.pts)
        assertEquals(5f, big.width)
        val small = SvgImport.fit(r, 1000, 1000)[0].shapes[0]
        assertEquals(400f, small.pts[0]); assertEquals(450f, small.pts[1]); assertEquals(10f, small.width)
        assertEquals(1024 to 512, SvgImport.canvasFor(r))
        val scaled = SvgImport.scaleTo(r, 1024, 512)[0].shapes[0]
        assertEquals(1024f, scaled.pts[2]); assertEquals(51.2f, scaled.width, 0.001f)
    }

    @Test fun foreignImportBuildsVectorProjectThatRoundTrips() {
        val text = svg("<g id=\"bg\"><rect width=\"200\" height=\"100\" fill=\"#eee\"/></g><g id=\"fg\"><circle cx=\"100\" cy=\"50\" r=\"30\" fill=\"red\"/></g><text>t</text>")
        val src = ForeignImport.MapSource(mapOf("drawing.svg" to text.toByteArray()))
        assertEquals(ForeignImport.Kind.SVG, ForeignImport.detect(src))
        val sink = ForeignImport.MapSink()
        val res = ForeignImport.convert(src, sink, "drawing.svg")
        assertEquals("SVG", res.app)
        assertTrue(SvgImport.N_TEXT in res.notes)
        val d = res.data
        assertEquals(1024, d.meta.width); assertEquals(512, d.meta.height)
        assertEquals("drawing", d.meta.name)
        assertEquals(listOf("bg", "fg"), d.media.map { it.name })
        assertTrue(d.media.all { it.vector && it.vectors.isNotEmpty() && it.w == 1024f && it.h == 512f })
        val back = ProjectJson.decode(ProjectJson.encode(d)).getOrThrow()
        assertEquals(d.media.map { it.vectors }, back.media.map { it.vectors })
        // не SVG - понятное сообщение; ZIP с одним SVG - тоже SVG
        val zip = ForeignImport.MapSource(mapOf("pack/drawing.svg" to text.toByteArray(), "readme.txt" to "x".toByteArray()))
        assertEquals(ForeignImport.Kind.SVG, ForeignImport.detect(zip))
        val notSvg = ForeignImport.MapSource(mapOf("a.svg" to "<html/>".toByteArray()))
        assertEquals(ForeignImport.Kind.UNKNOWN, ForeignImport.detect(notSvg))
        try { ForeignImport.convert(ForeignImport.MapSource(mapOf("e.svg" to svg("<text>t</text>").toByteArray())), sink, "e.svg"); fail("пусто") }
        catch (e: ForeignImport.Unsupported) { assertEquals(SvgImport.MSG_EMPTY, e.message) }
        assertTrue(ForeignImport.MSG_UNKNOWN.contains("SVG"))
        assertEquals(null, ForeignImport.unknownMessage("x.svg"))
    }

    @Test fun importedCurvesStayEditableAndWarpable() {
        val r = SvgImport.parse(svg("<path d=\"M10 10 C20 0 40 0 50 10 S80 20 90 10\" stroke=\"black\" fill=\"none\"/>"))
        val s: VectorShape = all(r)[0]
        assertEquals(VecKind.POLY, s.kind)
        assertTrue(VectorBezier.hasHandles(s))
        val moved = VectorWarp.map(s, VectorWarp.affine(1.0, 0.0, 0.0, 1.0, 5.0, 0.0))
        assertNotNull(moved)
        assertEquals(15f, moved!!.pts[0]); assertEquals(s.handles, moved.handles)
    }
}
