// Очередь готовых RGBA-буферов: IO-поток кладёт, GL-поток забирает и грузит в текстуры.
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace vt {

struct PendingUpload {
    int layerId = 0;
    int width = 0, height = 0;
    std::vector<uint8_t> rgba;
};

namespace uploadQueue {
void push(PendingUpload&& item);
bool pop(PendingUpload& out);   // false, если очередь пуста
bool empty();
size_t bytes();
void clear();     // выбросить все ожидающие загрузки (при смене проекта id слоёв пересекаются)   // суммарный размер буферов в очереди (для backpressure)
}  // namespace uploadQueue

}  // namespace vt
