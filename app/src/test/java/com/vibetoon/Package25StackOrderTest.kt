package com.vibetoon

import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.GroupNode
import com.vibetoon.domain.model.LayerTree
import com.vibetoon.domain.model.SceneTree
import com.vibetoon.domain.model.StackOrder
import com.vibetoon.domain.project.ProjectArchive
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 25: смешанный порядок слоёв PSD и медиа в одной группе. */
class Package25StackOrderTest {
    private fun leaf(id: Int, parent: Int = -1, x: Int = 0, y: Int = 0, w: Int = 10, h: Int = 10) =
        LayerInfo(id, "n$id", x, y, w, h, 1f, true, 0, parent, "norm", false)
    private fun group(id: Int, parent: Int = -1) = LayerInfo(id, "g$id", 0, 0, 0, 0, 1f, true, 1, parent, "norm", false)
    private fun end(parent: Int) = LayerInfo(900 + parent, "</g>", 0, 0, 0, 0, 1f, true, 3, parent, "norm", false)

    private val M1 = 100_001
    private val M2 = 100_002

    // корень: PSD 0, группа 1 (PSD 2, 3, конец группы), медиа M1, M2 (по умолчанию медиа над PSD)
    private fun base() = listOf(leaf(0), group(1), leaf(2, 1), leaf(3, 1), end(1), leaf(M1), leaf(M2, 1))

    @Test fun defaultOrderIsUnchangedWithoutExplicitOrder() {
        val l = base()
        assertEquals(l, StackOrder.apply(l, emptyMap()))
        assertEquals(listOf(0, 1, M1), StackOrder.children(l, -1))
        assertEquals(listOf(2, 3, M2), StackOrder.children(l, 1))   // конец группы не ребёнок
        assertFalse(StackOrder.mixed(l, 1))
    }

    @Test fun explicitOrderMixesPsdAndMediaAndKeepsStructure() {
        val l = base()
        val order = mapOf(1 to listOf(2, M2, 3), -1 to listOf(M1, 0, 1))
        val a = StackOrder.apply(l, order)
        assertEquals(listOf(2, M2, 3), StackOrder.children(a, 1))
        assertEquals(listOf(M1, 0, 1), StackOrder.children(a, -1))
        assertTrue(StackOrder.mixed(a, 1))
        assertEquals(l.size, a.size)
        assertEquals(l.map { it.id }.toSet(), a.map { it.id }.toSet())
        assertEquals(end(1), a[4])   // запись конца группы на своём месте
        // дерево и панель слоёв (сверху вниз) видят тот же порядок
        val g = SceneTree.build(a).first { it.id == 1 } as GroupNode
        assertEquals(listOf(2, M2, 3), g.children.map { it.id })
        assertEquals(listOf(1, 3, M2, 2, 0, M1), LayerTree.flatten(a, emptySet()).map { it.layer.id })
        // устаревшие и чужие id ни на что не влияют, новые дети - сверху
        val stale = StackOrder.apply(l, mapOf(1 to listOf(77, 3, 0)))
        assertEquals(listOf(3, 2, M2), StackOrder.children(stale, 1))
    }

    @Test fun rendererDrawsInTheMixedOrder() {
        val l = listOf(leaf(0, x = 0), leaf(1, x = 20), leaf(M1, x = 40))
        val fit = Affine2D.IDENTITY
        val def = SceneBuilder.buildTree(SceneTree.build(l), 100, 100, emptyMap(), fit)
        assertEquals(listOf(0, 1, M1), def.textureIds.toList())
        val mixed = StackOrder.apply(l, mapOf(-1 to listOf(0, M1, 1)))
        val sc = SceneBuilder.buildTree(SceneTree.build(mixed), 100, 100, emptyMap(), fit)
        assertEquals(listOf(0, M1, 1), sc.textureIds.toList())   // картинка между слоями PSD
    }

    @Test fun mediaClipsToPsdLayerBelowAtRoot() {
        val clippedMedia = LayerInfo(M1, "clip", 0, 0, 10, 10, 1f, true, 0, -1, "norm", true)
        val sc = SceneBuilder.buildTree(SceneTree.build(listOf(leaf(0), clippedMedia)), 100, 100, emptyMap(), Affine2D.IDENTITY)
        val stride = 4   // op, id, blend, flags
        // второй слой (картинка над слоем PSD) помечен как обтравленный
        assertEquals(listOf(0, M1), sc.textureIds.toList())
        assertTrue(sc.ops.size >= 2 * stride && (sc.ops[stride + 3] and SceneBuilder.FLAG_CLIPPED) != 0)
    }

    @Test fun dragRuleAndEdges() {
        val l = base()
        // картинка M2 (наверху группы) брошена на PSD 2 - тянули сверху, встаёт под него
        val o1 = StackOrder.drop(l, emptyMap(), M2, 2)!!
        assertEquals(listOf(M2, 2, 3), o1[1])
        // PSD 2 брошен на картинку M2 - тянули снизу, встаёт над ней
        val o2 = StackOrder.drop(l, emptyMap(), 2, M2)!!
        assertEquals(listOf(3, M2, 2), o2[1])
        assertNull(StackOrder.drop(l, emptyMap(), 2, 0))   // разные группы - не порядок
        assertNull(StackOrder.drop(l, emptyMap(), 2, 2))
        // явное «над целью»
        assertEquals(listOf(2, M2, 3), StackOrder.place(l, emptyMap(), M2, 2, above = true)!![1])
        assertNull(StackOrder.place(l, emptyMap(), 3, 2, above = true))   // уже над ним - без изменений
        // наверх / вниз группы
        assertEquals(listOf(M2, 2, 3), StackOrder.toEdge(l, emptyMap(), M2, top = false)!![1])
        assertEquals(listOf(3, M2, 2), StackOrder.toEdge(l, emptyMap(), 2, top = true)!![1])
        assertNull(StackOrder.toEdge(l, emptyMap(), M2, top = true))
        // фон - под всем в корне, в том числе под PSD
        assertEquals(listOf(M1, 0, 1), StackOrder.bottom(l, emptyMap(), M1)[-1])
    }

    @Test fun groupKeepsVisibleOrderAndTakesThePlaceOfTheTopLayer() {
        // корень: PSD 0, PSD 1, медиа M1, M2 по умолчанию; явно: 0, M1, 1, M2
        val before = StackOrder.apply(listOf(leaf(0), leaf(1), leaf(M1), leaf(M2)), mapOf(-1 to listOf(0, M1, 1, M2)))
        val gid = 99_999
        val after = StackOrder.apply(listOf(leaf(0), leaf(1, gid), leaf(M1, gid), leaf(M2), group(gid)), emptyMap())
        val o = StackOrder.group(before, after, mapOf(-1 to listOf(0, M1, 1, M2)), setOf(M1, 1), gid, -1)
        assertEquals(listOf(M1, 1), o[gid])              // внутри - прежний порядок вперемешку
        assertEquals(listOf(0, gid, M2), o[-1])           // группа на месте верхнего (1)
        val a = StackOrder.apply(after, o)
        assertEquals(listOf(0, gid, M2), StackOrder.children(a, -1))
        assertEquals(listOf(M1, 1), StackOrder.children(a, gid))
        assertEquals(listOf(0, M1, 1, M2), StackOrder.visual(before))
    }

    @Test fun substitutePruneAndNewPsd() {
        assertEquals(listOf(0, 500, 3), StackOrder.substitute(listOf(0, 1, 2, 3), setOf(1, 2), 1, 500))
        val o = mapOf(-1 to listOf(0, M1, 5), 1 to listOf(2, 9), 7 to listOf(M2))
        val p = StackOrder.prune(o, setOf(0, M1, 1, 2, M2))
        assertEquals(listOf(0, M1), p[-1])
        assertEquals(listOf(2), p[1])
        assertNull(p[7])                                  // группы 7 больше нет
        // новый PSD: записи PSD уходят, медиа и пользовательская группа 99999 (медиа-группа в пространстве PSD) остаются
        val d = StackOrder.dropPsd(mapOf(-1 to listOf(0, M1, 99_999), 3 to listOf(4, M2), 99_999 to listOf(2, M2)), setOf(M1, M2, 99_999))
        assertEquals(listOf(M1, 99_999), d[-1])
        assertNull(d[3])
        assertEquals(listOf(M2), d[99_999])
    }

    @Test fun projectJsonRoundTripVersionAndDamage() {
        val meta = ProjectMeta("p25", "Порядок", 1080, 1080, fps = 24, durationFrames = 48)
        val order = mapOf(-1 to listOf(M1, 0, 1), 1 to listOf(2, M2, 3))
        val json = ProjectJson.encode(ProjectData(meta, stackOrder = order))
        assertEquals(8, ProjectArchive.versionOf(json))
        assertEquals(8, ProjectJson.VERSION)
        assertEquals(order, ProjectJson.decode(json).getOrThrow().stackOrder)
        // без явного порядка формат прежний
        val plain = ProjectJson.encode(ProjectData(meta))
        assertFalse(plain.contains("\"order\""))
        assertEquals(1, ProjectArchive.versionOf(plain))
        // мусор в списке не ломает чтение
        val bad = json.replace("\"order\"", "\"order\":{\"x\":[1],\"-5\":[2],\"4\":[\"a\",-3,6,6]},\"old\"")
        assertEquals(mapOf(4 to listOf(6)), ProjectJson.decode(bad).getOrThrow().stackOrder)
    }
}
