// Рисование слоя: Stroke FBO, штампы (instanced quads), зерно кисти, запекание превью, Undo-прямоугольники, превью кистей.
#include <algorithm>
#include <cmath>

#include "common/Log.h"
#include "render/GlUtil.h"
#include "render/GlState.h"
#include "render/GradientMath.h"
#include "render/Renderer.h"

namespace vt {
namespace {

// Штамп: единичный квадрат (aQuad 0..1) растягивается до диаметра + 1.5 px запаса на сглаживание края.
// vWorld - позиция пикселя в координатах слоя (пиксели): по ней зерно привязывается к холсту, а не к штампу.
const char* kDabVert = R"(#version 300 es
layout(location = 0) in vec2 aQuad;
layout(location = 1) in vec4 aDab;      // x, y (пиксели слоя), радиус (пиксели), непрозрачность штампа
layout(location = 2) in float aRot;     // a_DabRotation: поворот штампа (радианы)
uniform vec2 uSize;                      // размер цели в пикселях
out vec2 vLocal;
out vec2 vWorld;
out float vOpacity;
out float vRadius;
out float vAngle;
void main() {
    vec2 local = aQuad * 2.0 - 1.0;
    vLocal = local;
    vOpacity = aDab.w;
    vRadius = aDab.z;
    vAngle = aRot;
    float cs = cos(aRot), sn = sin(aRot);
    vec2 off = local * (aDab.z + 1.5);
    vec2 p = aDab.xy + vec2(cs * off.x - sn * off.y, sn * off.x + cs * off.y);   // квад поворачивается, vLocal остаётся в осях штампа
    vWorld = p;
    // строка 0 текстуры = верх слоя: y не переворачивается, как и у остальных слоёв
    gl_Position = vec4(p / uSize * 2.0 - 1.0, 0.0, 1.0);
}
)";

// Круг: мягкий край от radius*hardness до radius (smoothstep), у hardness = 1 - сглаживание в 1 px.
// Квадрат: бинарная отсечка step() без smoothstep (пиксель-арт, маркер).
// Итоговая альфа = shapeAlpha * grainAlpha * flow (давление меняет радиус штампа).
// Зерно: MOVING - по UV штампа; CANVAS - grainUV = worldPos / (grainTextureSize * grainScale), текстура GL_REPEAT.
// Вывод premultiplied: цвет * alpha; ластик пишет только маску alpha.
const char* kDabFrag = R"(#version 300 es
precision highp float;
in vec2 vLocal;
in vec2 vWorld;
in float vOpacity;
in float vRadius;
in float vAngle;
uniform vec3 uColor;
uniform float uHardness;
uniform int uShape;
uniform float uRoundness;             // 1 - круг/квадрат, меньше - сплющивание по оси Y штампа
uniform int uCurve;                   // 1 - двойной smoothstep (градиентное перо)
uniform int uErase;
uniform int uGrainMode;
uniform sampler2D uGrain;
uniform vec2 uGrainSize;
uniform float uGrainScale;
uniform float uGrainContrast;
out vec4 outColor;
void main() {
    vec2 q = vLocal * (vRadius + 1.5);
    q.y /= max(uRoundness, 0.05);
    float a;
    if (uShape == 1) {
        a = step(max(abs(q.x), abs(q.y)), vRadius);
    } else {
        float soft = max(vRadius * (1.0 - uHardness), 1.0);
        float t = clamp((vRadius + 0.5 - length(q)) / soft, 0.0, 1.0);
        a = t * t * (3.0 - 2.0 * t);
        if (uCurve == 1) a = a * a * (3.0 - 2.0 * a);
    }
    if (uGrainMode != 0) {
        // MOVING: зерно в пикселях штампа (едет и поворачивается вместе с ним);
        // CANVAS: зерно на холсте, но штрихи идут вдоль направления (поворот UV на угол штампа).
        vec2 guv = vWorld;
        if (uGrainMode == 2) {
            float cs = cos(vAngle), sn = sin(vAngle);
            guv = vec2(cs * vWorld.x - sn * vWorld.y, sn * vWorld.x + cs * vWorld.y);
        }
        vec2 uv = uGrainMode == 1 ? vLocal * (vRadius + 1.5) / (uGrainSize * uGrainScale) + 0.5 : guv / (uGrainSize * uGrainScale);
        float g = texture(uGrain, uv).r;
        g = clamp((g - 0.5) * uGrainContrast + 0.5, 0.0, 1.0);
        a *= g;
    }
    a *= vOpacity;
    if (a <= 0.0) discard;
    outColor = uErase == 1 ? vec4(a) : vec4(uColor * a, a);
}
)";

// The screen-space projection keeps the A→B angle correct for rotated/nonuniformly scaled layers.
const char* kGradientVert = R"(#version 300 es
layout(location=0) in vec2 aPos;
out vec2 uv;
void main() { uv = aPos; gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0); }
)";
const char* kGradientFrag = R"(#version 300 es
precision highp float;
in vec2 uv;
uniform vec2 origin, axisX, axisY, startPoint, endPoint;
uniform vec3 color;
out vec4 outColor;
void main() {
    vec2 p = origin + uv.x * axisX + uv.y * axisY;
    vec2 d = endPoint - startPoint;
    float alpha = 1.0 - clamp(dot(p - startPoint, d) / dot(d, d), 0.0, 1.0);
    outColor = vec4(color * alpha, alpha);
}
)";

// Единичный квадрат -> вся цель (NDC); строка 0 текстуры остаётся строкой 0.
const GLfloat kPaintFull[9] = {2.f, 0.f, 0.f, 0.f, 2.f, 0.f, -1.f, -1.f, 1.f};

// Целевая текстура без mip-уровней: фильтр NEAREST (иначе незавершённая текстура читается чёрной).
GLuint makeTarget(int w, int h) {
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

GLuint makeFbo(GLuint tex) {
    GLuint f = 0;
    glGenFramebuffers(1, &f);
    glBindFramebuffer(GL_FRAMEBUFFER, f);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    return f;
}

// Сохраняет и возвращает привязки GL, которые меняет рисование.
using GlSave = GlState;

}  // namespace

// Шейдер штампов и VAO (единичный квадрат + instance-буфер). Создаётся один раз на контекст.
bool Renderer::paintInit() {
    if (progDab_) return true;
    if (!vbo_) return false;
    progDab_ = buildProgram(kDabVert, kDabFrag);
    if (!progDab_) return false;
    uDabSize_ = glGetUniformLocation(progDab_, "uSize");
    uDabColor_ = glGetUniformLocation(progDab_, "uColor");
    uDabHard_ = glGetUniformLocation(progDab_, "uHardness");
    uDabShape_ = glGetUniformLocation(progDab_, "uShape");
    uDabRound_ = glGetUniformLocation(progDab_, "uRoundness");
    uDabCurve_ = glGetUniformLocation(progDab_, "uCurve");
    uDabErase_ = glGetUniformLocation(progDab_, "uErase");
    uDabGrainMode_ = glGetUniformLocation(progDab_, "uGrainMode");
    uDabGrain_ = glGetUniformLocation(progDab_, "uGrain");
    uDabGrainSize_ = glGetUniformLocation(progDab_, "uGrainSize");
    uDabGrainScale_ = glGetUniformLocation(progDab_, "uGrainScale");
    uDabGrainContrast_ = glGetUniformLocation(progDab_, "uGrainContrast");
    glGenVertexArrays(1, &dabVao_);
    glBindVertexArray(dabVao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);   // единичный квадрат
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glGenBuffers(1, &dabVbo_);
    glBindBuffer(GL_ARRAY_BUFFER, dabVbo_);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, kDabStride * sizeof(GLfloat), nullptr);
    glVertexAttribDivisor(1, 1);
    glEnableVertexAttribArray(2);   // a_DabRotation: 5-й float штампа
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, kDabStride * sizeof(GLfloat), reinterpret_cast<const void*>(4 * sizeof(GLfloat)));
    glVertexAttribDivisor(2, 1);
    glBindVertexArray(0);
    return true;
}

// Текстура зерна (R8, GL_REPEAT): создаётся при первом использовании из переданных байт, дальше берётся из кэша.
GLuint Renderer::grainTexture(int kind, const uint8_t* bytes) {
    if (kind <= 0 || kind >= kGrainKinds) return 0;
    if (grainTex_[kind]) return grainTex_[kind];
    if (!bytes) return 0;
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, kGrainSize, kGrainSize);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kGrainSize, kGrainSize, GL_RED, GL_UNSIGNED_BYTE, bytes);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    grainTex_[kind] = t;
    return t;
}

bool Renderer::paintEnsureTargets(int w, int h) {
    if (strokeTex_ && paintW_ == w && paintH_ == h) return true;
    paintFreeTargets();
    strokeTex_ = makeTarget(w, h);
    strokeFbo_ = makeFbo(strokeTex_);
    const bool a = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    backupTex_ = makeTarget(w, h);
    backupFbo_ = makeFbo(backupTex_);
    const bool b = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (!layerFbo_) glGenFramebuffers(1, &layerFbo_);
    paintW_ = w;
    paintH_ = h;
    if (!a || !b) {
        LOGW("paint targets incomplete %dx%d", w, h);
        paintFreeTargets();
        return false;
    }
    return true;
}

void Renderer::paintFreeTargets() {
    if (strokeFbo_) glDeleteFramebuffers(1, &strokeFbo_);
    if (backupFbo_) glDeleteFramebuffers(1, &backupFbo_);
    if (strokeTex_) glDeleteTextures(1, &strokeTex_);
    if (backupTex_) glDeleteTextures(1, &backupTex_);
    strokeFbo_ = backupFbo_ = strokeTex_ = backupTex_ = 0;
    paintW_ = paintH_ = 0;
}

// Рисует пакет штампов в текущий кадровый буфер (привязан вызывающим) размера w x h.
// mode 1 - накопление (premultiplied ADD), иначе GL_MAX по 4 каналам; ластик (mode 2) пишет маску alpha.
void Renderer::paintDrawDabs(int mode, const float color[3], const BrushPreset& pr, const float* dabs, int count, int w, int h) {
    glViewport(0, 0, w, h);
    glDisable(GL_SCISSOR_TEST);
    glUseProgram(progDab_);
    glUniform2f(uDabSize_, static_cast<float>(w), static_cast<float>(h));
    glUniform3f(uDabColor_, color[0], color[1], color[2]);
    glUniform1f(uDabHard_, pr.hardness);
    glUniform1i(uDabShape_, pr.shape);
    glUniform1f(uDabRound_, pr.roundness);
    glUniform1i(uDabCurve_, pr.curve);
    glUniform1i(uDabErase_, mode == 2 ? 1 : 0);
    const GLuint gt = pr.grainMode != 0 ? grainTexture(pr.grainKind, nullptr) : 0;
    glUniform1i(uDabGrainMode_, gt ? pr.grainMode : 0);
    glUniform2f(uDabGrainSize_, static_cast<float>(kGrainSize), static_cast<float>(kGrainSize));
    glUniform1f(uDabGrainScale_, std::max(0.05f, pr.grainScale));
    glUniform1f(uDabGrainContrast_, pr.grainContrast);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, gt);
    glUniform1i(uDabGrain_, 0);
    glBindVertexArray(dabVao_);
    glBindBuffer(GL_ARRAY_BUFFER, dabVbo_);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(count) * kDabStride * sizeof(GLfloat), dabs, GL_STREAM_DRAW);
    glEnable(GL_BLEND);
    if (mode == 1) {
        glBlendEquation(GL_FUNC_ADD);                  // ACCUMULATE: краска копится (аэрограф)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    } else {
        glBlendEquation(GL_MAX);                        // CAP_OPACITY и ластик: максимум по 4 каналам, перекрытия не темнее
    }
    glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
    glBlendEquation(GL_FUNC_ADD);
}

bool Renderer::paintBegin(int layerId, int mode, float opacity, bool alphaLock, float r, float g, float b, const BrushPreset& preset, const uint8_t* grain) {
    bumpEpoch();   // текстура слоя меняется на месте: кэш эффектов устарел
    touchTex(layerId);   // пакет 27: и кэш групп с этим слоем
    const auto t = textures_.find(layerId);
    if (t == textures_.end() || t->second.w <= 0 || t->second.h <= 0 || !vbo_) return false;
    if (paintActive_) {  // прерванный штрих (потеря касания и т.п.): слой возвращается к состоянию до него
        std::vector<uint8_t> tmp;
        paintEnd(false, tmp);
    }
    GlSave save;
    if (!paintInit()) return false;
    if (preset.grainMode != 0) grainTexture(preset.grainKind, grain);   // зерно загружается один раз на контекст
    const int w = t->second.w, h = t->second.h;
    if (!paintEnsureTargets(w, h)) return false;

    glBindFramebuffer(GL_FRAMEBUFFER, layerFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t->second.id, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGW("paint: layer %d texture is not renderable", layerId);
        return false;
    }
    glDisable(GL_SCISSOR_TEST);
    // копия слоя "до штриха": от неё считается превью, из неё же берутся пиксели для Undo
    glBindFramebuffer(GL_READ_FRAMEBUFFER, layerFbo_);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, backupFbo_);
    glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, strokeFbo_);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);

    paintLayer_ = layerId;
    paintMode_ = mode;
    paintPreset_ = preset;
    paintOpacity_ = std::min(1.f, std::max(0.f, opacity));
    paintAlphaLock_ = alphaLock;
    paintPreset_.hardness = std::min(1.f, std::max(0.f, preset.hardness));
    paintColor_[0] = r; paintColor_[1] = g; paintColor_[2] = b;
    pbx0_ = w; pby0_ = h; pbx1_ = 0; pby1_ = 0;
    paintActive_ = true;
    return checkGl("paintBegin");
}

// Превью: прямоугольник слоя = копия "до" + Stroke FBO, запечённый по правилам режима. Потом mip-уровни.
void Renderer::paintBake(int x, int y, int w, int h) {
    const auto t = textures_.find(paintLayer_);
    if (t == textures_.end() || w <= 0 || h <= 0) return;
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, backupFbo_);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, layerFbo_);
    glBlitFramebuffer(x, y, x + w, y + h, x, y, x + w, y + h, GL_COLOR_BUFFER_BIT, GL_NEAREST);

    glBindFramebuffer(GL_FRAMEBUFFER, layerFbo_);
    glViewport(0, 0, paintW_, paintH_);
    glEnable(GL_SCISSOR_TEST);
    glScissor(x, y, w, h);
    glEnable(GL_BLEND);
    if (paintAlphaLock_) {
        // Alpha Lock: альфа кисти умножается на альфу слоя, альфа слоя не меняется:
        // rgb = src.rgb * dstA + dst.rgb * (1 - src.a), a = dstA. Ластик под замком ничего не стирает.
        if (paintMode_ == 2) glBlendFuncSeparate(GL_ZERO, GL_ONE, GL_ZERO, GL_ONE);
        else glBlendFuncSeparate(GL_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
    } else if (paintMode_ == 2) glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);   // Destination-Out: dst * (1 - маска * непрозрачность)
    else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);                    // premultiplied "поверх"; непрозрачность штриха - здесь
    glUseProgram(progTex_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, strokeTex_);
    glUniform1i(uTexSampler_, 0);
    glUniformMatrix3fv(uTexMvp_, 1, GL_FALSE, kPaintFull);
    glUniform1f(uTexOpacity_, paintOpacity_);
    glUniform1i(uTexMesh_, 0);
    glUniform1i(uTexPersp_, 0);
    glUniform1i(uTexWarp_, 0);
    glUniform4f(uTexTurb_, 0.f, 0.f, 0.f, 0.f);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glDisable(GL_SCISSOR_TEST);

    glBindTexture(GL_TEXTURE_2D, t->second.id);
    glGenerateMipmap(GL_TEXTURE_2D);
}

void Renderer::paintDabs(const float* dabs, int count) {
    bumpEpoch();   // текстура слоя меняется на месте: кэш эффектов устарел
    touchTex(paintLayer_);   // пакет 27: и кэш групп с этим слоем
    if (!paintActive_ || !dabs || count <= 0) return;
    GlSave save;
    glBindFramebuffer(GL_FRAMEBUFFER, strokeFbo_);
    paintDrawDabs(paintMode_, paintColor_, paintPreset_, dabs, count, paintW_, paintH_);

    int x0 = paintW_, y0 = paintH_, x1 = 0, y1 = 0;
    for (int i = 0; i < count; ++i) {
        const float cx = dabs[i * kDabStride], cy = dabs[i * kDabStride + 1], r = dabs[i * kDabStride + 2] + 2.f;
        x0 = std::min(x0, static_cast<int>(std::floor(cx - r)));
        y0 = std::min(y0, static_cast<int>(std::floor(cy - r)));
        x1 = std::max(x1, static_cast<int>(std::ceil(cx + r)));
        y1 = std::max(y1, static_cast<int>(std::ceil(cy + r)));
    }
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(paintW_, x1); y1 = std::min(paintH_, y1);
    if (x1 <= x0 || y1 <= y0) return;
    pbx0_ = std::min(pbx0_, x0); pby0_ = std::min(pby0_, y0);
    pbx1_ = std::max(pbx1_, x1); pby1_ = std::max(pby1_, y1);
    paintBake(x0, y0, x1 - x0, y1 - y0);
    checkGl("paintDabs");
}

bool Renderer::paintGradient(int layerId, const float* g) {
    bumpEpoch();   // текстура слоя меняется на месте: кэш эффектов устарел
    touchTex(layerId);   // пакет 27: и кэш групп с этим слоем
    if (!paintActive_ || layerId != paintLayer_ || !g || paintMode_ == 2) return false;
    for (int i = 0; i < 10; ++i) if (!std::isfinite(g[i])) return false;
    if (!validGradientGesture(g[6], g[7], g[8], g[9])) return false;
    GlSave save;
    const GLuint program = buildProgram(kGradientVert, kGradientFrag);
    if (!program) return false;
    glBindFramebuffer(GL_FRAMEBUFFER, strokeFbo_);
    glViewport(0, 0, paintW_, paintH_);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND);
    glUseProgram(program);
    glUniform2f(glGetUniformLocation(program, "origin"), g[0], g[1]);
    glUniform2f(glGetUniformLocation(program, "axisX"), g[2], g[3]);
    glUniform2f(glGetUniformLocation(program, "axisY"), g[4], g[5]);
    glUniform2f(glGetUniformLocation(program, "startPoint"), g[6], g[7]);
    glUniform2f(glGetUniformLocation(program, "endPoint"), g[8], g[9]);
    glUniform3f(glGetUniformLocation(program, "color"), paintColor_[0], paintColor_[1], paintColor_[2]);
    glBindVertexArray(vao_); glDrawArrays(GL_TRIANGLES, 0, 6);
    glDeleteProgram(program);
    if (!checkGl("paintGradient")) return false;
    pbx0_ = 0; pby0_ = 0; pbx1_ = paintW_; pby1_ = paintH_;
    glBlendEquation(GL_FUNC_ADD);
    paintBake(0, 0, paintW_, paintH_);
    if (!checkGl("paintGradientBake")) { std::vector<uint8_t> discarded; paintEnd(false, discarded); return false; }
    return true;
}

bool Renderer::paintEnd(bool commit, std::vector<uint8_t>& out) {
    bumpEpoch();   // текстура слоя меняется на месте: кэш эффектов устарел
    touchTex(paintLayer_);   // пакет 27: и кэш групп с этим слоем
    if (!paintActive_) return false;
    paintActive_ = false;
    const int x = pbx0_, y = pby0_, w = pbx1_ - pbx0_, h = pby1_ - pby0_;
    const auto t = textures_.find(paintLayer_);
    if (t == textures_.end() || w <= 0 || h <= 0) return false;
    GlSave save;
    glDisable(GL_SCISSOR_TEST);
    if (!commit) {   // отмена штриха: прямоугольник возвращается из копии "до"
        glBindFramebuffer(GL_READ_FRAMEBUFFER, backupFbo_);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, layerFbo_);
        glBlitFramebuffer(x, y, x + w, y + h, x, y, x + w, y + h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, t->second.id);
        glGenerateMipmap(GL_TEXTURE_2D);
        return false;
    }
    const size_t bytes = static_cast<size_t>(w) * h * 4;
    out.assign(16 + bytes * 2, 0);
    const int hdr[4] = {x, y, w, h};
    for (int i = 0; i < 4; ++i)
        for (int k = 0; k < 4; ++k) out[i * 4 + k] = static_cast<uint8_t>((hdr[i] >> (8 * k)) & 0xFF);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    // сначала "до" (копия слоя), потом "после" (слой с запечённым штрихом)
    glBindFramebuffer(GL_FRAMEBUFFER, backupFbo_);
    glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out.data() + 16);
    glBindFramebuffer(GL_FRAMEBUFFER, layerFbo_);
    glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out.data() + 16 + bytes);
    // карта прозрачности для hit-test: прямоугольник обновляется из "после"
    Tex& tex = t->second;
    const uint8_t* after = out.data() + 16 + bytes;
    for (int row = 0; row < h; ++row)
        for (int col = 0; col < w; ++col)
            tex.alpha[static_cast<size_t>(y + row) * tex.w + (x + col)] = after[(static_cast<size_t>(row) * w + col) * 4 + 3];
    return checkGl("paintEnd");
}

bool Renderer::paintEndAsync(int layerId) {
    bumpEpoch();   // текстура слоя меняется на месте: кэш эффектов устарел
    touchTex(layerId);   // пакет 27: и кэш групп с этим слоем
    if (!paintActive_) return false;
    paintActive_ = false;
    const int x = pbx0_, y = pby0_, w = pbx1_ - pbx0_, h = pby1_ - pby0_;
    if (textures_.find(paintLayer_) == textures_.end() || w <= 0 || h <= 0) return false;
    const size_t bytes = static_cast<size_t>(w) * h * 4;
    GlSave save;
    glDisable(GL_SCISSOR_TEST);
    PaintRead pr;
    pr.layerId = layerId; pr.x = x; pr.y = y; pr.w = w; pr.h = h;
    glGenBuffers(1, &pr.pbo);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pr.pbo);
    glBufferData(GL_PIXEL_PACK_BUFFER, static_cast<GLsizeiptr>(bytes * 2), nullptr, GL_STREAM_READ);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    // команды чтения встают в очередь GPU и снимают пиксели "до" и "после" в порядке выполнения: следующий штрих их не портит
    glBindFramebuffer(GL_FRAMEBUFFER, backupFbo_);
    glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindFramebuffer(GL_FRAMEBUFFER, layerFbo_);
    glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, reinterpret_cast<void*>(bytes));
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    pr.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    glFlush();   // отправить команды, но не ждать их
    paintReads_.push_back(pr);
    return checkGl("paintEndAsync");
}

int Renderer::paintPoll(std::vector<uint8_t>& out) {
    if (paintReads_.empty()) return -1;
    PaintRead pr = paintReads_.front();
    const GLenum st = glClientWaitSync(pr.fence, 0, 0);
    if (st == GL_TIMEOUT_EXPIRED) return 0;
    paintReads_.pop_front();
    glDeleteSync(pr.fence);
    bool ok = st != GL_WAIT_FAILED;
    const size_t bytes = static_cast<size_t>(pr.w) * pr.h * 4;
    if (ok) {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pr.pbo);
        const void* src = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, static_cast<GLsizeiptr>(bytes * 2), GL_MAP_READ_BIT);
        ok = src != nullptr;
        if (ok) {
            out.assign(16 + bytes * 2, 0);
            const int hdr[4] = {pr.x, pr.y, pr.w, pr.h};
            for (int i = 0; i < 4; ++i)
                for (int k = 0; k < 4; ++k) out[i * 4 + k] = static_cast<uint8_t>((hdr[i] >> (8 * k)) & 0xFF);
            std::copy(static_cast<const uint8_t*>(src), static_cast<const uint8_t*>(src) + bytes * 2, out.begin() + 16);
            glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
            // карта прозрачности для hit-test: прямоугольник обновляется из "после"
            const auto t = textures_.find(pr.layerId);
            if (t != textures_.end()) {
                Tex& tex = t->second;
                const uint8_t* after = out.data() + 16 + bytes;
                if (pr.x >= 0 && pr.y >= 0 && pr.x + pr.w <= tex.w && pr.y + pr.h <= tex.h)
                    for (int row = 0; row < pr.h; ++row)
                        for (int col = 0; col < pr.w; ++col)
                            tex.alpha[static_cast<size_t>(pr.y + row) * tex.w + (pr.x + col)] = after[(static_cast<size_t>(row) * pr.w + col) * 4 + 3];
            }
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    }
    glDeleteBuffers(1, &pr.pbo);
    return ok ? 1 : -1;
}

bool Renderer::paintRestore(int layerId, int x, int y, int w, int h, const uint8_t* rgba) {
    bumpEpoch();   // текстура слоя меняется на месте: кэш эффектов устарел
    touchTex(layerId);   // пакет 27: и кэш групп с этим слоем
    const auto t = textures_.find(layerId);
    if (t == textures_.end() || !rgba) return false;
    Tex& tex = t->second;
    if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > tex.w || y + h > tex.h) return false;
    GlSave save;
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex.id);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);   // прямая перезапись, без смешивания
    glGenerateMipmap(GL_TEXTURE_2D);
    for (int row = 0; row < h; ++row)
        for (int col = 0; col < w; ++col)
            tex.alpha[static_cast<size_t>(y + row) * tex.w + (x + col)] = rgba[(static_cast<size_t>(row) * w + col) * 4 + 3];
    return checkGl("paintRestore");
}

// Превью кисти: тот же движок штампов в отдельной цели w x h. Ластик показывается чёрным мазком.
bool Renderer::brushPreview(int mode, float r, float g, float b, const BrushPreset& preset, const uint8_t* grain,
                            const float* dabs, int count, int w, int h, std::vector<uint8_t>& out) {
    if (w <= 0 || h <= 0 || !dabs || count <= 0) return false;
    GlSave save;
    if (!paintInit()) return false;
    if (preset.grainMode != 0) grainTexture(preset.grainKind, grain);
    GLuint tex = makeTarget(w, h);
    GLuint fbo = makeFbo(tex);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (ok) {
        glClearColor(0.f, 0.f, 0.f, 0.f);
        glClear(GL_COLOR_BUFFER_BIT);
        // ластик в превью - обычный чёрный мазок (его форма и зерно видны так же)
        const int pm = mode == 2 ? 0 : mode;
        const float col[3] = {mode == 2 ? 0.f : r, mode == 2 ? 0.f : g, mode == 2 ? 0.f : b};
        BrushPreset p = preset;
        paintDrawDabs(pm, col, p, dabs, count, w, h);
        out.assign(static_cast<size_t>(w) * h * 4, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out.data());
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &tex);
    return ok && checkGl("brushPreview");
}

}  // namespace vt
