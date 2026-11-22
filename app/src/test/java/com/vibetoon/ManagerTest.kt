package com.vibetoon

import com.vibetoon.domain.project.BackupMath
import com.vibetoon.domain.project.ProjectListMath
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.ProjectSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerTest {
    private fun p(id: String, name: String, t: Long, folder: String = "") = ProjectMeta(id, name, 100, 100, updatedAt = t, folder = folder)
    private val all = listOf(p("a", "Zebra", 10, "Работа"), p("b", "apple", 30), p("c", "Mango", 20, "Работа"))

    @Test fun searchAndFolderFilter() {
        assertEquals(listOf("b"), ProjectListMath.filter(all, "APP", "").map { it.id })
        assertEquals(listOf("a", "c"), ProjectListMath.filter(all, "", "Работа").map { it.id })
        assertEquals(3, ProjectListMath.filter(all, "  ", "").size)
    }

    @Test fun sortByDateNameSize() {
        assertEquals(listOf("b", "c", "a"), ProjectListMath.sort(all, ProjectSort.DATE, emptyMap()).map { it.id })
        assertEquals(listOf("b", "c", "a"), ProjectListMath.sort(all, ProjectSort.NAME, emptyMap()).map { it.id })   // apple, Mango, Zebra
        assertEquals(listOf("c", "a", "b"), ProjectListMath.sort(all, ProjectSort.SIZE, mapOf("c" to 900L, "a" to 500L, "b" to 1L)).map { it.id })
    }

    @Test fun foldersMergeStoredAndUsed() {
        assertEquals(listOf("Архив", "Работа"), ProjectListMath.folders(setOf("Архив", " "), all))
        assertEquals("Моя папка", ProjectListMath.cleanFolderName("  Моя   папка "))
    }

    @Test fun backupRulesAndZipSlip() {
        assertTrue(BackupMath.included("project.json")); assertTrue(BackupMath.included("assets/m1.png"))
        assertFalse(BackupMath.included("exports/a.mp4")); assertFalse(BackupMath.included("assets/x.mp3.wave"))
        assertNull(BackupMath.safePath("../evil.txt")); assertNull(BackupMath.safePath("assets/../../x"))
        assertEquals("assets/a.png", BackupMath.safePath("/assets/a.png"))
    }
}
