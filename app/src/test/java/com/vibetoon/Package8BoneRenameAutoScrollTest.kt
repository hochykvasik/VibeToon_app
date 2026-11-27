package com.vibetoon

import com.vibetoon.domain.anim.QuickDeselect
import com.vibetoon.domain.input.EdgeAutoScroll
import com.vibetoon.domain.project.BONE_ID_BASE
import com.vibetoon.domain.project.Bone
import com.vibetoon.domain.project.BoneRename
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 8: имя кости, автопрокрутка списка при перетаскивании слоёв, быстрое снятие выделения на таймлайне. */
class Package8BoneRenameAutoScrollTest {

    // ---- имя кости

    @Test
    fun boneNameIsTrimmedAndOneLine() {
        assertEquals("Предплечье", BoneRename.sanitize("  Предплечье \n", "Кость 2"))
        assertEquals("Левая рука", BoneRename.sanitize("Левая\nрука", "Кость 1"))
    }

    @Test
    fun emptyOrSameNameChangesNothing() {
        assertNull(BoneRename.sanitize("   ", "Кость 1"))
        assertNull(BoneRename.sanitize("Кость 1", "Кость 1"))
    }

    @Test
    fun boneNameIsLimited() {
        val long = "а".repeat(100)
        assertEquals(BoneRename.MAX_LEN, BoneRename.sanitize(long, "Кость 1")!!.length)
    }

    @Test
    fun renameTouchesOnlyThatBone() {
        val bones = listOf(Bone(BONE_ID_BASE, "Кость 1", 0f, 0f, 10f, 0f), Bone(BONE_ID_BASE + 1, "Кость 2", 10f, 0f, 20f, 0f))
        val out = BoneRename.apply(bones, BONE_ID_BASE + 1, "Предплечье")
        assertEquals("Кость 1", out[0].name)
        assertEquals("Предплечье", out[1].name)
        assertEquals(bones[1].copy(name = "Предплечье"), out[1])
    }

    @Test
    fun boneNameIsSavedInProject() {
        val bones = listOf(Bone(BONE_ID_BASE, "Предплечье", 1f, 2f, 30f, 4f))
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(ProjectMeta("p", "n", 100, 100), bones = bones))).getOrThrow()
        assertEquals("Предплечье", back.bones.single().name)
    }

    // ---- автопрокрутка при перетаскивании слоёв

    @Test
    fun noScrollInTheMiddle() {
        assertEquals(0f, EdgeAutoScroll.velocity(500f, 1000, 50f, 20f, canBack = true, canForward = true), 0f)
    }

    @Test
    fun scrollsUpNearTopAndFasterAtTheEdge() {
        val near = EdgeAutoScroll.velocity(40f, 1000, 50f, 20f, canBack = true, canForward = true)
        val edge = EdgeAutoScroll.velocity(0f, 1000, 50f, 20f, canBack = true, canForward = true)
        assertTrue(near < 0f)
        assertTrue(edge < near)
        assertEquals(-20f, edge, 1e-4f)
        // палец выше окна - максимальная скорость, не больше
        assertEquals(-20f, EdgeAutoScroll.velocity(-300f, 1000, 50f, 20f, canBack = true, canForward = true), 1e-4f)
    }

    @Test
    fun scrollsDownNearBottom() {
        assertTrue(EdgeAutoScroll.velocity(980f, 1000, 50f, 20f, canBack = true, canForward = true) > 0f)
        assertEquals(20f, EdgeAutoScroll.velocity(1200f, 1000, 50f, 20f, canBack = true, canForward = true), 1e-4f)
    }

    @Test
    fun stopsAtListEnds() {
        assertEquals(0f, EdgeAutoScroll.velocity(0f, 1000, 50f, 20f, canBack = false, canForward = true), 0f)
        assertEquals(0f, EdgeAutoScroll.velocity(1000f, 1000, 50f, 20f, canBack = true, canForward = false), 0f)
        assertEquals(0f, EdgeAutoScroll.velocity(10f, 0, 50f, 20f, canBack = true, canForward = true), 0f)
        assertEquals(0f, EdgeAutoScroll.velocity(Float.NaN, 1000, 50f, 20f, canBack = true, canForward = true), 0f)
    }

    @Test
    fun shortViewportEdgeBandsDoNotOverlap() {
        // окно 90 px, полоса 56 px: середина окна не прокручивает
        assertEquals(0f, EdgeAutoScroll.velocity(45f, 90, 56f, 20f, canBack = true, canForward = true), 0f)
    }

    @Test
    fun dragTargetFollowsAutoScroll() {
        val rowPx = 40f
        // строка 5, палец на 10 px в строке, протянули на 3 строки вниз + список прокрутился на 400 px
        assertEquals(5 * 40f + 10f + 520f, EdgeAutoScroll.contentPos(5, rowPx, 10f, 520f), 1e-4f)
        assertEquals(18, EdgeAutoScroll.targetIndex(5, 520f, rowPx, 63))
        // цель не выходит за список из 63 слоёв
        assertEquals(62, EdgeAutoScroll.targetIndex(5, 9999f, rowPx, 63))
        assertEquals(0, EdgeAutoScroll.targetIndex(5, -9999f, rowPx, 63))
    }

    // ---- быстрое снятие выделения

    @Test
    fun tapOnEmptyClearsSelection() {
        assertEquals(emptySet<Pair<Int, Int>>(), QuickDeselect.afterTap(setOf(1 to 5, 2 to 7), null))
    }

    @Test
    fun tapOnSelectedKeyDeselectsOnlyIt() {
        assertEquals(setOf(2 to 7), QuickDeselect.afterTap(setOf(1 to 5, 2 to 7), 1 to 5))
    }

    @Test
    fun tapOnUnselectedKeyKeepsNormalTap() {
        assertNull(QuickDeselect.afterTap(setOf(1 to 5), 3 to 9))
        assertNull(QuickDeselect.afterTap(emptySet<Pair<Int, Int>>(), null))
        assertNull(QuickDeselect.afterTap(emptySet(), 1 to 5))
    }

    @Test
    fun markersUseTheSameRule() {
        assertEquals(setOf(10), QuickDeselect.afterTap(setOf(4, 10), 4))
    }

    @Test
    fun keysOfOneBoneFocusThatBone() {
        val b1 = BONE_ID_BASE
        val b2 = BONE_ID_BASE + 1
        val bones = listOf(b1, b2)
        assertEquals(b2, QuickDeselect.focusBone(setOf(b2 to 3, b2 to 9), bones))
        // ключи слоёв не мешают: одна кость в выделении
        assertEquals(b2, QuickDeselect.focusBone(setOf(7 to 3, b2 to 9), bones))
        assertNull(QuickDeselect.focusBone(setOf(b1 to 3, b2 to 9), bones))
        assertNull(QuickDeselect.focusBone(setOf(7 to 3), bones))
    }
}
