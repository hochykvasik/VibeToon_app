// Экранные границы слоя и «дальнобойность» эффектов: ограничение fill-rate (scissor) и отсечение невидимого.
// Чистые функции без GL: проверяются нативным тестом в CI.
#pragma once
#include <algorithm>
#include <cmath>

namespace vt {

struct ScreenRect {
    float x0 = 0.f, y0 = 0.f, x1 = 0.f, y1 = 0.f;
    bool empty() const { return !(x1 > x0 && y1 > y0); }
};

// AABB единичного квадрата слоя на экране: view (холст -> экран) * m (слой -> холст).
inline ScreenRect layerScreenRect(const float view[6], const float m[6]) {
    // композиция аффинных матриц [a b c d tx ty] (точка = (a*x + c*y + tx, b*x + d*y + ty))
    const float a = view[0] * m[0] + view[2] * m[1];
    const float b = view[1] * m[0] + view[3] * m[1];
    const float c = view[0] * m[2] + view[2] * m[3];
    const float d = view[1] * m[2] + view[3] * m[3];
    const float tx = view[0] * m[4] + view[2] * m[5] + view[4];
    const float ty = view[1] * m[4] + view[3] * m[5] + view[5];
    const float xs[4] = {tx, tx + a, tx + c, tx + a + c};
    const float ys[4] = {ty, ty + b, ty + d, ty + b + d};
    ScreenRect r{xs[0], ys[0], xs[0], ys[0]};
    for (int i = 1; i < 4; ++i) {
        r.x0 = std::min(r.x0, xs[i]); r.x1 = std::max(r.x1, xs[i]);
        r.y0 = std::min(r.y0, ys[i]); r.y1 = std::max(r.y1, ys[i]);
    }
    if (!std::isfinite(r.x0) || !std::isfinite(r.y0) || !std::isfinite(r.x1) || !std::isfinite(r.y1)) return {};
    return r;
}

inline ScreenRect expand(const ScreenRect& r, float pad) { return {r.x0 - pad, r.y0 - pad, r.x1 + pad, r.y1 + pad}; }

inline ScreenRect intersect(const ScreenRect& r, float w, float h) {
    return {std::max(r.x0, 0.f), std::max(r.y0, 0.f), std::min(r.x1, w), std::min(r.y1, h)};
}

// Слой целиком за пределами цели w x h - рисовать нечего.
inline bool outsideTarget(const ScreenRect& r, float w, float h) {
    return r.x1 <= 0.f || r.y1 <= 0.f || r.x0 >= w || r.y0 >= h;
}

// Насколько далеко эффект переносит/размазывает пиксели слоя (пиксели ХОЛСТА).
// < 0 - эффект зависит от всего кадра (зеркало, плитка, zoom blur, пульс...), ограничивать нельзя.
// e = [mode, p0..p7, time] - тот же формат, что передаётся в шейдер.
inline float effectReach(const float* e) {
    const int mode = static_cast<int>(e[0]);
    const auto f = [&](int i) { return std::fabs(e[i]); };
    switch (mode) {
        case 0: case 2: case 6: case 7: case 12: case 20: return 0.f;   // цвет/альфа на месте
        case 1: return f(1);                       // Gaussian Blur: радиус
        case 3: return f(1);                       // Pixelate: блок
        case 4: return f(2);                       // Glow: радиус
        case 5: return f(1) + f(2) + f(3);         // Drop Shadow: dx + dy + радиус
        case 8: return f(1);                       // Stroke: ширина
        case 9: return f(1);                       // Wave: амплитуда
        case 13: return f(1);                      // Shake: амплитуда
        case 14: return f(1);                      // Motion Blur: длина
        case 17: return f(1) + f(2) + f(3);        // RGB Split: сдвиги + глитч
        case 19: return f(1);                      // Displacement: сила
        default: return -1.f;
    }
}

// Суммарный охват цепочки (эффекты применяются последовательно, охваты складываются).
inline float chainReach(const float* const* effects, int count) {
    float sum = 0.f;
    for (int i = 0; i < count; ++i) {
        const float r = effectReach(effects[i]);
        if (r < 0.f || !std::isfinite(r)) return -1.f;
        sum += r;
    }
    return sum;
}

// Целочисленный scissor (начало координат GL снизу). Возвращает false, если область пуста.
inline bool scissorBox(const ScreenRect& r, int targetH, int box[4]) {
    const int x0 = static_cast<int>(std::floor(r.x0)), x1 = static_cast<int>(std::ceil(r.x1));
    const int y0 = static_cast<int>(std::floor(r.y0)), y1 = static_cast<int>(std::ceil(r.y1));
    box[0] = x0; box[1] = targetH - y1; box[2] = std::max(0, x1 - x0); box[3] = std::max(0, y1 - y0);
    return box[2] > 0 && box[3] > 0;
}

}  // namespace vt
