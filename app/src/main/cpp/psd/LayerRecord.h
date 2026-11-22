// POD-описание слоя. Чистый C++, без jni.h.
#pragma once
#include <string>
#include <vector>

namespace vt {

struct LayerRecord {
    int id = 0;                 // индекс записи в файле (0 = самый нижний)
    std::u16string name;        // UTF-16, передаётся в Java как есть
    int x = 0, y = 0;           // left, top
    int width = 0, height = 0;  // right-left, bottom-top
    float opacity = 1.f;        // layer opacity 0..1
    bool visible = true;
    int kind = 0;               // 0 LAYER, 1 GROUP_OPEN, 2 GROUP_CLOSED, 3 GROUP_END
    int parentId = -1;
    char blend[5] = {0};        // 4 символа ключа, напр. "norm"
    bool clipped = false;
};

struct ReadResult {
    bool ok = false;
    std::string error;
    unsigned docWidth = 0, docHeight = 0, bitsPerChannel = 0, colorMode = 0;
    std::vector<LayerRecord> layers;
};

}  // namespace vt
