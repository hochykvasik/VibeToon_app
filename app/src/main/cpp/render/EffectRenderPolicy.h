#pragma once

namespace vt {
// Existing renderer cannot apply an FX chain to a clipped layer; callers must not
// report a group-wide edit as visible on such a layer.
constexpr bool applyLayerEffect(bool clipped, bool hasEffects, bool hasProgram, bool hasTexture) {
    return !clipped && hasEffects && hasProgram && hasTexture;
}
}
