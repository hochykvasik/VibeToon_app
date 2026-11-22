#pragma once
#include <string>
#include "psd/LayerRecord.h"

namespace vt {

// Читает список слоёв PSD (без распаковки пикселей).
ReadResult readPsdLayers(const std::string& path);

}  // namespace vt
