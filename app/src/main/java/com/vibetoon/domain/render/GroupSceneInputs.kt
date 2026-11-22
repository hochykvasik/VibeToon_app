package com.vibetoon.domain.render

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.CubicWarpGrid
import com.vibetoon.domain.anim.PerspectiveMath
import com.vibetoon.domain.anim.WarpMath
import com.vibetoon.domain.project.GroupDeformationAnchor

/** One frame's inputs, shared by editor and export. An anchor alone is inert. */
object GroupSceneInputs {
    data class Split(val warps: Map<Int, FloatArray>, val perspectives: Map<Int, FloatArray>, val groups: Map<Int, SceneBuilder.GroupDeformation>)
    fun build(anchors: Map<Int, GroupDeformationAnchor>, warpIds: Set<Int>, perspectiveIds: Set<Int>,
              frame: Int, base: Map<Int, LayerTransform>, animation: Animation, fx: Boolean,
              sizes: Map<Int, CubicWarpGrid.Size> = emptyMap()): Split {
        val groupIds = anchors.keys.intersect(warpIds + perspectiveIds)
        val groups = groupIds.associateWith { id ->
            val a = anchors.getValue(id)
            require(a.valid()) { "Invalid group bounds" }
            SceneBuilder.GroupDeformation(GroupDeformationMesh.Bounds(a.x, a.y, a.width, a.height),
                if (id in warpIds) CubicWarpGrid.cubic(id, sizes[id] ?: CubicWarpGrid.DEFAULT, frame, base, animation, fx) else null,
                if (id in perspectiveIds) PerspectiveMath.matrix(PerspectiveMath.offsets(id, frame, base, animation, fx)) else null)
        }
        return Split((warpIds - groupIds).associateWith { CubicWarpGrid.cubic(it, sizes[it] ?: CubicWarpGrid.DEFAULT, frame, base, animation, fx) },
            (perspectiveIds - groupIds).associateWith { PerspectiveMath.matrix(PerspectiveMath.offsets(it, frame, base, animation, fx)) }, groups)
    }
}
