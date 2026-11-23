// Нативный тест границ слоя/охвата эффектов (scissor и отсечение в Renderer) и смещения mip в экспорте.
#include "render/LayerBounds.h"
#include "render/CanvasClipPolicy.h"
#include <cassert>
#include <cmath>
#include <cstdio>

using namespace vt;

static bool near(float a, float b) { return std::fabs(a - b) < 1e-3f; }

int main() {
    // Вид: масштаб 0.5, сдвиг (10, 20). Слой 100x50 в точке (40, 60).
    const float view[6] = {0.5f, 0.f, 0.f, 0.5f, 10.f, 20.f};
    const float layer[6] = {100.f, 0.f, 0.f, 50.f, 40.f, 60.f};
    ScreenRect r = layerScreenRect(view, layer);
    assert(near(r.x0, 30.f) && near(r.y0, 50.f) && near(r.x1, 80.f) && near(r.y1, 75.f));

    // Поворот на 90°: AABB остаётся корректным.
    const float rot[6] = {0.f, 100.f, -100.f, 0.f, 200.f, 0.f};
    const float ident[6] = {1.f, 0.f, 0.f, 1.f, 0.f, 0.f};
    r = layerScreenRect(ident, rot);
    assert(near(r.x0, 100.f) && near(r.x1, 200.f) && near(r.y0, 0.f) && near(r.y1, 100.f));

    // Вне экрана - отсекается; частично видимый - нет.
    assert(outsideTarget({-50.f, 10.f, -1.f, 20.f}, 100.f, 100.f));
    assert(outsideTarget({10.f, 101.f, 20.f, 150.f}, 100.f, 100.f));
    assert(!outsideTarget({-50.f, 10.f, 5.f, 20.f}, 100.f, 100.f));

    // NaN в матрице -> пустая рамка (слой не рисуется, без падений).
    const float bad[6] = {NAN, 0.f, 0.f, 1.f, 0.f, 0.f};
    assert(layerScreenRect(ident, bad).empty());

    // Охват эффектов: цветовые - 0, размытие - радиус, тень - смещение + радиус, глобальные - без ограничения.
    const float cc[10] = {2, .5f, .1f, .2f, 0, 0, 0, 0, 0, 0};
    const float blur[10] = {1, 12, 0, 0, 0, 0, 0, 0, 0, 0};
    const float shadow[10] = {5, 6, -8, 4, .5f, 0, 0, 0, 0, 0};
    const float glow[10] = {4, 1.5f, 9, 0, 0, 0, 0, 0, 0, 0};
    const float mirror[10] = {10, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    const float zoom[10] = {15, .3f, 0, 0, 0, 0, 0, 0, 0, 0};
    assert(near(effectReach(cc), 0.f));
    assert(near(effectReach(blur), 12.f));
    assert(near(effectReach(shadow), 18.f));
    assert(near(effectReach(glow), 9.f));
    assert(effectReach(mirror) < 0.f);
    assert(effectReach(zoom) < 0.f);
    const float* chain1[3] = {cc, blur, shadow};
    assert(near(chainReach(chain1, 3), 30.f));
    const float* chain2[2] = {blur, mirror};
    assert(chainReach(chain2, 2) < 0.f);
    assert(near(chainReach(nullptr, 0), 0.f));

    // Scissor: y переворачивается (GL снизу), дробные края расширяются наружу.
    int box[4];
    assert(scissorBox({10.2f, 20.7f, 30.1f, 40.f}, 100, box));
    assert(box[0] == 10 && box[1] == 60 && box[2] == 21 && box[3] == 20);
    assert(!scissorBox({5.f, 5.f, 5.f, 9.f}, 100, box));

    // Пересечение с целью.
    const ScreenRect c = intersect(expand({-10.f, 90.f, 20.f, 120.f}, 5.f), 100.f, 100.f);
    assert(near(c.x0, 0.f) && near(c.y0, 85.f) && near(c.x1, 25.f) && near(c.y1, 100.f));

    // Экспорт выбирает более чёткий mip-уровень, редактор - нет.
    assert(exportLodBias(true) < 0.f && exportLodBias(true) >= -1.f);
    assert(exportLodBias(false) == 0.f);
    assert(clipSceneToCanvas(true) && !clipSceneToCanvas(false));

    std::puts("LayerBoundsTest OK");
    return 0;
}
