// Нативный тест кэша эффектов при движении камеры (пакет 4): преобразование вида, покрытие, пределы.
#include "render/FxCachePolicy.h"
#include <cassert>
#include <cmath>
#include <cstdio>

using namespace vt;
using namespace vt::fxcache;

static bool near(float a, float b, float e = 1e-3f) { return std::fabs(a - b) < e; }

int main() {
    // Тот же вид -> единичное преобразование: кэш берётся пиксель в пиксель.
    const float va[6] = {0.8f, 0.f, 0.f, 0.8f, 100.f, 50.f};
    float d[6];
    assert(screenDelta(va, va, d));
    assert(isIdentity(d));

    // Пан на (+30, -12): D - чистый сдвиг.
    const float panned[6] = {0.8f, 0.f, 0.f, 0.8f, 130.f, 38.f};
    assert(screenDelta(panned, va, d));
    assert(!isIdentity(d));
    assert(near(d[0], 1.f) && near(d[3], 1.f) && near(d[4], 30.f) && near(d[5], -12.f));
    assert(scaleAcceptable(d));

    // Зум x1.5 вокруг точки: масштаб D = 1.5, допустим; x3 - уже пересчёт.
    const float zoomed[6] = {1.2f, 0.f, 0.f, 1.2f, 40.f, 10.f};
    assert(screenDelta(zoomed, va, d));
    assert(near(scaleOf(d), 1.5f));
    assert(scaleAcceptable(d));
    const float far[6] = {2.4f, 0.f, 0.f, 2.4f, 0.f, 0.f};
    assert(screenDelta(far, va, d));
    assert(!scaleAcceptable(d));

    // Покрытие: сохранена рамка 100..300 x 100..200 (экран тогда). Пан на +20 по x:
    // нужная сейчас 130..310 соответствует 110..290 тогда - внутри; 130..340 -> 110..320 - выходит (пересчёт).
    assert(screenDelta(panned, va, d));
    float pan20[6] = {1.f, 0.f, 0.f, 1.f, 20.f, 0.f}, inv[6];
    assert(invert(pan20, inv));
    const ScreenRect cached{100.f, 100.f, 300.f, 200.f};
    assert(covers(cached, ScreenRect{130.f, 110.f, 310.f, 190.f}, inv));
    assert(!covers(cached, ScreenRect{130.f, 110.f, 340.f, 190.f}, inv));
    assert(covers(cached, ScreenRect{}, inv));   // пустая нужная область (слой за экраном)

    // Поворот вида на 90°: обратное преобразование корректно.
    const float rot[6] = {0.f, 1.f, -1.f, 0.f, 0.f, 0.f};
    float rinv[6], back[6];
    assert(invert(rot, rinv));
    compose(rot, rinv, back);
    assert(isIdentity(back));

    // Вырожденный вид - отказ, без NaN.
    const float flat[6] = {0.f, 0.f, 0.f, 0.f, 1.f, 1.f};
    assert(!screenDelta(va, flat, d));

    // Записи больше половины экрана не кэшируются (полноэкранные эффекты).
    assert(entryAllowed(300, 200, 1080, 1920));
    assert(!entryAllowed(1080, 1200, 1080, 1920));
    assert(!entryAllowed(0, 10, 1080, 1920));

    std::puts("FxCachePolicyTest OK");
    return 0;
}
