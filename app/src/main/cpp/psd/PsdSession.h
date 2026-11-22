// Открытый PSD-документ: держит файл, Document и LayerMaskSection до closeDocument.
// decode() извлекает пиксели ОДНОГО слоя, уменьшает и возвращает premultiplied RGBA.
// Потокобезопасность: ExtractLayer непотокобезопасен -> все decode() под mutex.
#pragma once
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Psd/Psd.h"
#include "Psd/PsdDocument.h"
#include "Psd/PsdLayerMaskSection.h"
#include "Psd/PsdMallocAllocator.h"
#include "psd/AndroidFile.h"

namespace vt {

struct DecodedLayer {
    int layerId = 0;
    int width = 0, height = 0;      // размеры ПОСЛЕ уменьшения
    std::vector<uint8_t> rgba;      // premultiplied alpha, строки сверху вниз
};

class PsdSession {
public:
    static std::unique_ptr<PsdSession> open(const std::string& path, std::string& error);
    ~PsdSession();

    unsigned width() const { return doc_->width; }
    unsigned height() const { return doc_->height; }

    // scale - желаемый масштаб превью; maxTex - предел стороны текстуры.
    // false: слой пустой/не RGB8/ошибка (причина в Logcat).
    bool decode(int layerId, float scale, int maxTex, DecodedLayer& out);

private:
    PsdSession() = default;

    psd::MallocAllocator allocator_;
    std::unique_ptr<AndroidFile> file_;
    psd::Document* doc_ = nullptr;
    psd::LayerMaskSection* sec_ = nullptr;
    std::mutex mu_;
};

}  // namespace vt
