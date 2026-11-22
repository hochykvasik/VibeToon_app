#pragma once

namespace vt {
// Mesh payload stores original UV then displacement; positions remain unbounded
// so the editor can render geometry outside the canvas.
struct MeshPosition { float x, y, u, v; };
constexpr MeshPosition meshPosition(float u, float v, float dx, float dy) {
    return {u + dx, v + dy, u, v};
}
}
