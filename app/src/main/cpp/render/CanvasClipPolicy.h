#pragma once

namespace vt {
// Монтажный стол виден только в редакторе; захват и экспорт ограничены кадром.
constexpr bool clipSceneToCanvas(bool exportMode) { return exportMode; }
}
