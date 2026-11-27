package com.vibetoon

import com.vibetoon.domain.model.LayerListDrag
import com.vibetoon.domain.model.LayerListView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 40: перетаскивание слоёв в виде «Список» - цель броска при строках разной высоты, кости, поиск. */
class Package40LayerListTest {
    private fun layer(id: Int, group: Boolean = false) = LayerListView.Entry(id, "L$id", 0, -1, if (group) LayerListView.Category.GROUP else LayerListView.Category.DRAWING, isGroup = group)
    private fun bone(id: Int) = LayerListView.Entry(id, "B$id", 1, 5, LayerListView.Category.BONES, bone = true)

    @Test fun targetFollowsRowCentreWithMixedHeights() {
        val h = listOf(32f, 32f, 30f, 30f, 32f)
        assertEquals(0, LayerListDrag.targetIndex(h, 0, 0f))
        assertEquals(0, LayerListDrag.targetIndex(h, 0, 15f))      // меньше половины строки - на месте
        assertEquals(1, LayerListDrag.targetIndex(h, 0, 17f))
        assertEquals(4, LayerListDrag.targetIndex(h, 0, 140f))
        assertEquals(4, LayerListDrag.targetIndex(h, 0, 9999f))    // ниже конца - последняя строка
        assertEquals(0, LayerListDrag.targetIndex(h, 4, -9999f))   // выше начала - первая
        assertEquals(-1, LayerListDrag.targetIndex(emptyList(), 0, 10f))
        assertEquals(2, LayerListDrag.targetIndex(h, 2, Float.NaN))
    }

    @Test fun boneRowMeansItsBonesLayer() {
        val es = listOf(layer(1), layer(5), bone(100), bone(101), layer(2))
        assertEquals(5, LayerListDrag.layerAt(es, 3))
        assertEquals(5, LayerListDrag.layerAt(es, 2))
        assertEquals(2, LayerListDrag.layerAt(es, 4))
        assertNull(LayerListDrag.layerAt(listOf(bone(1)), 0))
    }

    @Test fun dropTargetSkipsSelfAndNoMove() {
        val es = listOf(layer(1), layer(2, group = true), layer(3), layer(4))
        val h = List(4) { 32f }
        assertNull(LayerListDrag.dropTarget(es, h, 0, 5f, 1))                 // не сдвинули - ничего
        assertEquals(2, LayerListDrag.dropTarget(es, h, 0, 32f, 1))           // на группу - VM положит внутрь
        assertEquals(4, LayerListDrag.dropTarget(es, h, 0, 96f, 1))           // на слой - на его место
        assertEquals(1, LayerListDrag.dropTarget(es, h, 3, -96f, 4))
        assertNull(LayerListDrag.dropTarget(es, h, 9, 32f, 1))                 // устаревший индекс
        assertNull(LayerListDrag.dropTarget(es, h.dropLast(1), 0, 32f, 1))     // высоты не совпали со списком
        // бросок на кость своего же слоя костей - не перемещение
        val withBones = listOf(layer(5), bone(100), layer(6))
        assertNull(LayerListDrag.dropTarget(withBones, listOf(32f, 30f, 32f), 0, 31f, 5))
    }

    @Test fun handleOnlyWhenWholeListVisible() {
        assertTrue(LayerListDrag.canDrag(layer(1), searching = false, multi = false))
        assertFalse(LayerListDrag.canDrag(layer(1), searching = true, multi = false))
        assertFalse(LayerListDrag.canDrag(layer(1), searching = false, multi = true))
        assertFalse(LayerListDrag.canDrag(bone(1), searching = false, multi = false))
    }
}
