package com.vibetoon

import com.vibetoon.domain.anim.PerspectiveMath
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerspectiveTest {
    @Test fun identity_mapsPointsToThemselves() {
        val m = PerspectiveMath.matrix(FloatArray(8))
        val (x, y) = PerspectiveMath.apply(m, 0.3f, 0.7f)
        assertEquals(0.3f, x, 1e-5f)
        assertEquals(0.7f, y, 1e-5f)
    }

    @Test fun corners_mapToDisplacedCorners() {
        val off = floatArrayOf(0.1f, 0.05f, -0.2f, 0.1f, -0.1f, -0.15f, 0.05f, 0.0f)
        val m = PerspectiveMath.matrix(off)
        for (k in 0 until 4) {
            val (x, y) = PerspectiveMath.apply(m, PerspectiveMath.cornerX(k), PerspectiveMath.cornerY(k))
            assertEquals(PerspectiveMath.cornerX(k) + off[k * 2], x, 1e-4f)
            assertEquals(PerspectiveMath.cornerY(k) + off[k * 2 + 1], y, 1e-4f)
        }
    }

    @Test fun parallelogram_isAffine() {
        val off = floatArrayOf(0.2f, 0f, 0.2f, 0f, 0.2f, 0f, 0.2f, 0f)   // сдвиг всех углов
        val m = PerspectiveMath.matrix(off)
        assertEquals(0f, m[2], 1e-6f)
        assertEquals(0f, m[5], 1e-6f)
        val (x, _) = PerspectiveMath.apply(m, 0.5f, 0.5f)
        assertEquals(0.7f, x, 1e-5f)
    }

    @Test fun json_roundTrip() {
        val d = ProjectData(ProjectMeta(id = "p", name = "t", width = 10, height = 10), persps = setOf(3, 9))
        assertEquals(setOf(3, 9), ProjectJson.decode(ProjectJson.encode(d)).getOrThrow().persps)
        assertTrue(PerspectiveMath.isPointId(PerspectiveMath.pointId(3, 2)))
    }
}
