// Очередь готовых RGBA-буферов: IO-поток кладёт, GL-поток забирает и грузит в текстуры.
#pragma once
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

namespace vt {

struct PendingUpload {
    int layerId = 0;
    int width = 0, height = 0;
    std::vector<uint8_t> rgba;
};

namespace uploadQueue {
// Блокирует постановку новых загрузок, только если очередь изначально пуста.
// Не защищает от устаревших загрузок, которые начались до и закончатся после guard.
class EmptyGuard {
public:
    EmptyGuard();
    bool acquired() const { return acquired_; }
    // Резервирует запись до выделения GL-текстуры, не меняя её поколение.
    bool prepareLayer(int layerId);
    // Вызывается только после успешной замены и prepareLayer, под блокировкой.
    void invalidateLayer(int layerId);
private:
    std::unique_lock<std::mutex> lock_;
    bool acquired_;
};
// Билет берётся ДО декодирования. Замена слоя и смена проекта делают старые билеты недействительными.
uint64_t ticket(int layerId);
bool pushIfCurrent(PendingUpload&& item, uint64_t ticket);
void push(PendingUpload&& item);
bool pop(PendingUpload& out);   // false, если очередь пуста
bool empty();
// Отдельная очередь «теневых» текстур снимка символа: они не заменяют текстуры слоёв редактора,
// а живут только на время одного снимка (см. Renderer::captureFrame с kCaptureShadow).
void pushShadow(PendingUpload&& item);
bool popShadow(PendingUpload& out);
size_t bytes();
// Спринт 31: пул CPU-буферов. acquireBuffer отдаёт вектор ровно нужного размера (повторно используя
// подходящий из пула), recycleBuffer возвращает буфер после загрузки в GL. Содержимое не обнуляется.
std::vector<uint8_t> acquireBuffer(size_t bytes);
void recycleBuffer(std::vector<uint8_t>&& buffer);
size_t poolCount();
size_t poolBytes();
void clearPool();
void clear();     // выбросить все ожидающие загрузки (при смене проекта id слоёв пересекаются)   // суммарный размер буферов в очереди (для backpressure)
}  // namespace uploadQueue

}  // namespace vt
