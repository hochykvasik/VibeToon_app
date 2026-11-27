// Спринт 31: чистые правила пула CPU-буферов загрузки текстур и выбора пути через PBO.
// Без GL и без глобального состояния - проверяется нативным тестом в CI (UploadBufferPolicyTest.cpp).
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace vt {
namespace uploadbuf {

constexpr size_t kMiB = 1024u * 1024u;
// Сколько буферов держит пул: кадр видео/рисунка в полёте + следующий + запас для второго слоя.
constexpr size_t kMaxBuffers = 4;
// Предел памяти пула: 4 кадра 1920x1080 RGBA (~8 МБ каждый) с запасом.
constexpr size_t kMaxPoolBytes = 48u * kMiB;
// Слишком большие буферы (например, 4096x4096) не задерживаются: это разовая загрузка, а не поток кадров.
constexpr size_t kMaxSingleBytes = 34u * kMiB;
// Буфер берётся повторно, только если он не больше чем вдвое (+64 КБ) превышает нужный размер:
// крупный буфер не застревает на мелких миниатюрах.
constexpr size_t kSlackBytes = 64u * 1024u;
// Меньше этого PBO не даёт выигрыша (накладные расходы вызова больше копии).
constexpr size_t kPboMinBytes = 256u * 1024u;

inline bool reusable(size_t capacity, size_t need) {
    return need > 0 && capacity >= need && capacity <= need * 2 + kSlackBytes;
}

// Индекс самого маленького подходящего буфера или -1 (тогда выделяется новый).
inline int bestFit(const std::vector<size_t>& capacities, size_t need) {
    int best = -1;
    for (size_t i = 0; i < capacities.size(); ++i) {
        if (!reusable(capacities[i], need)) continue;
        if (best < 0 || capacities[i] < capacities[static_cast<size_t>(best)]) best = static_cast<int>(i);
    }
    return best;
}

// Возвращать ли освободившийся буфер в пул (иначе память отдаётся системе сразу).
inline bool keep(size_t poolCount, size_t poolBytes, size_t capacity) {
    return capacity > 0 && capacity <= kMaxSingleBytes && poolCount < kMaxBuffers &&
           poolBytes + capacity <= kMaxPoolBytes;
}

// Строки Bitmap идут подряд без выравнивания: весь кадр копируется одним memcpy.
inline bool singleCopy(uint32_t stride, uint32_t width) {
    return static_cast<uint64_t>(stride) == static_cast<uint64_t>(width) * 4u;
}

// Загрузка через GL_PIXEL_UNPACK_BUFFER (ES 3): только для повторной записи в текстуру того же размера,
// крупных кадров и пока драйвер не вернул ошибку на этом пути.
inline bool usePbo(bool broken, size_t bytes) { return !broken && bytes >= kPboMinBytes; }

}  // namespace uploadbuf
}  // namespace vt
