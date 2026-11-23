// Кэш результата эффектов слоя (обводка группы, свечение, тень...) при движении камеры.
// Чистые функции без GL: проверяются нативным тестом в CI.
//
// Идея: результат цепочки эффектов слоя посчитан для вида va0 (холст -> экран) и сохранён в маленькую
// текстуру размером со свою экранную рамку. Пока сцена и текстуры не менялись (эпоха та же):
//  - вид не изменился (D = va * va0^-1 == I) - результат переиспользуется пиксель в пиксель;
//  - идёт жест камеры (пан/зум/поворот двумя пальцами) - результат рисуется с преобразованием D,
//    если нужная сейчас область целиком лежит внутри сохранённой (иначе пересчёт). После жеста
//    кадр пересчитывается точно (D == I не выполняется -> обычный проход эффектов -> новый кэш).
#pragma once
#include <cmath>

#include "render/LayerBounds.h"

namespace vt {
namespace fxcache {

// Аффинная матрица [a b c d tx ty]: точка = (a*x + c*y + tx, b*x + d*y + ty).
inline bool invert(const float m[6], float out[6]) {
    const float det = m[0] * m[3] - m[1] * m[2];
    if (!std::isfinite(det) || std::fabs(det) < 1e-12f) return false;
    const float id = 1.f / det;
    out[0] = m[3] * id;
    out[1] = -m[1] * id;
    out[2] = -m[2] * id;
    out[3] = m[0] * id;
    out[4] = -(out[0] * m[4] + out[2] * m[5]);
    out[5] = -(out[1] * m[4] + out[3] * m[5]);
    return true;
}

// out = A * B (сначала B, потом A).
inline void compose(const float A[6], const float B[6], float out[6]) {
    float r[6];
    r[0] = A[0] * B[0] + A[2] * B[1];
    r[1] = A[1] * B[0] + A[3] * B[1];
    r[2] = A[0] * B[2] + A[2] * B[3];
    r[3] = A[1] * B[2] + A[3] * B[3];
    r[4] = A[0] * B[4] + A[2] * B[5] + A[4];
    r[5] = A[1] * B[4] + A[3] * B[5] + A[5];
    for (int i = 0; i < 6; ++i) out[i] = r[i];
}

inline void apply(const float m[6], float x, float y, float& ox, float& oy) {
    ox = m[0] * x + m[2] * y + m[4];
    oy = m[1] * x + m[3] * y + m[5];
}

// Преобразование экрана «тогда -> сейчас»: D = vaNow * vaThen^-1. false - вырожденный вид.
inline bool screenDelta(const float vaNow[6], const float vaThen[6], float d[6]) {
    float inv[6];
    if (!invert(vaThen, inv)) return false;
    compose(vaNow, inv, d);
    for (int i = 0; i < 6; ++i) if (!std::isfinite(d[i])) return false;
    return true;
}

// Вид не менялся: линейная часть единичная, сдвиг меньше сотой пикселя.
inline bool isIdentity(const float d[6]) {
    return std::fabs(d[0] - 1.f) < 1e-5f && std::fabs(d[3] - 1.f) < 1e-5f && std::fabs(d[1]) < 1e-5f &&
           std::fabs(d[2]) < 1e-5f && std::fabs(d[4]) < 0.01f && std::fabs(d[5]) < 0.01f;
}

// Масштаб преобразования (корень из |det|).
inline float scaleOf(const float d[6]) { return std::sqrt(std::fabs(d[0] * d[3] - d[1] * d[2])); }

// Во время жеста результат можно растянуть не больше чем вдвое (иначе заметно мыло/лесенка - пересчёт).
inline bool scaleAcceptable(const float d[6]) {
    const float s = scaleOf(d);
    return std::isfinite(s) && s >= 0.5f && s <= 2.f;
}

// Нужная сейчас область [need] (экран сейчас) целиком внутри сохранённой [cached] (экран тогда)?
// dInv - преобразование «сейчас -> тогда». tol - допуск в пикселях.
inline bool covers(const ScreenRect& cached, const ScreenRect& need, const float dInv[6], float tol = 1.f) {
    if (need.empty()) return true;
    const float xs[2] = {need.x0, need.x1}, ys[2] = {need.y0, need.y1};
    for (float x : xs) for (float y : ys) {
        float px, py;
        apply(dInv, x, y, px, py);
        if (!std::isfinite(px) || !std::isfinite(py)) return false;
        if (px < cached.x0 - tol || px > cached.x1 + tol || py < cached.y0 - tol || py > cached.y1 + tol) return false;
    }
    return true;
}

// Сколько байт кэша можно держать: не больше [budget], одна запись не больше половины экрана
// (полноэкранные эффекты вроде зеркала/плитки не кэшируются - они и так считаются один раз на кадр).
inline bool entryAllowed(int w, int h, int screenW, int screenH) {
    if (w <= 0 || h <= 0) return false;
    return static_cast<double>(w) * h <= 0.5 * static_cast<double>(screenW) * screenH;
}

}  // namespace fxcache
}  // namespace vt
