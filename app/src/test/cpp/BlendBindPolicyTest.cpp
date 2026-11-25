// Нативный тест спринта 45: артефакт «мини-копия холста» в экспорте. Модель состояния блоков текстур GL:
// создание подложки привязывает новую текстуру к АКТИВНОМУ блоку; после bindInputs слой обязан остаться в блоке 0.
#include "render/BlendBindPolicy.h"
#include <cassert>
#include <cstdio>

using namespace vt::blendbind;

struct FakeGl {
    int active = 0;
    unsigned units[4] = {0, 0, 0, 0};
    unsigned nextName = 100;
    void activeTexture(int unit) { active = unit; }
    void bindTexture(unsigned t) { units[active] = t; }
    unsigned genAndBind() { const unsigned t = nextName++; bindTexture(t); return t; }   // как ensureBackdrop раньше
};

int main() {
    static_assert(kUnitLayer == 0 && kUnitBackdrop == 1, "units match shader uniforms uTex=0, uBackdrop=1");

    // 1. Первый кадр после смены цели: подложки нет, она создаётся посреди наложения.
    {
        FakeGl gl;
        unsigned backdrop = 0;
        const unsigned layer = 7;
        bindInputs(gl, layer, [&]() { if (!backdrop) backdrop = gl.genAndBind(); return backdrop; });
        assert(gl.units[kUnitLayer] == layer);       // шейдер читает слой, а не весь кадр
        assert(gl.units[kUnitBackdrop] == backdrop);
        assert(gl.active == kUnitLayer);
        assert(backdrop != layer);
    }
    // 2. Старый порядок (слой -> подложка) воспроизводит баг: блок 0 затёрт подложкой.
    {
        FakeGl gl;
        const unsigned layer = 7;
        gl.activeTexture(0); gl.bindTexture(layer);
        const unsigned backdrop = gl.genAndBind();
        gl.activeTexture(1); gl.bindTexture(backdrop);
        gl.activeTexture(0);
        assert(gl.units[0] == backdrop && gl.units[0] != layer);   // «мини-холст» в рамке слоя
    }
    // 3. Подложка уже есть (последующие кадры): привязки те же.
    {
        FakeGl gl;
        const unsigned backdrop = 55, layer = 9;
        bindInputs(gl, layer, [&]() { return backdrop; });
        assert(gl.units[0] == layer && gl.units[1] == backdrop);
    }
    // 4. Каждый кадр совместимого экспорта (снимок с новой подложкой) - слой всегда в блоке 0.
    for (int frame = 0; frame < 50; ++frame) {
        FakeGl gl;
        gl.nextName = 200 + static_cast<unsigned>(frame) * 3;
        const unsigned layer = 3 + static_cast<unsigned>(frame);
        unsigned backdrop = 0;
        bindInputs(gl, layer, [&]() { backdrop = gl.genAndBind(); return backdrop; });
        assert(gl.units[0] == layer);
        assert(gl.units[1] == backdrop);
    }
    std::printf("BlendBindPolicyTest OK\n");
    return 0;
}
