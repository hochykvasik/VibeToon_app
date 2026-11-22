package com.vibetoon

import com.vibetoon.domain.project.*
import org.junit.Assert.*
import org.junit.Test

class GroupAnchorProjectTest {
    private val meta = ProjectMeta("p", "test", 100, 100)
    @Test fun legacyFilesStayV1AndAnchorsRoundTripAsV2() {
        val old = ProjectJson.encode(ProjectData(meta))
        assertTrue(old.contains("\"version\": 1"))
        assertTrue(ProjectJson.decode(old).getOrThrow().groupDeformationAnchors.isEmpty())
        val anchors = mapOf(17 to GroupDeformationAnchor(-200f, -30f, 400f, 100f), 20 to GroupDeformationAnchor(4f, 9f, 5f, 6f))
        val text = ProjectJson.encode(ProjectData(meta, groupDeformationAnchors = anchors))
        assertTrue(text.contains("\"version\": 2"))
        assertEquals(anchors, ProjectJson.decode(text).getOrThrow().groupDeformationAnchors)
        assertEquals(anchors, ProjectJson.decode(ProjectJson.encode(ProjectJson.decode(text).getOrThrow())).getOrThrow().groupDeformationAnchors)
    }
    @Test fun corruptAndVersionMismatchedAnchorsAreRejectedNotDropped() {
        for (body in listOf("{\"17\":[0,0,0,5]}", "{\"17\":[0,0,1]}", "{\"-1\":[0,0,1,1]}", "{\"17\":[0,0,1e300,1]}", "[]")) {
            assertTrue(ProjectJson.decode("{\"version\":2,\"id\":\"p\",\"groupDeformationAnchors\":$body}").isFailure)
        }
        assertTrue(ProjectJson.decode("{\"version\":1,\"id\":\"p\",\"groupDeformationAnchors\":{\"17\":[0,0,1,1]}}").isFailure)
        try { ProjectJson.encode(ProjectData(meta, groupDeformationAnchors = mapOf(1 to GroupDeformationAnchor(0f, 0f, Float.NaN, 2f)))); fail() } catch (_: IllegalArgumentException) { }
    }
}
