#include "psd/PsdLayerReader.h"

#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <fcntl.h>
#include <unistd.h>

#include "Psd/Psd.h"
#include "Psd/PsdMallocAllocator.h"
#include "Psd/PsdDocument.h"
#include "Psd/PsdParseDocument.h"
#include "Psd/PsdLayerMaskSection.h"
#include "Psd/PsdParseLayerMaskSection.h"
#include "Psd/PsdLayer.h"
#include "Psd/PsdLayerType.h"
#include "Psd/PsdColorMode.h"

#include "common/Log.h"
#include "psd/AndroidFile.h"

namespace vt {
namespace {

// Проверка заголовка: размер >= 26, сигнатура "8BPS", версия 1 (2 = PSB, не поддерживаем).
bool checkHeader(const std::string& path, std::string& err) {
    int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        err = "open failed errno=" + std::to_string(errno);
        return false;
    }
    uint8_t h[26];
    ssize_t n = ::pread(fd, h, sizeof h, 0);
    ::close(fd);
    if (n < 26 || h[0] != '8' || h[1] != 'B' || h[2] != 'P' || h[3] != 'S') {
        err = "not a PSD";
        return false;
    }
    int version = (h[4] << 8) | h[5];
    if (version != 1) {
        err = "PSB not supported (version=" + std::to_string(version) + ")";
        return false;
    }
    return true;
}

int mapKind(uint32_t type) {
    switch (type) {
        case psd::layerType::OPEN_FOLDER:     return 1;
        case psd::layerType::CLOSED_FOLDER:   return 2;
        case psd::layerType::SECTION_DIVIDER: return 3;
        case psd::layerType::ANY:
        default:                              return 0;
    }
}

// Имя слоя: UTF-16 если есть, иначе ASCII-имя (байты > 0x7F -> '?').
std::u16string layerName(const psd::Layer& l) {
    std::u16string s;
    if (l.utf16Name) {
        for (const uint16_t* p = l.utf16Name; *p; ++p) s.push_back(static_cast<char16_t>(*p));
    } else {
        for (const char* p = l.name.c_str(); *p; ++p) {
            unsigned char c = static_cast<unsigned char>(*p);
            s.push_back(c > 0x7F ? u'?' : static_cast<char16_t>(c));
        }
    }
    return s;
}

}  // namespace

ReadResult readPsdLayers(const std::string& path) {
    ReadResult r;
    auto t0 = std::chrono::steady_clock::now();

    if (!checkHeader(path, r.error)) return r;

    psd::MallocAllocator allocator;
    AndroidFile file(&allocator, path);
    if (!file.OpenRead(L"")) {
        r.error = "open failed: " + path;
        return r;
    }

    psd::Document* doc = psd::CreateDocument(&file, &allocator);
    if (!doc) {
        r.error = "CreateDocument failed";
        return r;
    }
    r.docWidth = doc->width;
    r.docHeight = doc->height;
    r.bitsPerChannel = doc->bitsPerChannel;
    r.colorMode = doc->colorMode;
    LOGI("document %ux%u bpc=%u mode=%u", doc->width, doc->height, doc->bitsPerChannel,
         doc->colorMode);
    if (doc->colorMode != psd::colorMode::RGB || doc->bitsPerChannel != 8)
        LOGW("file is not RGB/8bpc (mode=%u bpc=%u)", doc->colorMode, doc->bitsPerChannel);

    psd::LayerMaskSection* sec = psd::ParseLayerMaskSection(doc, &file, &allocator);
    if (!sec) {
        r.error = "ParseLayerMaskSection failed";
        psd::DestroyDocument(doc, &allocator);
        return r;
    }

    r.layers.reserve(sec->layerCount);
    for (unsigned i = 0; i < sec->layerCount; ++i) {
        const psd::Layer& l = sec->layers[i];
        LayerRecord rec;
        rec.id = static_cast<int>(i);
        rec.name = layerName(l);
        rec.x = l.left;
        rec.y = l.top;
        rec.width = l.right - l.left;
        rec.height = l.bottom - l.top;
        rec.opacity = static_cast<float>(l.opacity) / 255.f;
        rec.visible = l.isVisible;
        rec.kind = mapKind(l.type);
        rec.parentId = l.parent ? static_cast<int>(l.parent - sec->layers) : -1;
        // blendModeKey -> 4 символа (big-endian), напр. "norm"
        rec.blend[0] = static_cast<char>((l.blendModeKey >> 24) & 0xFF);
        rec.blend[1] = static_cast<char>((l.blendModeKey >> 16) & 0xFF);
        rec.blend[2] = static_cast<char>((l.blendModeKey >> 8) & 0xFF);
        rec.blend[3] = static_cast<char>(l.blendModeKey & 0xFF);
        rec.blend[4] = 0;
        rec.clipped = l.clipping != 0;
        if (l.layerMask) LOGW("layer %u has a layer mask (ignored at stage 1)", i);
        r.layers.push_back(std::move(rec));
    }

    psd::DestroyLayerMaskSection(sec, &allocator);
    psd::DestroyDocument(doc, &allocator);
    file.Close();

    if (!file.ok()) {
        r.error = "I/O error or truncated file";
        r.layers.clear();
        return r;
    }

    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - t0).count();
    LOGI("layer section parsed: records=%zu in %lld ms", r.layers.size(),
         static_cast<long long>(ms));
    r.ok = true;
    return r;
}

}  // namespace vt
