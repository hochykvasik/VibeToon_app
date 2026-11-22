// Вид холста в редакторе: вписывание, камера (zoom, pan), поворот и отражение вида.
// Чистая математика без GL: её же проверяют нативные тесты на хосте (app/src/test/cpp).
//
//   экран = O + k * L * p,   p - точка холста (пиксели холста, y вниз)
//   k - масштаб (пикселей экрана на пиксель холста) = вписывание * zoom
//   L - ортонормированная матрица 2x2: поворот вида и отражения (Flip Canvas)
//   O - экранная точка начала холста (левый верхний угол без поворота)
//
// Поворот и отражение - вокруг центра холста, центр холста стоит в (центр экрана + pan).
// Экспорт всегда рисуется без поворота и отражения (beginExport сбрасывает вид), поэтому
// поворот вида влияет только на удобство рисования, а не на результат.
#pragma once

#include <algorithm>
#include <cmath>

namespace vt {

struct ViewAffine {
    float k = 1.f;
    // L по столбцам: (la, lb) - образ оси X холста, (lc, ld) - образ оси Y
    float la = 1.f, lb = 0.f, lc = 0.f, ld = 1.f;
    float ox = 0.f, oy = 0.f;

    void apply(float x, float y, float& sx, float& sy) const {
        sx = ox + k * (la * x + lc * y);
        sy = oy + k * (lb * x + ld * y);
    }

    // Обратное преобразование: L ортонормирована, поэтому L^-1 = L^T.
    void invert(float sx, float sy, float& x, float& y) const {
        const float kk = k > 1e-12f ? k : 1e-12f;
        const float dx = (sx - ox) / kk, dy = (sy - oy) / kk;
        x = la * dx + lb * dy;
        y = lc * dx + ld * dy;
    }

    // Полная матрица холст -> экран в формате [a b c d tx ty] (как у слоёв).
    void matrix(float m[6]) const {
        m[0] = k * la; m[1] = k * lb; m[2] = k * lc; m[3] = k * ld; m[4] = ox; m[5] = oy;
    }

    // Без поворота и отражений: можно обрезать холст обычным glScissor.
    bool axisAligned() const {
        return std::fabs(lb) < 1e-6f && std::fabs(lc) < 1e-6f && la > 0.f && ld > 0.f;
    }
};

// fitMargin: 0.95 в редакторе (поля 5%), 1.0 при экспорте. rot - радианы, по часовой на экране.
inline ViewAffine makeView(int viewW, int viewH, int canvasW, int canvasH, float fitMargin,
                           float zoom, float panX, float panY, float rot, bool flipX, bool flipY) {
    ViewAffine v;
    const float cw = static_cast<float>(canvasW > 0 ? canvasW : 1);
    const float ch = static_cast<float>(canvasH > 0 ? canvasH : 1);
    const float vw = static_cast<float>(viewW > 0 ? viewW : 1);
    const float vh = static_cast<float>(viewH > 0 ? viewH : 1);
    const float fit = fitMargin * std::min(vw / cw, vh / ch);
    v.k = fit * (zoom > 0.f ? zoom : 1.f);
    const float cs = std::cos(rot), sn = std::sin(rot);
    const float fx = flipX ? -1.f : 1.f, fy = flipY ? -1.f : 1.f;
    // L = R(rot) * diag(fx, fy): сначала отражение в осях холста, затем поворот на экране
    v.la = cs * fx; v.lb = sn * fx;
    v.lc = -sn * fy; v.ld = cs * fy;
    // почти точные 0/90/180/270 градусов делаем точными: края холста без «лесенки»
    auto snap = [](float& a) { if (std::fabs(a) < 1e-6f) a = 0.f; };
    snap(v.la); snap(v.lb); snap(v.lc); snap(v.ld);
    const float ccx = cw * 0.5f, ccy = ch * 0.5f;
    v.ox = vw * 0.5f + panX - v.k * (v.la * ccx + v.lc * ccy);
    v.oy = vh * 0.5f + panY - v.k * (v.lb * ccx + v.ld * ccy);
    return v;
}

// Матрица mat3 (по столбцам) для шейдера: единичный квадрат слоя (u, v) -> холст по m[6] -> экран -> NDC.
inline void composeMvp(const ViewAffine& v, const float m[6], int width, int height, float o[9]) {
    float vm[6];
    v.matrix(vm);
    // S = V * M
    const float A = vm[0] * m[0] + vm[2] * m[1];
    const float B = vm[1] * m[0] + vm[3] * m[1];
    const float C = vm[0] * m[2] + vm[2] * m[3];
    const float D = vm[1] * m[2] + vm[3] * m[3];
    const float TX = vm[0] * m[4] + vm[2] * m[5] + vm[4];
    const float TY = vm[1] * m[4] + vm[3] * m[5] + vm[5];
    const float w = static_cast<float>(width > 0 ? width : 1), h = static_cast<float>(height > 0 ? height : 1);
    o[0] = A / w * 2.f;  o[1] = -B / h * 2.f;  o[2] = 0.f;
    o[3] = C / w * 2.f;  o[4] = -D / h * 2.f;  o[5] = 0.f;
    o[6] = TX / w * 2.f - 1.f;
    o[7] = 1.f - TY / h * 2.f;
    o[8] = 1.f;
}

// Экранная рамка (bounding box) холста: x0, y0, x1, y1.
inline void canvasScreenBounds(const ViewAffine& v, int canvasW, int canvasH, float out[4]) {
    const float xs[4] = {0.f, static_cast<float>(canvasW), static_cast<float>(canvasW), 0.f};
    const float ys[4] = {0.f, 0.f, static_cast<float>(canvasH), static_cast<float>(canvasH)};
    out[0] = out[1] = 1e30f;
    out[2] = out[3] = -1e30f;
    for (int i = 0; i < 4; ++i) {
        float sx, sy;
        v.apply(xs[i], ys[i], sx, sy);
        out[0] = std::min(out[0], sx); out[1] = std::min(out[1], sy);
        out[2] = std::max(out[2], sx); out[3] = std::max(out[3], sy);
    }
}

}  // namespace vt
