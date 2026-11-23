// Нативный тест форм символов (пакет 5): под-прямоугольник формы в квадрате экземпляра и попадание пальцем.
#include "render/TexAlias.h"
#include <cassert>
#include <cmath>
#include <cstdio>

using namespace vt::texalias;

static bool near(float a, float b) { return std::fabs(a - b) < 1e-4f; }

int main() {
    // Экземпляр 1000x800 в точке (100, 50); рот обрезан в прямоугольник x 0.4..0.6, y 0.7..0.8 квадрата.
    const float m[6] = {1000.f, 0.f, 0.f, 800.f, 100.f, 50.f};
    const float rect[4] = {0.4f, 0.7f, 0.2f, 0.1f};
    float sub[6];
    subRect(m, rect, sub);
    // угол (0,0) формы = точка квадрата (0.4, 0.7) = (500, 610); угол (1,1) = (0.6, 0.8) = (700, 690)
    assert(near(sub[4], 500.f) && near(sub[5], 610.f));
    assert(near(sub[0] + sub[2] + sub[4], 700.f) && near(sub[1] + sub[3] + sub[5], 690.f));

    // Поворот экземпляра на 90°: под-прямоугольник поворачивается вместе с ним.
    const float rot[6] = {0.f, 100.f, -100.f, 0.f, 200.f, 0.f};
    const float half[4] = {0.5f, 0.f, 0.5f, 1.f};
    subRect(rot, half, sub);
    assert(near(sub[4], 200.f) && near(sub[5], 50.f));

    // Полный прямоугольник - та же матрица.
    const float full[4] = {0.f, 0.f, 1.f, 1.f};
    assert(isFull(full) && !isFull(rect));
    subRect(m, full, sub);
    for (int i = 0; i < 6; ++i) assert(near(sub[i], m[i]));

    // Попадание: середина рта - внутри формы, лоб - мимо (под экземпляром выбирается слой ниже).
    float su = 0.f, sv = 0.f;
    assert(toForm(0.5f, 0.75f, rect, su, sv) && near(su, 0.5f) && near(sv, 0.5f));
    assert(!toForm(0.5f, 0.2f, rect, su, sv));
    assert(!toForm(0.6f, 0.75f, rect, su, sv));   // правая граница не включается

    // Вырожденный прямоугольник -> весь квадрат, без деления на ноль.
    float bad[4] = {0.3f, 0.3f, 0.f, 0.f};
    assert(!toForm(0.3f, 0.3f, bad, su, sv));
    sanitize(bad);
    assert(isFull(bad));

    std::puts("TexAliasTest OK");
    return 0;
}
