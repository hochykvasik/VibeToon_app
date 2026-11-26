package com.vibetoon

import com.vibetoon.domain.project.StarterProject
import com.vibetoon.domain.render.ProjectRenderStartup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProjectRenderStartupTest {
    @Test fun recommendedAndTutorialEntryShareTheReadyRestorePublishSequence() = runBlocking {
        val source = File("src/main/java/com/vibetoon/ui/projects/ProjectsScreen.kt").readText()
        assertTrue(source.contains("app.startLesson(WELCOME_TOUR.coachSession())"))   // спринт 53: тур выбирается явно
        assertTrue(source.contains("app.startLesson(t.coachSession())"))
        val project = StarterProject.data("tutorial", 1)
        val steps = mutableListOf<String>()
        val uploaded = mutableSetOf<Int>()
        val result = ProjectRenderStartup.start(
            acquireReady = { steps += "renderer-ready-and-cleared"; "view" },
            clear = { steps += "empty-scene" },
            restore = { steps += "textures"; uploaded.addAll(project.media.map { it.id }) },
            publish = { assertEquals(project.media.map { it.id }.toSet(), uploaded); steps += "scene" }
        )
        assertEquals("view", result)
        assertEquals(listOf("renderer-ready-and-cleared", "empty-scene", "textures", "scene"), steps)
        assertEquals(3, uploaded.size)
    }

    @Test fun failureToRestoreDoesNotPublishScene() = runBlocking {
        var published = false
        try {
            ProjectRenderStartup.start(acquireReady = { Unit }, clear = {}, restore = { error("missing texture") }, publish = { published = true })
            fail("expected error")
        } catch (_: IllegalStateException) { }
        assertFalse(published)
    }
}
