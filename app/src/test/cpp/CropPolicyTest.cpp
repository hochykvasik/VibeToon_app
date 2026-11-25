// Спринт 45: вкладка «Обрезка» - правила рамки в рендерере (CropPolicy.h).
#include "render/CropPolicy.h"
#include <cassert>
#include <cmath>
#include <cstdio>

using namespace vt::croppolicy;

int main() {
    // вся текстура / мусор - обрезки нет
    { float r[4] = {0.f, 0.f, 1.f, 1.f}; assert(!sanitize(r)); }
    { float r[4] = {-1.f, -2.f, 3.f, 4.f}; assert(!sanitize(r)); }
    { float r[4] = {NAN, 0.f, 1.f, 1.f}; assert(!sanitize(r)); }
    { float r[4] = {0.5f, 0.2f, 0.5f, 0.8f}; assert(!sanitize(r)); }   // нулевая ширина
    { float r[4] = {0.7f, 0.2f, 0.3f, 0.8f}; assert(!sanitize(r)); }   // l > r
    // нормальная рамка, выход за 0..1 прижимается
    { float r[4] = {0.1f, -0.5f, 0.9f, 0.8f}; assert(sanitize(r)); assert(r[1] == 0.f && r[0] == 0.1f && r[3] == 0.8f); }
    // inside: границы включительно, строка 0 - верх
    { const float r[4] = {0.25f, 0.25f, 0.75f, 0.5f};
      assert(inside(r, 0.5f, 0.3f)); assert(inside(r, 0.25f, 0.25f)); assert(inside(r, 0.75f, 0.5f));
      assert(!inside(r, 0.1f, 0.3f)); assert(!inside(r, 0.5f, 0.6f)); assert(!inside(r, 0.5f, 0.1f)); }
    // uniform: без рамки нули (шейдер ничего не отбрасывает), с рамкой - сама рамка
    { float u[4]; uniformValue(nullptr, u); assert(!shaderActive(u)); assert(u[0] == 0.f && u[2] == 0.f);
      const float r[4] = {0.1f, 0.2f, 0.3f, 0.4f}; uniformValue(r, u); assert(shaderActive(u)); assert(u[3] == 0.4f); }
    std::puts("CropPolicyTest OK");
    return 0;
}
