package com.vibetoon

import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.StarterProject
import org.junit.Assert.*
import org.junit.Test

class StarterProjectTest {
    @Test fun starterContainsDistinctShapeLayersAndSurvivesSerialization() {
        val data = StarterProject.data("starter", 123L)
        assertEquals(3, data.media.size)
        assertEquals(3, data.media.map { it.id }.toSet().size)
        assertEquals(3, data.media.map { it.file }.toSet().size)
        assertTrue(data.media.all { it.x >= 0 && it.x + it.w <= data.meta.width })
        val restored = ProjectJson.decode(ProjectJson.encode(data)).getOrThrow()
        assertEquals(data.meta, restored.meta)
        assertEquals(data.media, restored.media)
    }
}
