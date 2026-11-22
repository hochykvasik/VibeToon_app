// Нативные тесты Sprint 6.9.1 на хосте, без GL и Android:
//  - очередь загрузок текстур: новая картинка того же слоя заменяет ожидающую (память не растёт при смене маркеров);
//  - уменьшение RGBA вдвое для запасного пути бюджета текстур (слой не пропадает).
#include <cstdio>

#include "render/TexMath.h"
#include "render/UploadQueue.cpp"

static int failures = 0;
static int checks = 0;
#define CHECK(c) do { ++checks; if (!(c)) { ++failures; std::printf("FAIL line %d: %s\n", __LINE__, #c); } } while (0)

static vt::PendingUpload make(int id, int w, int h, uint8_t fill) {
    vt::PendingUpload u;
    u.layerId = id; u.width = w; u.height = h;
    u.rgba.assign(static_cast<size_t>(w) * h * 4, fill);
    return u;
}

static void queueCoalescesSameLayer() {
    vt::uploadQueue::clear();
    vt::uploadQueue::push(make(100001, 4, 4, 1));
    vt::uploadQueue::push(make(7, 2, 2, 2));
    vt::uploadQueue::push(make(100001, 8, 8, 3));   // новый кадр символа заменяет ожидающий
    CHECK(vt::uploadQueue::bytes() == 8u * 8 * 4 + 2u * 2 * 4);
    vt::PendingUpload a, b, c;
    CHECK(vt::uploadQueue::pop(a));
    CHECK(a.layerId == 100001 && a.width == 8 && a.rgba[0] == 3);   // место в очереди прежнее, данные новые
    CHECK(vt::uploadQueue::pop(b));
    CHECK(b.layerId == 7);
    CHECK(!vt::uploadQueue::pop(c));
    CHECK(vt::uploadQueue::bytes() == 0u);
    CHECK(vt::uploadQueue::empty());
}

static void halveAveragesAndKeepsOddEdge() {
    // 3x2: колонки (10,20,30) / (30,40,50): нечётная правая колонка повторяется
    std::vector<uint8_t> px;
    const int vals[6] = {10, 20, 30, 30, 40, 50};
    for (int v : vals) for (int c = 0; c < 4; ++c) px.push_back(static_cast<uint8_t>(v));
    int w = 3, h = 2;
    vt::halveRgba(px, w, h);
    CHECK(w == 1 && h == 1);
    CHECK(px.size() == 4u);
    CHECK(px[0] == 25);   // (10+20+30+40)/4
    CHECK(px[3] == 25);
}

static void halveUntilFitsBudget() {
    // 4096x4096 с mip = 85 МБ; в 32 МБ помещается только после уменьшения до 2048x2048 (21 МБ)
    int w = 4096, h = 4096;
    std::vector<uint8_t> px(static_cast<size_t>(w) * h * 4, 200);
    const size_t budget = 32u * 1024 * 1024;
    int steps = 0;
    while (vt::textureBytesWithMips(w, h) > budget && w >= 128 && h >= 128) { vt::halveRgba(px, w, h); ++steps; }
    CHECK(steps == 1);
    CHECK(w == 2048 && h == 2048);
    CHECK(px.size() == static_cast<size_t>(w) * h * 4);
    CHECK(px[12345] == 200);   // однородная картинка не меняет цвет
}

int main() {
    queueCoalescesSameLayer();
    halveAveragesAndKeepsOddEdge();
    halveUntilFitsBudget();
    std::printf("TexMathTest: %d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
