#pragma once
// Спринт 43: проверка пакета сеток ПО КАЖДОЙ СЕТКЕ ОТДЕЛЬНО. Раньше одно рассогласование длины массивов
// сбрасывало ВСЕ сетки сцены («каскад»: новая сетка челюсти выключала глаз и провод). Теперь корректные сетки
// до места ошибки сохраняются, а сетка с неверными счётчиками пропускается только сама.

namespace vt::meshpack {

// Индексы рисуются GLushort: не больше 65535 вершин в одной сетке.
constexpr long long kMaxVerts = 65535;

constexpr bool meshValid(long long vc, long long tc) {
    return vc > 0 && vc <= kMaxVerts && tc > 0 && tc <= kMaxVerts * 8;
}

// Сколько первых сеток целиком помещается в массивы verts (4 float на вершину) и tris (3 индекса на треугольник).
// Сетки с неверными счётчиками в префикс входят (они будут пропущены), но не сдвигают смещения в отрицательную сторону.
inline int fittingPrefix(const int* counts, int n, long long vLen, long long tLen) {
    long long vo = 0, to = 0;
    for (int i = 0; i < n; ++i) {
        const long long vc = counts[i * 2] > 0 ? counts[i * 2] : 0;
        const long long tc = counts[i * 2 + 1] > 0 ? counts[i * 2 + 1] : 0;
        if (vo + vc * 4 > vLen || to + tc * 3 > tLen) return i;
        vo += vc * 4;
        to += tc * 3;
    }
    return n;
}

}  // namespace vt::meshpack
