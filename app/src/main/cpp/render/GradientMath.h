#pragma once
#include <algorithm>
#include <cmath>

namespace vt {
// Validation also used before invoking the GPU shader; A is opaque, B is transparent.
inline bool validGradientGesture(float ax, float ay, float bx, float by) {
    if (!std::isfinite(ax) || !std::isfinite(ay) || !std::isfinite(bx) || !std::isfinite(by)) return false;
    const float dx = bx - ax, dy = by - ay, d2 = dx * dx + dy * dy;
    return std::isfinite(d2) && d2 >= 4.f;
}
inline float gradientAlpha(float x, float y, float ax, float ay, float bx, float by) {
    const float dx = bx - ax, dy = by - ay;
    return 1.f - std::clamp(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy), 0.f, 1.f);
}
}
