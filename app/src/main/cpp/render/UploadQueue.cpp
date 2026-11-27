#include "render/UploadQueue.h"
#include "render/UploadBufferPolicy.h"

#include <algorithm>
#include <deque>
#include <mutex>
#include <new>
#include <unordered_map>

namespace vt {
namespace uploadQueue {
namespace {
std::mutex g_mu;
std::deque<PendingUpload> g_queue;
std::deque<PendingUpload> g_shadow;
size_t g_bytes = 0;
uint32_t g_epoch = 1;
std::unordered_map<int, uint32_t> g_generation;
// Пул буферов живёт под своим mutex: загрузчик и GL-поток не ждут очередь ради выделения памяти.
std::mutex g_poolMu;
std::vector<std::vector<uint8_t>> g_pool;
size_t g_poolBytes = 0;
uint64_t currentTicket(int id) {
    auto it = g_generation.find(id);
    return (static_cast<uint64_t>(g_epoch) << 32) | (it == g_generation.end() ? 0 : it->second);
}
}  // namespace

EmptyGuard::EmptyGuard() : lock_(g_mu), acquired_(g_queue.empty()) {
    if (!acquired_) lock_.unlock();
}

bool EmptyGuard::prepareLayer(int layerId) {
    if (!acquired_) return false;
    try { g_generation.emplace(layerId, 0); return true; }
    catch (const std::bad_alloc&) { return false; }
}

void EmptyGuard::invalidateLayer(int layerId) {
    if (!acquired_) return;
    auto it = g_generation.find(layerId);
    if (it != g_generation.end()) ++it->second;
}

uint64_t ticket(int layerId) {
    std::lock_guard<std::mutex> l(g_mu);
    return currentTicket(layerId);
}

bool pushIfCurrent(PendingUpload&& item, uint64_t t) {
    std::lock_guard<std::mutex> l(g_mu);
    if (currentTicket(item.layerId) != t) return false;
    g_bytes += item.rgba.size();
    g_queue.push_back(std::move(item));
    return true;
}

void push(PendingUpload&& item) {
    std::lock_guard<std::mutex> l(g_mu);
    g_bytes += item.rgba.size();
    g_queue.push_back(std::move(item));
}

bool pop(PendingUpload& out) {
    std::lock_guard<std::mutex> l(g_mu);
    if (g_queue.empty()) return false;
    out = std::move(g_queue.front());
    g_queue.pop_front();
    g_bytes -= out.rgba.size();
    return true;
}

void pushShadow(PendingUpload&& item) {
    std::lock_guard<std::mutex> l(g_mu);
    g_bytes += item.rgba.size();
    g_shadow.push_back(std::move(item));
}

bool popShadow(PendingUpload& out) {
    std::lock_guard<std::mutex> l(g_mu);
    if (g_shadow.empty()) return false;
    out = std::move(g_shadow.front());
    g_shadow.pop_front();
    g_bytes -= out.rgba.size();
    return true;
}

bool empty() {
    std::lock_guard<std::mutex> l(g_mu);
    return g_queue.empty();
}

void clear() {
    {
        std::lock_guard<std::mutex> l(g_mu);
        g_queue.clear();
        g_shadow.clear();
        g_bytes = 0;
        ++g_epoch;
        g_generation.clear();
    }
    clearPool();   // смена проекта: память пула отдаётся системе (другой mutex, порядок захвата не пересекается)
}

std::vector<uint8_t> acquireBuffer(size_t bytes) {
    std::vector<uint8_t> out;
    {
        std::lock_guard<std::mutex> l(g_poolMu);
        std::vector<size_t> caps;
        caps.reserve(g_pool.size());
        for (const auto& b : g_pool) caps.push_back(b.capacity());
        const int i = uploadbuf::bestFit(caps, bytes);
        if (i >= 0) {
            out = std::move(g_pool[static_cast<size_t>(i)]);
            g_pool.erase(g_pool.begin() + i);
            g_poolBytes -= std::min(g_poolBytes, out.capacity());
        }
    }
    // Внутри capacity resize не перевыделяет память; уменьшение размера ничего не стоит.
    out.resize(bytes);
    return out;
}

void recycleBuffer(std::vector<uint8_t>&& buffer) {
    std::vector<uint8_t> b = std::move(buffer);
    std::lock_guard<std::mutex> l(g_poolMu);
    if (!uploadbuf::keep(g_pool.size(), g_poolBytes, b.capacity())) return;   // b освобождается здесь
    g_poolBytes += b.capacity();
    try { g_pool.push_back(std::move(b)); }
    catch (const std::bad_alloc&) { g_poolBytes -= std::min(g_poolBytes, b.capacity()); }
}

size_t poolCount() {
    std::lock_guard<std::mutex> l(g_poolMu);
    return g_pool.size();
}

size_t poolBytes() {
    std::lock_guard<std::mutex> l(g_poolMu);
    return g_poolBytes;
}

void clearPool() {
    std::lock_guard<std::mutex> l(g_poolMu);
    g_pool.clear();
    g_poolBytes = 0;
}

size_t bytes() {
    std::lock_guard<std::mutex> l(g_mu);
    return g_bytes;
}

}  // namespace uploadQueue
}  // namespace vt
