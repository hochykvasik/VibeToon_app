// Чистая математика текстур без GL (тестируется на хосте: app/src/test/cpp/TexMathTest.cpp).
#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace vt {

// Уменьшение RGBA вдвое (среднее 2x2, нечётный край повторяется) - запасной путь, когда текстура не помещается
// в бюджет памяти GPU: слой остаётся видимым, а не пропадает.
inline void halveRgba(std::vector<uint8_t>& rgba, int& w, int& h) {
    const int nw = std::max(1, w / 2), nh = std::max(1, h / 2);
    std::vector<uint8_t> out(static_cast<size_t>(nw) * nh * 4);
    for (int y = 0; y < nh; ++y) {
        const int y0 = std::min(h - 1, y * 2), y1 = std::min(h - 1, y * 2 + 1);
        for (int x = 0; x < nw; ++x) {
            const int x0 = std::min(w - 1, x * 2), x1 = std::min(w - 1, x * 2 + 1);
            for (int c = 0; c < 4; ++c) {
                const int sum = rgba[(static_cast<size_t>(y0) * w + x0) * 4 + c] + rgba[(static_cast<size_t>(y0) * w + x1) * 4 + c] +
                                rgba[(static_cast<size_t>(y1) * w + x0) * 4 + c] + rgba[(static_cast<size_t>(y1) * w + x1) * 4 + c];
                out[(static_cast<size_t>(y) * nw + x) * 4 + c] = static_cast<uint8_t>((sum + 2) / 4);
            }
        }
    }
    rgba.swap(out);
    w = nw;
    h = nh;
}

// Байты текстуры с mip-цепочкой (как считает бюджет Renderer).
inline size_t textureBytesWithMips(int w, int h) { return static_cast<size_t>(w) * h * 4 * 4 / 3; }

}  // namespace vt
