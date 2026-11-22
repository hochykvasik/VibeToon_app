package com.vibetoon

import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.FrameMath
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCellsTest {
    private val cells = listOf(FrameCell(0, "a"), FrameCell(4, "b"), FrameCell(9, "c"))

    @Test fun activeHoldsUntilNextCell() {
        assertEquals("a", FrameMath.active(cells, 0)!!.file)
        assertEquals("a", FrameMath.active(cells, 3)!!.file)
        assertEquals("b", FrameMath.active(cells, 4)!!.file)
        assertEquals("c", FrameMath.active(cells, 100)!!.file)
        assertEquals("b", FrameMath.active(listOf(FrameCell(4, "b")), 1)!!.file)   // до первого - удержание назад
        assertNull(FrameMath.active(emptyList(), 0))
    }

    @Test fun neighborsForOnionSkin() {
        val (p, n) = FrameMath.neighbors(cells, 5, 3)
        assertEquals(listOf("a"), p.map { it.file })
        assertEquals(listOf("c"), n.map { it.file })
        val (p2, n2) = FrameMath.neighbors(cells, 0, 1)
        assertEquals(emptyList<FrameCell>(), p2); assertEquals(listOf("b"), n2.map { it.file })
        val long = (0..9).map { FrameCell(it * 2, "f$it") }
        val (p3, n3) = FrameMath.neighbors(long, 10, 2)
        assertEquals(listOf("f4", "f3"), p3.map { it.file })
        assertEquals(listOf("f6", "f7"), n3.map { it.file })
    }

    @Test fun freeStartAddAndDelete() {
        assertEquals(1, FrameMath.freeStart(cells, 0, 24))
        assertEquals(5, FrameMath.freeStart(cells, 4, 24))
        assertEquals(12, FrameMath.freeStart(cells, 12, 24))
        assertNull(FrameMath.freeStart(cells, 23, 23))
        assertEquals(listOf(0, 2, 4, 9), FrameMath.with(cells, FrameCell(2, "x")).map { it.start })
        // удаление активного: предыдущий держится дальше
        val d = FrameMath.without(cells, 5)
        assertEquals(listOf("a", "c"), d.map { it.file })
        assertEquals("a", FrameMath.active(d, 5)!!.file)
        val one = listOf(FrameCell(0, "a"))
        assertEquals(one, FrameMath.without(one, 0))
    }

    @Test fun ghostAlphaFadesWithDistance() {
        assertEquals(0.35f, FrameMath.ghostAlpha(0.35f, 0), 1e-6f)
        assertEquals(0.21f, FrameMath.ghostAlpha(0.35f, 1), 1e-5f)
    }

    @Test fun cellsAndGroupSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val media = listOf(
            MediaItem(100001, MediaKind.IMAGE, "Кадры", "assets/m1.png", cells = cells),
            MediaItem(99999, MediaKind.GROUP, "Группа 1", "")
        )
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, media = media))).getOrThrow().media
        assertEquals(cells, back.first { it.id == 100001 }.cells)
        assertEquals(MediaKind.GROUP, back.first { it.id == 99999 }.kind)
    }
}
