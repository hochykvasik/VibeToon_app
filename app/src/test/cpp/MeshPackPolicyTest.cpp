// Спринт 43: пакет сеток проверяется по каждой сетке - ошибка одной не сбрасывает остальные.
#include "render/MeshPackPolicy.h"
#include <cassert>
#include <cstdio>

using namespace vt::meshpack;

int main() {
    static_assert(meshValid(3, 1), "triangle");
    static_assert(meshValid(1089, 2048), "dense warp 33x33");
    assert(!meshValid(0, 1));
    assert(!meshValid(3, 0));
    assert(!meshValid(-5, 1));
    assert(!meshValid(70000, 10));

    // глаз (4 вершины, 2 треугольника), провод (6, 4), челюсть (9, 8)
    const int counts[] = {4, 2, 6, 4, 9, 8};
    const long long v = (4 + 6 + 9) * 4, t = (2 + 4 + 8) * 3;
    assert(fittingPrefix(counts, 3, v, t) == 3);
    // массив вершин челюсти обрезан: глаз и провод сохраняются
    assert(fittingPrefix(counts, 3, v - 1, t) == 2);
    assert(fittingPrefix(counts, 3, v, (2 + 4) * 3) == 2);
    assert(fittingPrefix(counts, 3, 0, 0) == 0);
    // отрицательные счётчики не сдвигают смещения назад
    const int bad[] = {-100, -100, 4, 2};
    assert(fittingPrefix(bad, 2, 16, 6) == 2);

    std::printf("MeshPackPolicyTest OK\n");
    return 0;
}
