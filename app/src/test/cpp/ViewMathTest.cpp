// Нативные тесты математики вида (render/ViewMath.h) на хосте, без GL и Android:
//   g++ -std=c++17 -I app/src/main/cpp app/src/test/cpp/ViewMathTest.cpp -o /tmp/vt_viewmath && /tmp/vt_viewmath
// Те же формулы на Kotlin (domain/render/Camera.kt) проверяет ViewCameraTest.
#include <cmath>
#include <cstdio>

#include "render/ViewMath.h"

static int failures = 0;
static int checks = 0;

static void near(float a, float b, float eps, const char* what, int line) {
    ++checks;
    if (!(std::fabs(a - b) <= eps)) {
        ++failures;
        std::printf("FAIL line %d: %s: %f != %f\n", line, what, a, b);
    }
}
#define NEAR(a, b, eps) near((a), (b), (eps), #a " ~ " #b, __LINE__)
#define CHECK(c) do { ++checks; if (!(c)) { ++failures; std::printf("FAIL line %d: %s\n", __LINE__, #c); } } while (0)

static void identityMatchesLegacyFormula() {
    // старая формула Renderer::viewTransform: ox = cx + (fx - cx) * zoom + pan
    const int vw = 1000, vh = 800, cw = 1920, ch = 1080;
    const float zoom = 1.7f, panX = 12.f, panY = -30.f;
    vt::ViewAffine v = vt::makeView(vw, vh, cw, ch, 0.95f, zoom, panX, panY, 0.f, false, false);
    const float fit = 0.95f * std::min(float(vw) / cw, float(vh) / ch);
    const float fx = (vw - cw * fit) * 0.5f, fy = (vh - ch * fit) * 0.5f;
    NEAR(v.k, fit * zoom, 1e-5f);
    NEAR(v.ox, vw * 0.5f + (fx - vw * 0.5f) * zoom + panX, 1e-2f);
    NEAR(v.oy, vh * 0.5f + (fy - vh * 0.5f) * zoom + panY, 1e-2f);
    CHECK(v.axisAligned());
}

static void roundTripWithRotationAndFlip() {
    vt::ViewAffine v = vt::makeView(1000, 800, 1920, 1080, 0.95f, 1.3f, 40.f, 10.f, 0.7f, true, false);
    float sx, sy, x, y;
    v.apply(123.f, 456.f, sx, sy);
    v.invert(sx, sy, x, y);
    NEAR(x, 123.f, 1e-2f);
    NEAR(y, 456.f, 1e-2f);
    // центр холста - в центре вида + pan
    v.apply(960.f, 540.f, sx, sy);
    NEAR(sx, 540.f, 1e-2f);
    NEAR(sy, 410.f, 1e-2f);
    CHECK(!v.axisAligned());
}

static void rightAngleIsExact() {
    vt::ViewAffine v = vt::makeView(1000, 800, 100, 100, 0.95f, 1.f, 0.f, 0.f, 1.5707964f, false, false);
    CHECK(v.la == 0.f);
    CHECK(v.ld == 0.f);
    CHECK(v.lb > 0.f);   // ось X холста смотрит вниз: по часовой
}

static void flipMirrorsAxis() {
    vt::ViewAffine v = vt::makeView(800, 800, 100, 100, 1.f, 1.f, 0.f, 0.f, 0.f, true, false);
    float l, t, r, b;
    v.apply(0.f, 0.f, l, t);
    v.apply(100.f, 100.f, r, b);
    CHECK(l > r);        // левый край холста справа
    CHECK(t < b);        // по вертикали без изменений
    CHECK(!v.axisAligned());
}

static void exportViewIsIdentity() {
    // экспорт: поля 1.0, zoom 1, без поворота и отражения -> холст ровно на всю цель
    vt::ViewAffine v = vt::makeView(1920, 1080, 1920, 1080, 1.f, 1.f, 0.f, 0.f, 0.f, false, false);
    NEAR(v.k, 1.f, 1e-6f);
    NEAR(v.ox, 0.f, 1e-4f);
    NEAR(v.oy, 0.f, 1e-4f);
    float m[6] = {1920.f, 0.f, 0.f, 1080.f, 0.f, 0.f};   // слой на весь холст
    float o[9];
    vt::composeMvp(v, m, 1920, 1080, o);
    // единичный квадрат -> NDC: (0,0) -> (-1, 1), (1,1) -> (1, -1)
    NEAR(o[6], -1.f, 1e-5f);
    NEAR(o[7], 1.f, 1e-5f);
    NEAR(o[0] + o[6], 1.f, 1e-5f);
    NEAR(o[4] + o[7], -1.f, 1e-5f);
}

static void boundsCoverRotatedCanvas() {
    vt::ViewAffine v = vt::makeView(1000, 1000, 100, 100, 1.f, 1.f, 0.f, 0.f, 0.785398f, false, false);
    float bb[4];
    vt::canvasScreenBounds(v, 100, 100, bb);
    const float side = 1000.f * std::sqrt(2.f);   // квадрат 1000 px повёрнут на 45°: диагональ
    NEAR(bb[2] - bb[0], side, 0.5f);
    NEAR(bb[3] - bb[1], side, 0.5f);
    NEAR((bb[0] + bb[2]) * 0.5f, 500.f, 0.5f);
}

int main() {
    identityMatchesLegacyFormula();
    roundTripWithRotationAndFlip();
    rightAngleIsExact();
    flipMirrorsAxis();
    exportViewIsIdentity();
    boundsCoverRotatedCanvas();
    std::printf("ViewMath native tests: %d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
