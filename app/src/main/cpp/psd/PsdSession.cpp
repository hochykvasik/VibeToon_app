#include "psd/PsdSession.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>

#include "Psd/PsdChannel.h"
#include "Psd/PsdChannelType.h"
#include "Psd/PsdLayer.h"
#include "Psd/PsdParseDocument.h"
#include "Psd/PsdParseLayerMaskSection.h"
#include "common/Log.h"

namespace vt {

std::unique_ptr<PsdSession> PsdSession::open(const std::string& path, std::string& error) {
    std::unique_ptr<PsdSession> s(new PsdSession());
    s->file_ = std::make_unique<AndroidFile>(&s->allocator_, path);
    if (!s->file_->OpenRead(L"")) {
        error = "open failed: " + path;
        return nullptr;
    }
    s->doc_ = psd::CreateDocument(s->file_.get(), &s->allocator_);
    if (!s->doc_) {
        error = "not a PSD";
        return nullptr;
    }
    if (s->doc_->isLargeDocument) {
        error = "PSB not supported";
        return nullptr;
    }
    s->sec_ = psd::ParseLayerMaskSection(s->doc_, s->file_.get(), &s->allocator_);
    if (!s->sec_) {
        error = "ParseLayerMaskSection failed";
        return nullptr;
    }
    return s;
}

PsdSession::~PsdSession() {
    if (sec_) psd::DestroyLayerMaskSection(sec_, &allocator_);
    if (doc_) psd::DestroyDocument(doc_, &allocator_);
    if (file_) file_->Close();
}

bool PsdSession::decode(int layerId, float scale, int maxTex, DecodedLayer& out) {
    std::lock_guard<std::mutex> lock(mu_);
    if (layerId < 0 || static_cast<unsigned>(layerId) >= sec_->layerCount) return false;
    if (doc_->bitsPerChannel != 8) {
        LOGW("decode: only 8 bits per channel supported");
        return false;
    }
    psd::Layer& layer = sec_->layers[layerId];
    const int w = layer.right - layer.left;
    const int h = layer.bottom - layer.top;
    if (w <= 0 || h <= 0) return false;

    // Целевой размер: не больше желаемого масштаба и не больше maxTex.
    float s = std::min({scale, 1.f, static_cast<float>(maxTex) / static_cast<float>(w),
                        static_cast<float>(maxTex) / static_cast<float>(h)});
    const int tw = std::max(1, static_cast<int>(std::lround(w * s)));
    const int th = std::max(1, static_cast<int>(std::lround(h * s)));

    auto t0 = std::chrono::steady_clock::now();
    psd::ExtractLayer(doc_, file_.get(), &allocator_, &layer);  // пики памяти: ~4*w*h байт

    const uint8_t* ch[4] = {nullptr, nullptr, nullptr, nullptr};  // R, G, B, A
    for (unsigned i = 0; i < layer.channelCount; ++i) {
        const psd::Channel& c = layer.channels[i];
        if (!c.data) continue;
        if (c.type == psd::channelType::R) ch[0] = static_cast<const uint8_t*>(c.data);
        else if (c.type == psd::channelType::G) ch[1] = static_cast<const uint8_t*>(c.data);
        else if (c.type == psd::channelType::B) ch[2] = static_cast<const uint8_t*>(c.data);
        else if (c.type == psd::channelType::TRANSPARENCY_MASK) ch[3] = static_cast<const uint8_t*>(c.data);
    }

    bool ok = ch[0] && ch[1] && ch[2];
    if (ok) {
        out.layerId = layerId;
        out.width = tw;
        out.height = th;
        out.rgba.assign(static_cast<size_t>(tw) * th * 4, 0);
        // Box-фильтр: усредняем исходные пиксели, попадающие в целевой пиксель.
        // Усредняем premultiplied значения, чтобы на краях не было тёмной каймы.
        for (int ty = 0; ty < th; ++ty) {
            const int sy0 = static_cast<int>(static_cast<int64_t>(ty) * h / th);
            const int sy1 = std::max(sy0 + 1, static_cast<int>(static_cast<int64_t>(ty + 1) * h / th));
            for (int tx = 0; tx < tw; ++tx) {
                const int sx0 = static_cast<int>(static_cast<int64_t>(tx) * w / tw);
                const int sx1 = std::max(sx0 + 1, static_cast<int>(static_cast<int64_t>(tx + 1) * w / tw));
                uint64_t sa = 0, sr = 0, sg = 0, sb = 0;
                for (int y = sy0; y < sy1; ++y) {
                    const size_t row = static_cast<size_t>(y) * w;
                    for (int x = sx0; x < sx1; ++x) {
                        const size_t i = row + x;
                        const uint32_t a = ch[3] ? ch[3][i] : 255u;
                        sa += a;
                        sr += ch[0][i] * a;
                        sg += ch[1][i] * a;
                        sb += ch[2][i] * a;
                    }
                }
                const uint64_t n = static_cast<uint64_t>(sy1 - sy0) * (sx1 - sx0);
                uint8_t* px = &out.rgba[(static_cast<size_t>(ty) * tw + tx) * 4];
                px[0] = static_cast<uint8_t>(sr / (255 * n));
                px[1] = static_cast<uint8_t>(sg / (255 * n));
                px[2] = static_cast<uint8_t>(sb / (255 * n));
                px[3] = static_cast<uint8_t>(sa / n);
            }
        }
    } else {
        LOGW("layer %d: RGB channels missing", layerId);
    }

    // Сразу освобождаем полноразмерные каналы (повторный ExtractLayer требует data == nullptr).
    for (unsigned i = 0; i < layer.channelCount; ++i) {
        if (layer.channels[i].data) {
            allocator_.Free(layer.channels[i].data);
            layer.channels[i].data = nullptr;
        }
    }
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - t0).count();
    LOGI("decode layer %d: %dx%d -> %dx%d (%.1f MB) %lld ms", layerId, w, h, tw, th,
         tw * th * 4 / 1048576.0, static_cast<long long>(ms));
    return ok;
}

}  // namespace vt
