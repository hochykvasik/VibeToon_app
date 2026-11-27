package com.vibetoon

import com.vibetoon.domain.project.DrawingInsert
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.FrameMath
import com.vibetoon.domain.project.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 17: Import → Insert Images into Existing Drawing. */
class Package17InsertIntoDrawingTest {

    private val cells = listOf(FrameCell(0, "assets/a.png"), FrameCell(4, "assets/b.png"), FrameCell(9, "assets/c.png"))

    @Test fun replacesDrawingAtPlayheadKeepingExposure() {
        val r = FrameMath.replaceActive(cells, 6, "assets/new.png", "assets/base.png")
        assertEquals(listOf(FrameCell(0, "assets/a.png"), FrameCell(4, "assets/new.png"), FrameCell(9, "assets/c.png")), r)
        // плейхед ровно на начале рисунка и до первого рисунка (удержание назад)
        assertEquals("assets/new.png", FrameMath.replaceActive(cells, 9, "assets/new.png", "x").last().file)
        assertEquals("assets/new.png", FrameMath.replaceActive(cells.drop(1), 0, "assets/new.png", "x").first().file)
        assertEquals("assets/b.png", cells[1].file)
    }

    @Test fun plainLayerBecomesSingleDrawing() {
        assertEquals(listOf(FrameCell(0, "assets/new.png")), FrameMath.replaceActive(emptyList(), 17, "assets/new.png", "assets/base.png"))
    }

    @Test fun textureFallsBackToLayerFileAfterUndo() {
        // с кадрами - рисунок кадра
        assertEquals("assets/b.png", FrameMath.shownFile(cells, 5, "assets/base.png", "assets/a.png"))
        // кадров не осталось (Undo), текстура показывала рисунок кадра - вернуть файл слоя
        assertEquals("assets/base.png", FrameMath.shownFile(emptyList(), 5, "assets/base.png", "assets/new.png"))
        // обычный слой, кадров не было - ничего не подменять (рисование в свой файл)
        assertNull(FrameMath.shownFile(emptyList(), 5, "assets/base.png", null))
    }

    @Test fun fitsAndCentersKeepingProportions() {
        val wide = DrawingInsert.fitRect(400, 100, 200, 200)       // 2:1 в квадрат: по ширине
        assertEquals(listOf(0f, 75f, 200f, 125f), wide.toList())
        val tall = DrawingInsert.fitRect(100, 400, 200, 100)       // по высоте
        assertEquals(listOf(87.5f, 0f, 112.5f, 100f), tall.toList())
        val small = DrawingInsert.fitRect(10, 10, 100, 50)         // маленькая картинка увеличивается до рисунка
        assertEquals(listOf(25f, 0f, 75f, 50f), small.toList())
        val broken = DrawingInsert.fitRect(0, 10, 100, 50)
        assertEquals(listOf(0f, 0f, 100f, 50f), broken.toList())
    }

    @Test fun onlyUnlockedRasterImageLayers() {
        assertTrue(DrawingInsert.accepts(MediaKind.IMAGE, vector = false, locked = false))
        assertFalse(DrawingInsert.accepts(MediaKind.IMAGE, vector = true, locked = false))
        assertFalse(DrawingInsert.accepts(MediaKind.IMAGE, vector = false, locked = true))
        for (k in listOf(MediaKind.TEXT, MediaKind.VIDEO, MediaKind.GROUP, MediaKind.SYMBOL, MediaKind.AUDIO)) assertFalse(DrawingInsert.accepts(k, false, false))
    }
}
