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
    // спринт 46: квадрат обрезанного слоя = видимая рамка; текстура берётся из рамки (uUvRect)
    { float u[4]; uvRect(nullptr, u); assert(u[2] == 0.f && u[3] == 0.f);   // w <= 0 - вся текстура
      const float c[4] = {0.f, 0.1f, 1.f, 0.8f}; uvRect(c, u);
      assert(u[0] == 0.f && std::fabs(u[1] - 0.1f) < 1e-6f && u[2] == 1.f && std::fabs(u[3] - 0.7f) < 1e-6f);
      float tu, tv; toTexture(c, 0.f, 0.f, tu, tv); assert(tu == 0.f && std::fabs(tv - 0.1f) < 1e-6f);
      toTexture(c, 1.f, 1.f, tu, tv); assert(tu == 1.f && std::fabs(tv - 0.8f) < 1e-6f);
      toTexture(c, 0.5f, 0.5f, tu, tv); assert(std::fabs(tv - 0.45f) < 1e-6f); }
    // форма символа обрезанного экземпляра: под-прямоугольник в долях рамки и отбрасывание в долях формы
    { const float c[4] = {0.f, 0.f, 0.5f, 1.f};          // видна левая половина экземпляра
      const float rect[4] = {0.25f, 0.f, 0.5f, 1.f};     // форма занимает середину квадрата
      float r2[4]; aliasRect(c, rect, r2);
      assert(std::fabs(r2[0] - 0.5f) < 1e-6f && std::fabs(r2[2] - 1.f) < 1e-6f && r2[1] == 0.f && r2[3] == 1.f);
      float fc[4]; formCrop(c, rect, fc);
      assert(std::fabs(fc[0] + 0.5f) < 1e-6f && std::fabs(fc[2] - 0.5f) < 1e-6f);   // видна левая половина формы
      assert(inside(fc, 0.25f, 0.5f) && !inside(fc, 0.75f, 0.5f)); }
    std::puts("CropPolicyTest OK");
    return 0;
}
