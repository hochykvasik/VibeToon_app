#include "render/Renderer.h"

#include <algorithm>
#include <cmath>
#include <exception>
#include <utility>
#include <new>

#include "common/Log.h"
#include "render/GlUtil.h"
#include "render/GlState.h"
#include "render/CanvasClipPolicy.h"
#include "render/FxCachePolicy.h"
#include "render/UploadQueue.h"
#include <EGL/egl.h>
#include <chrono>
#include "render/LayerBounds.h"
#include "render/EffectRenderPolicy.h"
#include "render/MeshPosition.h"
#include "render/UploadQueue.h"

namespace vt {
namespace {

// Бюджет GPU-памяти под текстуры слоёв (с учётом mip-уровней +33%).
constexpr size_t kTextureBudgetBytes = 192u * 1024u * 1024u;
// Сколько байт грузим в GPU за один кадр (чтобы не подвешивать UI).
constexpr size_t kUploadBytesPerFrame = 16u * 1024u * 1024u;

const char* kVertex = R"(#version 300 es
layout(location = 0) in vec2 aPos;   // единичный квадрат, y вниз (в пространстве слоя)
layout(location = 1) in vec2 aUv;    // только для Mesh: текстурные координаты вершины
uniform mat3 uMvp;
uniform int uPersp;                   // 1 - слой в перспективе: позиция через гомографию uH
uniform mat3 uH;
uniform int uMesh;                    // 1 - рисуется Mesh: aPos уже смещена, aUv - исходное положение
uniform int uWarp;                    // 1 - слой деформирован (рисуется сеткой)
uniform vec2 uOff[16];                // смещения контрольных точек 4x4 в долях размера слоя
out vec2 vUv;
vec4 bernstein(float t) {
    float s = 1.0 - t;
    return vec4(s * s * s, 3.0 * s * s * t, 3.0 * s * t * t, t * t * t);
}
void main() {
    vUv = uMesh != 0 ? aUv : aPos;    // строка 0 текстуры = верх слоя
    vec2 pos = aPos;
    if (uWarp != 0 && uMesh == 0) {
        vec4 bu = bernstein(aPos.x);
        vec4 bv = bernstein(aPos.y);
        vec2 d = vec2(0.0);
        for (int j = 0; j < 4; ++j)
            for (int i = 0; i < 4; ++i) d += bu[i] * bv[j] * uOff[j * 4 + i];
        pos += d;
    }
    if (uPersp != 0) {
        vec3 q = uH * vec3(pos, 1.0);
        pos = q.xy / q.z;
    }
    vec3 p = uMvp * vec3(pos, 1.0);
    gl_Position = vec4(p.xy, 0.0, 1.0);
}
)";

const char* kFragSolid = R"(#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 outColor;
void main() { outColor = uColor; }
)";

// Обычное наложение: premultiplied текстура * прозрачность (фиксированный blend функцией GL).
// highp: mediump UV (fp16) на текстурах >2048 px теряет до 1-2 текселей точности - «мыло» и дрожание.
const char* kFragTex = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform float uOpacity;
uniform float uLodBias;               // экспорт: -0.5 (менее размытый mip при уменьшении), редактор: 0
uniform vec4 uTurb;                   // intensity (доля слоя), size (ячеек на слой), complexity (октавы), seed; intensity = 0 - выкл
in vec2 vUv;
out vec4 outColor;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p, int oct) {
    float s = 0.0, a = 0.5;
    for (int k = 0; k < 6; ++k) { if (k >= oct) break; s += a * vnoise(p); p = p * 2.03 + 11.7; a *= 0.5; }
    return s;
}
void main() {
    vec2 uv = vUv;
    if (uTurb.x > 0.0) {
        vec2 p = uv * uTurb.y + vec2(uTurb.w * 7.31, uTurb.w * 3.17);
        int oct = int(uTurb.z);
        uv += (vec2(fbm(p, oct), fbm(p + 31.4, oct)) - 0.5) * 2.0 * uTurb.x;
    }
    outColor = texture(uTex, uv, uLodBias) * uOpacity;
}
)";

// Multiply / Screen: шейдер читает подложку (копия текущей цели) и сам считает результат.
// Формула W3C compositing, premultiplied:
//   Co = Cs*(1-ab) + Cb*(1-as) + as*ab*B(cb, cs),  ao = as + ab*(1-as)
const char* kFragBlend = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBackdrop;
uniform float uOpacity;
uniform int uMode;                    // 1 = multiply, 2 = screen, 3 = overlay, 5 = soft light, 6 = add, 7 = darken, 8 = lighten, 9 = color dodge, 10 = color burn
uniform int uClip;                    // 1 = обтравка: src *= alpha подложки, альфа подложки не меняется
uniform vec4 uTurb;
uniform float uLodBias;
in vec2 vUv;
out vec4 outColor;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p, int oct) {
    float s = 0.0, a = 0.5;
    for (int k = 0; k < 6; ++k) { if (k >= oct) break; s += a * vnoise(p); p = p * 2.03 + 11.7; a *= 0.5; }
    return s;
}
// Формулы W3C compositing (непремультиплицированные cb - подложка, cs - источник).
vec3 blendFn(vec3 cb, vec3 cs) {
    if (uMode == 1) return cb * cs;                                   // Multiply
    if (uMode == 2) return cb + cs - cb * cs;                         // Screen
    if (uMode == 3) {                                                 // Overlay = HardLight(cs, cb) с обменом
        vec3 lo = 2.0 * cb * cs;
        vec3 t = 2.0 * cb - 1.0;
        vec3 hi = cs + t - cs * t;
        return mix(lo, hi, step(0.5, cb));
    }
    if (uMode == 6) return min(cb + cs, vec3(1.0));                   // Add (Linear Dodge)
    if (uMode == 7) return min(cb, cs);                               // Darken
    if (uMode == 8) return max(cb, cs);                               // Lighten
    if (uMode == 9) {                                                 // Color Dodge
        vec3 r = min(cb / max(1.0 - cs, vec3(1e-4)), vec3(1.0));
        return mix(r, vec3(0.0), step(cb, vec3(0.0)));
    }
    if (uMode == 10) {                                                // Color Burn
        vec3 r = 1.0 - min((1.0 - cb) / max(cs, vec3(1e-4)), vec3(1.0));
        return mix(r, vec3(1.0), step(vec3(1.0), cb));
    }
    // Soft Light
    vec3 dd = mix(((16.0 * cb - 12.0) * cb + 4.0) * cb, sqrt(cb), step(0.25, cb));
    vec3 lo = cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb);
    vec3 hi = cb + (2.0 * cs - 1.0) * (dd - cb);
    return mix(lo, hi, step(0.5, cs));
}
void main() {
    vec2 uv = vUv;
    if (uTurb.x > 0.0) {
        vec2 p = uv * uTurb.y + vec2(uTurb.w * 7.31, uTurb.w * 3.17);
        int oct = int(uTurb.z);
        uv += (vec2(fbm(p, oct), fbm(p + 31.4, oct)) - 0.5) * 2.0 * uTurb.x;
    }
    vec4 s = texture(uTex, uv, uLodBias) * uOpacity;
    vec4 d = texelFetch(uBackdrop, ivec2(gl_FragCoord.xy), 0);
    float ab = d.a;
    if (uClip != 0) s *= ab;   // обтравка: src *= clipAlpha до смешивания, нижний слой (Dst) не обнуляется
    float as = s.a;
    vec3 cs = as > 0.0 ? s.rgb / as : vec3(0.0);
    vec3 cb = ab > 0.0 ? d.rgb / ab : vec3(0.0);
    vec3 rgb = s.rgb * (1.0 - ab) + d.rgb * (1.0 - as) + as * ab * blendFn(cb, cs);
    float ao = as + ab * (1.0 - as);
    if (uClip != 0) { ao = ab; rgb = min(rgb, vec3(ab)); }   // обтравка не меняет альфу базы
    outColor = vec4(rgb, ao);
}
)";


// Эффекты слоя: один полноэкранный проход над результатом предыдущего (premultiplied текстура).
// uMode: 0 ChromaKey, 1 Blur, 2 ColorCorrection, 3 Pixelate, 4 Glow, 5 DropShadow, 6 Invert, 7 Vignette,
// 8 Stroke, 9 Wave, 10 Mirror, 11 Tiles, 12 Noise, 13 Shake, 14 MotionBlur, 15 ZoomBlur (16 Spin - на CPU),
// 17 RGB Split / Glitch, 18 Pulse, 19 Displacement (процедурная карта смещения).
// Параметры p0..p7 = uP0.xyzw, uP1.xyzw. Расстояния в параметрах - пиксели холста, здесь умножаются на uView.x.
const char* kFragFx = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;        // размер цели в пикселях экрана
uniform vec2 uCanvas;      // размер холста в пикселях холста
uniform vec4 uView;        // масштаб холста, ox, oy (верх-лево холста на экране, y вниз), время в секундах
uniform int uMode;
uniform vec4 uP0;
uniform vec4 uP1;
in vec2 vUv;
out vec4 outColor;

vec4 tx(vec2 uv) {
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return vec4(0.0);
    return texture(uTex, uv);
}
vec3 unpre(vec4 c) { return c.a > 0.0001 ? c.rgb / c.a : vec3(0.0); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Гауссово размытие по спирали (золотой угол): веса exp(-r^2 / 2s^2), s = радиус / 2.
vec4 blurAt(vec2 uv, float rpx) {
    if (rpx < 0.5) return tx(uv);
    float s = rpx * 0.5;
    vec4 acc = tx(uv);
    float wsum = 1.0;
    for (int i = 0; i < 40; ++i) {
        float fi = float(i);
        float r = sqrt((fi + 0.5) / 40.0) * rpx;
        float a = fi * 2.39996323;
        float w = exp(-(r * r) / (2.0 * s * s));
        acc += w * tx(uv + vec2(cos(a), sin(a)) * r / uSize);
        wsum += w;
    }
    return acc / wsum;
}

void main() {
    vec2 uv = vUv;
    vec2 px = uv * uSize;                           // пиксель экрана, y вверх
    vec2 q = vec2(px.x - uView.y, (uSize.y - px.y) - uView.z) / uView.x;   // холст, y вниз
    float k = uView.x;
    vec4 c = tx(uv);
    vec4 o = c;
    if (uMode == 0) {                               // Chroma Key: цвет, допуск, мягкость
        vec3 col = unpre(c);
        float d = distance(col, uP0.xyz);
        float a = smoothstep(uP0.w, uP0.w + uP1.x + 0.0001, d);
        o = vec4(col * c.a * a, c.a * a);
    } else if (uMode == 1) {                        // Gaussian Blur: радиус
        o = blurAt(uv, uP0.x * k);
    } else if (uMode == 2) {                        // Color Correction: экспозиция (EV), контраст, насыщенность
        vec3 col = unpre(c) * exp2(uP0.x);
        col = (col - 0.5) * (1.0 + uP0.y) + 0.5;
        float l = dot(col, vec3(0.299, 0.587, 0.114));
        col = clamp(mix(vec3(l), col, 1.0 + uP0.z), 0.0, 1.0);
        if (abs(uP0.w) > 0.01) {                    // Hue Shift (градусы): поворот вокруг оси серого
            float ca = cos(radians(uP0.w)), sa = sin(radians(uP0.w));
            vec3 kk = vec3(0.57735027);
            col = clamp(col * ca + cross(kk, col) * sa + kk * dot(kk, col) * (1.0 - ca), 0.0, 1.0);
        }
        o = vec4(col * c.a, c.a);
    } else if (uMode == 20) {                       // Tint (калька): цвет rgb в uP0.xyz, сила в uP0.w; альфа слоя сохраняется
        o = vec4(mix(c.rgb, uP0.xyz * c.a, clamp(uP0.w, 0.0, 1.0)), c.a);
    } else if (uMode == 3) {                        // Pixelate: размер блока
        float b = max(uP0.x, 1.0);
        vec2 cell = floor(q / b);
        vec2 ctr = (cell + 0.5) * b;
        vec2 p2 = vec2(ctr.x * k + uView.y, uSize.y - (ctr.y * k + uView.z));
        o = tx(p2 / uSize);
    } else if (uMode == 4) {                        // Glow: сила, радиус
        vec4 g = blurAt(uv, uP0.y * k);
        o = min(c + g * uP0.x, vec4(1.0));
    } else if (uMode == 5) {                        // Drop Shadow: dx, dy, радиус, непрозрачность, цвет rgb
        vec2 off = vec2(uP0.x, -uP0.y) * k / uSize;
        float a = blurAt(uv - off, uP0.z * k).a * uP0.w;
        vec4 sh = vec4(uP1.xyz * a, a);
        o = c + sh * (1.0 - c.a);
    } else if (uMode == 6) {                        // Invert: степень
        vec3 col = unpre(c);
        o = vec4(mix(col, 1.0 - col, uP0.x) * c.a, c.a);
    } else if (uMode == 7) {                        // Vignette: сила, размер
        vec2 pc = q / uCanvas - 0.5;
        float d = length(pc) * 1.4142;
        float v = 1.0 - uP0.x * smoothstep(uP0.y, uP0.y + 0.6, d);
        o = vec4(c.rgb * v, c.a);
    } else if (uMode == 8) {                        // Stroke: ширина, цвет rgb
        float w = uP0.x * k;
        float m = c.a;
        for (int ring = 1; ring <= 2; ++ring) {
            float r = w * float(ring) * 0.5;
            for (int i = 0; i < 16; ++i) {
                float a = float(i) * 0.3926991;
                m = max(m, tx(uv + vec2(cos(a), sin(a)) * r / uSize).a);
            }
        }
        float sa = m * (1.0 - c.a);
        o = c + vec4(uP0.yzw * sa, sa);
    } else if (uMode == 9) {                        // Wave: амплитуда, длина волны, скорость, направление (0 гориз, 1 верт)
        float ph = (uP1.x < 0.5 ? q.y : q.x) / max(uP0.y, 1.0) * 6.2831853 + uView.w * uP0.z;
        vec2 d = vec2(sin(ph) * uP0.x * k);
        o = uP1.x < 0.5 ? tx(uv + vec2(d.x, 0.0) / uSize) : tx(uv + vec2(0.0, d.y) / uSize);
    } else if (uMode == 10) {                       // Mirror: 0 по горизонтали, 1 по вертикали, 2 оба
        vec2 ctr = vec2(uView.y + uCanvas.x * k * 0.5, uSize.y - (uView.z + uCanvas.y * k * 0.5));
        vec2 p2 = px;
        if (uP0.x < 0.5 || uP0.x > 1.5) p2.x = ctr.x - abs(px.x - ctr.x);
        if (uP0.x > 0.5) p2.y = ctr.y - abs(px.y - ctr.y);
        o = tx(p2 / uSize);
    } else if (uMode == 11) {                       // Tiles: число повторов
        vec2 f = fract(q / uCanvas * max(uP0.x, 1.0));
        vec2 p2 = vec2(f.x * uCanvas.x * k + uView.y, uSize.y - (f.y * uCanvas.y * k + uView.z));
        o = tx(p2 / uSize);
    } else if (uMode == 12) {                       // Noise: сила, размер зерна
        float n = hash(floor(px / max(uP0.y * k, 1.0)) + floor(uView.w * 24.0));
        o = vec4(c.rgb + (n - 0.5) * uP0.x * c.a, c.a);
    } else if (uMode == 13) {                       // Shake: амплитуда, частота (смен в секунду)
        float seed = floor(uView.w * max(uP0.y, 0.1));
        vec2 d = (vec2(hash(vec2(seed, 1.0)), hash(vec2(seed, 7.0))) - 0.5) * 2.0 * uP0.x * k;
        o = tx(uv - vec2(d.x, -d.y) / uSize);
    } else if (uMode == 14) {                       // Motion Blur: длина, угол (градусы)
        float a = radians(uP0.y);
        vec2 dir = vec2(cos(a), -sin(a)) * uP0.x * k / uSize;
        vec4 acc = vec4(0.0);
        for (int i = 0; i < 24; ++i) acc += tx(uv + dir * (float(i) / 23.0 - 0.5));
        o = acc / 24.0;
    } else if (uMode == 15) {                       // Zoom Blur: сила
        vec2 ctr = vec2(uView.y + uCanvas.x * k * 0.5, uSize.y - (uView.z + uCanvas.y * k * 0.5)) / uSize;
        vec4 acc = vec4(0.0);
        for (int i = 0; i < 24; ++i) {
            float s = 1.0 - uP0.x * (float(i) / 23.0);
            acc += tx(ctr + (uv - ctr) * s);
        }
        o = acc / 24.0;
    } else if (uMode == 17) {                       // RGB Split / Glitch: сдвиг X, сдвиг Y (px), глитч-дёрганье (px), частота
        vec2 sh = vec2(uP0.x, -uP0.y) * k / uSize;
        float seed = floor(uView.w * max(uP1.x, 0.1));
        float band = floor(q.y / 24.0);
        float g = (hash(vec2(band, seed)) > 0.8) ? (hash(vec2(seed, band)) - 0.5) * 2.0 * uP0.z * k / uSize.x : 0.0;
        vec2 gv = vec2(g, 0.0);
        vec4 cr = tx(uv + sh + gv), cg = tx(uv + gv), cb = tx(uv - sh + gv);
        o = vec4(cr.r, cg.g, cb.b, max(cr.a, max(cg.a, cb.a)));
    } else if (uMode == 18) {                       // Pulse: амплитуда масштаба, частота (Гц), амплитуда непрозрачности, центр x, y (доли холста)
        float s = sin(uView.w * uP0.y * 6.2831853);
        float sc = 1.0 + uP0.x * s;
        vec2 ctr = vec2(uView.y + uCanvas.x * k * uP1.x, uSize.y - (uView.z + uCanvas.y * k * uP1.y)) / uSize;
        o = tx(ctr + (uv - ctr) / max(sc, 0.05));
        o *= clamp(1.0 + uP0.z * s, 0.0, 1.0);
    } else if (uMode == 19) {                       // Displacement: сила (px), масштаб карты (px), скорость
        vec2 m = q / max(uP0.y, 4.0) + vec2(uView.w * uP0.z, -uView.w * uP0.z * 0.7);
        vec2 d = vec2(vnoise(m), vnoise(m + 17.3)) - 0.5;
        o = tx(uv + vec2(d.x, -d.y) * 2.0 * uP0.x * k / uSize);
    }
    outColor = o;
}
)";

const GLfloat kQuad[] = {0.f, 0.f, 1.f, 0.f, 0.f, 1.f, 1.f, 0.f, 1.f, 1.f, 0.f, 1.f};
// Единичный квадрат -> весь экран (NDC). Нужен для композитинга FBO.
const GLfloat kFullscreen[9] = {2.f, 0.f, 0.f, 0.f, 2.f, 0.f, -1.f, -1.f, 1.f};

}  // namespace

int Renderer::initGL() {
    LOGI("GL_VERSION=%s", reinterpret_cast<const char*>(glGetString(GL_VERSION)));
    LOGI("GL_RENDERER=%s", reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
    GLint maxTex = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    LOGI("GL_MAX_TEXTURE_SIZE=%d", maxTex);

    progSolid_ = buildProgram(kVertex, kFragSolid);
    progTex_ = buildProgram(kVertex, kFragTex);
    progBlend_ = buildProgram(kVertex, kFragBlend);
    progFx_ = buildProgram(kVertex, kFragFx);
    if (progFx_) {
        uFxMvp_ = glGetUniformLocation(progFx_, "uMvp");
        uFxTex_ = glGetUniformLocation(progFx_, "uTex");
        uFxSize_ = glGetUniformLocation(progFx_, "uSize");
        uFxCanvas_ = glGetUniformLocation(progFx_, "uCanvas");
        uFxView_ = glGetUniformLocation(progFx_, "uView");
        uFxMode_ = glGetUniformLocation(progFx_, "uMode");
        uFxP0_ = glGetUniformLocation(progFx_, "uP0");
        uFxP1_ = glGetUniformLocation(progFx_, "uP1");
    }
    if (!progSolid_ || !progTex_ || !progBlend_) return -1;
    uSolidMvp_ = glGetUniformLocation(progSolid_, "uMvp");
    uSolidColor_ = glGetUniformLocation(progSolid_, "uColor");
    uTexMvp_ = glGetUniformLocation(progTex_, "uMvp");
    uTexOpacity_ = glGetUniformLocation(progTex_, "uOpacity");
    uTexLod_ = glGetUniformLocation(progTex_, "uLodBias");
    uBlLod_ = glGetUniformLocation(progBlend_, "uLodBias");
    uTexSampler_ = glGetUniformLocation(progTex_, "uTex");
    uTexWarp_ = glGetUniformLocation(progTex_, "uWarp");
    uTexOff_ = glGetUniformLocation(progTex_, "uOff");
    uBlMvp_ = glGetUniformLocation(progBlend_, "uMvp");
    uBlOpacity_ = glGetUniformLocation(progBlend_, "uOpacity");
    uBlTex_ = glGetUniformLocation(progBlend_, "uTex");
    uBlBackdrop_ = glGetUniformLocation(progBlend_, "uBackdrop");
    uBlMode_ = glGetUniformLocation(progBlend_, "uMode");
    uBlClip_ = glGetUniformLocation(progBlend_, "uClip");
    uBlWarp_ = glGetUniformLocation(progBlend_, "uWarp");
    uBlOff_ = glGetUniformLocation(progBlend_, "uOff");
    uTexMesh_ = glGetUniformLocation(progTex_, "uMesh");
    uBlMesh_ = glGetUniformLocation(progBlend_, "uMesh");
    uTexPersp_ = glGetUniformLocation(progTex_, "uPersp");
    uTexH_ = glGetUniformLocation(progTex_, "uH");
    uBlPersp_ = glGetUniformLocation(progBlend_, "uPersp");
    uBlH_ = glGetUniformLocation(progBlend_, "uH");
    uTexTurb_ = glGetUniformLocation(progTex_, "uTurb");
    uBlTurb_ = glGetUniformLocation(progBlend_, "uTurb");

    // Mesh: динамический VBO (позиция + uv, обновляется при каждой отрисовке) и IBO.
    glGenVertexArrays(1, &meshVao_);
    glBindVertexArray(meshVao_);
    glGenBuffers(1, &meshVbo_);
    glBindBuffer(GL_ARRAY_BUFFER, meshVbo_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<const void*>(2 * sizeof(GLfloat)));
    glGenBuffers(1, &meshIbo_);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, meshIbo_);
    glBindVertexArray(0);

    glGenVertexArrays(1, &vao_);
    glBindVertexArray(vao_);
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuad), kQuad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glBindVertexArray(0);

    // Сетка для Warp: (N+1)x(N+1) вершин единичного квадрата, 2 треугольника на ячейку.
    {
        constexpr int N = 24;
        std::vector<GLfloat> v;
        v.reserve((N + 1) * (N + 1) * 2);
        for (int j = 0; j <= N; ++j)
            for (int i = 0; i <= N; ++i) {
                v.push_back(static_cast<GLfloat>(i) / N);
                v.push_back(static_cast<GLfloat>(j) / N);
            }
        std::vector<GLushort> idx;
        idx.reserve(N * N * 6);
        for (int j = 0; j < N; ++j)
            for (int i = 0; i < N; ++i) {
                const GLushort a = static_cast<GLushort>(j * (N + 1) + i);
                const GLushort b = static_cast<GLushort>(a + 1);
                const GLushort c = static_cast<GLushort>(a + N + 1);
                const GLushort d = static_cast<GLushort>(c + 1);
                idx.insert(idx.end(), {a, b, c, b, d, c});
            }
        gridIndexCount_ = static_cast<GLsizei>(idx.size());
        glGenVertexArrays(1, &gridVao_);
        glBindVertexArray(gridVao_);
        glGenBuffers(1, &gridVbo_);
        glBindBuffer(GL_ARRAY_BUFFER, gridVbo_);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(v.size() * sizeof(GLfloat)), v.data(), GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
        glGenBuffers(1, &gridIbo_);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, gridIbo_);   // привязка к VAO сетки
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLsizeiptr>(idx.size() * sizeof(GLushort)), idx.data(), GL_STATIC_DRAW);
        glBindVertexArray(0);
    }

    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);  // premultiplied alpha

    // Новый контекст: старые id (текстуры, FBO) недействительны.
    textures_.clear();
    shadowTex_.clear();
    fxCache_.clear();
    fxCacheBytes_ = 0;
    bumpEpoch();
    textureBytes_ = 0;
    fboPool_.clear();
    fboCreated_ = 0;
    backdropTex_ = backdropFbo_ = 0;
    // рисование: id объектов прошлого контекста недействительны
    progDab_ = dabVao_ = dabVbo_ = strokeTex_ = strokeFbo_ = backupTex_ = backupFbo_ = layerFbo_ = 0;
    paintW_ = paintH_ = 0;
    paintActive_ = false;
    grainTex_[0] = grainTex_[1] = grainTex_[2] = grainTex_[3] = 0;
    curFbo_ = 0;
    return checkGl("initGL") ? maxTex : -1;
}

void Renderer::setViewport(int width, int height) {
    const int w = width > 0 ? width : 1;
    const int h = height > 0 ? height : 1;
    if (w != width_ || h != height_) { releaseTargets(); dropFxCache(); }  // FBO привязаны к размеру экрана
    bumpEpoch();
    width_ = w;
    height_ = h;
    glViewport(0, 0, width_, height_);
    LOGI("viewport %dx%d", width_, height_);
}

void Renderer::beginExport(int width, int height) {
    bumpEpoch();
    savedW_ = width_; savedH_ = height_;
    savedZoom_ = zoom_; savedPanX_ = panX_; savedPanY_ = panY_;
    savedRotDeg_ = rotDeg_; savedFlipX_ = flipX_; savedFlipY_ = flipY_;
    exportMode_ = true;
    zoom_ = 1.f; panX_ = panY_ = 0.f; rotDeg_ = 0.f; flipX_ = flipY_ = false;
    releaseTargets();  // свежие FBO/подложка под размер экспорта: никаких stale-целей редактора
    setViewport(width, height);
    // Экспорт в Surface кодека: рисуем в FBO (blit FBO->FBO надёжен), затем blit на поверхность.
    // Blit ИЗ window surface ненадёжен на части драйверов и давал артефакт «мини-копия холста».
    if (screenFbo_ == 0 && width > 0 && height > 0) {
        glGenTextures(1, &exportTex_);
        glBindTexture(GL_TEXTURE_2D, exportTex_);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenFramebuffers(1, &exportFbo_);
        glBindFramebuffer(GL_FRAMEBUFFER, exportFbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, exportTex_, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            screenFbo_ = exportFbo_;
            exportToSurface_ = true;
        } else {
            glDeleteFramebuffers(1, &exportFbo_); glDeleteTextures(1, &exportTex_);
            exportFbo_ = exportTex_ = 0;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
}

bool Renderer::captureFrame(int w, int h, bool transparent, std::vector<uint8_t>& out, int flags) {
    // Приложение в фоне: поверхность GLSurfaceView снята, контекст не текущий - любые GL-вызовы дадут мусор
    // (раньше пустой снимок мог стать картинкой символа). Отказ - вызывающий повторит после возврата.
    if (eglGetCurrentContext() == EGL_NO_CONTEXT) return false;
    GLint maxTexture = 0, maxRenderbuffer = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTexture);
    glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &maxRenderbuffer);
    const int limit = std::min(8192, std::min(maxTexture, maxRenderbuffer));
    if (w < 1 || h < 1 || w > limit || h > limit || exportMode_) return false;
    GlState glState;
    // Never resize/reuse the editor's blend backdrop or group/effect targets.
    // A capture may have exactly the viewport size, so size checks alone aren't isolation.
    auto editorPool = std::move(fboPool_);
    const GLuint editorBackdropTex = backdropTex_, editorBackdropFbo = backdropFbo_;
    const GLuint editorCurrent = curFbo_, editorScreen = screenFbo_;
    const int editorCreated = fboCreated_;
    fboPool_.clear(); backdropTex_ = backdropFbo_ = 0; fboCreated_ = 0;
    GLuint tex = 0, fbo = 0;
    bool ok = false;
    try {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glDisable(GL_SCISSOR_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        if (ok) {
            screenFbo_ = fbo;  // до beginExport: снимок, не Surface-экспорт (без внутреннего FBO)
            beginExport(w, h);
            transparentCapture_ = transparent;
            const bool shadow = (flags & kCaptureShadow) != 0;
            if (shadow) {
                // Снимок символа: нужны только его теневые текстуры (их немного). Общую очередь загрузок здесь
                // НЕ вычерпываем: после возврата из фона в ней весь проект, и раньше снимок держал GL-поток
                // секундами (главный поток ждёт GL в onPause/onResume/surfaceChanged -> ANR).
                uploadShadows();
                useShadows_ = true;
                drawFrame();
                useShadows_ = false;
            } else {
                // Прочие снимки дорисовывают загрузки, но не дольше ~1.5 с (защита главного потока от ANR).
                const auto t0 = std::chrono::steady_clock::now();
                int guard = 0;
                while (drawFrame() && guard++ < 500) {
                    if (std::chrono::steady_clock::now() - t0 > std::chrono::milliseconds(1500)) break;
                }
            }
            const bool notReady = (flags & kCaptureStrict) != 0 && stats_.missing > 0;
            if (notReady) {
                out.clear();
                ok = true;
            } else {
                out.assign(static_cast<size_t>(w) * h * 4, 0);
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glPixelStorei(GL_PACK_ALIGNMENT, 1);
                glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out.data());
                ok = checkGl("captureFrame");
            }
        }
    } catch (const std::exception& e) {
        LOGW("capture allocation failed: %s", e.what());
        ok = false;
    }
    transparentCapture_ = false;
    useShadows_ = false;
    if (flags & kCaptureShadow) clearShadows();   // теневые текстуры живут ровно один снимок
    screenFbo_ = editorScreen;
    endExport();
    releaseTargets();
    fboPool_ = std::move(editorPool);
    backdropTex_ = editorBackdropTex; backdropFbo_ = editorBackdropFbo;
    curFbo_ = editorCurrent; fboCreated_ = editorCreated;
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &tex);
    bumpEpoch();
    if (!ok) out.clear();
    return ok;
}

void Renderer::endExport() {
    if (!exportMode_) return;
    bumpEpoch();
    exportMode_ = false;
    if (exportToSurface_) {
        if (exportFbo_) glDeleteFramebuffers(1, &exportFbo_);
        if (exportTex_) glDeleteTextures(1, &exportTex_);
        exportFbo_ = exportTex_ = 0;
        exportToSurface_ = false;
        screenFbo_ = 0;
    }
    zoom_ = savedZoom_; panX_ = savedPanX_; panY_ = savedPanY_;
    rotDeg_ = savedRotDeg_; flipX_ = savedFlipX_; flipY_ = savedFlipY_;
    setViewport(savedW_, savedH_);
}

void Renderer::setCanvas(int width, int height) {
    if (width != canvasW_ || height != canvasH_) bumpEpoch();
    canvasW_ = width > 0 ? width : 1;
    canvasH_ = height > 0 ? height : 1;
}

void Renderer::setWarps(const int* ids, const float* offsets, int n) {
    bumpEpoch();
    warps_.clear();
    for (int i = 0; i < n; ++i) {
        std::array<float, 32> a;
        for (int k = 0; k < 32; ++k) a[static_cast<size_t>(k)] = offsets[i * 32 + k];
        warps_[ids[i]] = a;
    }
}

void Renderer::setPerspectives(const int* ids, const float* mats, int n) {
    bumpEpoch();
    persps_.clear();
    for (int i = 0; i < n; ++i) {
        std::array<float, 9> a;
        for (int k = 0; k < 9; ++k) a[static_cast<size_t>(k)] = mats[i * 9 + k];
        persps_[ids[i]] = a;
    }
}

void Renderer::setTurbulence(const int* ids, const float* data, int n) {
    bumpEpoch();
    turbs_.clear();
    for (int i = 0; i < n; ++i) {
        std::array<float, 4> a;
        for (int k = 0; k < 4; ++k) a[static_cast<size_t>(k)] = data[i * 4 + k];
        turbs_[ids[i]] = a;
    }
}

void Renderer::setEffects(const int* ids, const int* counts, int n, const float* data) {
    bumpEpoch();
    effects_.clear();
    size_t o = 0;
    for (int i = 0; i < n; ++i) {
        std::vector<std::array<float, 10>> list;
        for (int k = 0; k < counts[i]; ++k) {
            std::array<float, 10> e;
            for (int j = 0; j < 10; ++j) e[static_cast<size_t>(j)] = data[o + static_cast<size_t>(j)];
            o += 10;
            list.push_back(e);
        }
        if (!list.empty()) effects_[ids[i]] = std::move(list);
    }
}

void Renderer::setMeshes(const int* ids, const int* counts, int n, const float* verts, const int* tris) {
    bumpEpoch();
    meshes_.clear();
    size_t vo = 0, to = 0;
    for (int i = 0; i < n; ++i) {
        const int vc = counts[i * 2], tc = counts[i * 2 + 1];
        MeshGeom g;
        g.verts.reserve(static_cast<size_t>(vc) * 4);
        for (int k = 0; k < vc; ++k) {
            const float* p = verts + vo + static_cast<size_t>(k) * 4;
            const auto pos = meshPosition(p[0], p[1], p[2], p[3]);
            g.verts.insert(g.verts.end(), {pos.x, pos.y, pos.u, pos.v});
        }
        g.idx.reserve(static_cast<size_t>(tc) * 3);
        for (int t = 0; t < tc * 3; ++t) {
            const int ix = tris[to + static_cast<size_t>(t)];
            g.idx.push_back(static_cast<GLushort>(ix >= 0 && ix < vc ? ix : 0));
        }
        vo += static_cast<size_t>(vc) * 4;
        to += static_cast<size_t>(tc) * 3;
        meshes_[ids[i]] = std::move(g);
    }
}

void Renderer::setScene(const int* ops, const float* params, int n) {
    bumpEpoch();
    scene_.clear();
    scene_.reserve(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) {
        const int* o = ops + i * 4;
        const float* p = params + i * 8;
        scene_.push_back({o[0], o[1], o[2], o[3], {p[0], p[1], p[2], p[3], p[4], p[5]}, p[6]});
    }
}

void Renderer::setCamera(float zoom, float panX, float panY, float rotDeg, bool flipX, bool flipY) {
    zoom_ = zoom > 0.f ? zoom : 1.f;
    panX_ = panX;
    panY_ = panY;
    rotDeg_ = rotDeg;
    flipX_ = flipX;
    flipY_ = flipY;
}

// Холст -> экран: сначала вписывание (поля 5%, по центру), затем камера (zoom вокруг центра + pan).
void Renderer::viewTransform(float& scale, float& ox, float& oy) const {
    const float fit = (exportMode_ ? 1.0f : 0.95f) * std::min(static_cast<float>(width_) / canvasW_,
                                       static_cast<float>(height_) / canvasH_);
    const float fx = (width_ - canvasW_ * fit) * 0.5f;
    const float fy = (height_ - canvasH_ * fit) * 0.5f;
    const float cx = width_ * 0.5f, cy = height_ * 0.5f;
    scale = fit * zoom_;
    ox = cx + (fx - cx) * zoom_ + panX_;
    oy = cy + (fy - cy) * zoom_ + panY_;
}

// Холст -> экран: screen = (a0*x + a2*y + a4, a1*x + a3*y + a5). Поворот/отражение вида — только вид.
void Renderer::viewAffine(float a[6]) const {
    const float fit = (exportMode_ ? 1.0f : 0.95f) * std::min(static_cast<float>(width_) / canvasW_,
                                       static_cast<float>(height_) / canvasH_);
    const float fx = (width_ - canvasW_ * fit) * 0.5f;
    const float fy = (height_ - canvasH_ * fit) * 0.5f;
    const float cx = width_ * 0.5f, cy = height_ * 0.5f;
    const float th = rotDeg_ * 0.01745329252f;
    const float c = std::cos(th), s = std::sin(th);
    const float sx = flipX_ ? -1.f : 1.f, sy = flipY_ ? -1.f : 1.f;
    // A = R * F
    const float a00 = c * sx, a01 = -s * sy, a10 = s * sx, a11 = c * sy;
    const float k = zoom_ * fit;
    a[0] = k * a00; a[1] = k * a10; a[2] = k * a01; a[3] = k * a11;
    // t = C + zoom * A * (f - C) + pan
    const float ex = fx - cx, ey = fy - cy;
    a[4] = cx + zoom_ * (a00 * ex + a01 * ey) + panX_;
    a[5] = cy + zoom_ * (a10 * ex + a11 * ey) + panY_;
}

void Renderer::setBackground(float r, float g, float b, float a) {
    if (a <= 0.f) { bg_[0] = bg_[1] = bg_[2] = 0.85f; bg_[3] = 1.f; return; }   // прозрачный
    bg_[0] = r; bg_[1] = g; bg_[2] = b; bg_[3] = 1.f;
}

void Renderer::setSelection(const float* quads, int n) {
    selQuads_.clear();
    if (quads && n > 0) selQuads_.assign(quads, quads + static_cast<size_t>(n) * 8);
}

// Матрица слоя: единичный квадрат (u, v) -> холст по аффинному m = [a b c d tx ty]
//   canvas = (a*u + c*v + tx, b*u + d*v + ty)
// затем холст -> экран (вписывание и камера) -> NDC (ось y экрана вниз). Матрица mat3 по столбцам.
void Renderer::mvpFor(const float m[6], GLfloat o[9]) const {
    float a[6];
    viewAffine(a);
    // screen = view * (layer -> canvas); NDC: x = sx/w*2-1, y = 1-sy/h*2 (столбцы mat3)
    o[0] = (a[0] * m[0] + a[2] * m[1]) / width_ * 2.f;
    o[1] = -(a[1] * m[0] + a[3] * m[1]) / height_ * 2.f;
    o[2] = 0.f;
    o[3] = (a[0] * m[2] + a[2] * m[3]) / width_ * 2.f;
    o[4] = -(a[1] * m[2] + a[3] * m[3]) / height_ * 2.f;
    o[5] = 0.f;
    o[6] = (a[0] * m[4] + a[2] * m[5] + a[4]) / width_ * 2.f - 1.f;
    o[7] = 1.f - (a[1] * m[4] + a[3] * m[5] + a[5]) / height_ * 2.f;
    o[8] = 1.f;
}

void Renderer::mvpForRect(float x, float y, float w, float h, GLfloat o[9]) const {
    const float m[6] = {w, 0.f, 0.f, h, x, y};
    mvpFor(m, o);
}

// Параллелограмм в пикселях экрана: точка = (ox, oy) + u*(ex, ey) + v*(fx, fy).
void Renderer::mvpScreen(float ox, float oy, float ex, float ey, float fx, float fy, GLfloat o[9]) const {
    o[0] = ex / width_ * 2.f;   o[1] = -ey / height_ * 2.f;  o[2] = 0.f;
    o[3] = fx / width_ * 2.f;   o[4] = -fy / height_ * 2.f;  o[5] = 0.f;
    o[6] = ox / width_ * 2.f - 1.f;
    o[7] = 1.f - oy / height_ * 2.f;
    o[8] = 1.f;
}

// Верхний слой, у которого под точкой непрозрачный пиксель (альфа > 20). Сцена обходится сверху вниз.
// Точка экрана переводится в координаты холста, затем обратной матрицей слоя в (u, v) квадрата.
const uint8_t* Renderer::layerAlpha(int layerId, int& w, int& h) const {
    const auto t = textures_.find(layerId);
    if (t == textures_.end() || t->second.alpha.empty()) return nullptr;
    w = t->second.w;
    h = t->second.h;
    return t->second.alpha.data();
}

bool Renderer::layerThumb(int layerId, int size, std::vector<uint8_t>& out) {
    const auto t = textures_.find(layerId);
    if (t == textures_.end() || t->second.w <= 0 || t->second.h <= 0 || size < 4 || !progTex_) return false;
    GLint prevFbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    GLint prevVp[4];
    glGetIntegerv(GL_VIEWPORT, prevVp);
    GLuint tex = 0, fbo = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, size, size);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (ok) {
        glViewport(0, 0, size, size);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.f, 0.f, 0.f, 0.f);
        glClear(GL_COLOR_BUFFER_BIT);
        // вписываем по длинной стороне: единичный квадрат -> прямоугольник в NDC
        const float aw = static_cast<float>(t->second.w), ah = static_cast<float>(t->second.h);
        const float sx = aw >= ah ? 1.f : aw / ah, sy = ah >= aw ? 1.f : ah / aw;
        const GLfloat mvp[9] = {2.f * sx, 0.f, 0.f, 0.f, 2.f * sy, 0.f, -sx, -sy, 1.f};
        glBindVertexArray(vao_);
        const int saveMesh = 0;
        (void)saveMesh;
        curMesh_ = nullptr; curPersp_ = nullptr; curTurb_ = nullptr;
        drawTexture(t->second.id, mvp, 1.f, kNormal, false);
        out.assign(static_cast<size_t>(size) * size * 4, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, out.data());
        glBindVertexArray(0);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prevFbo));
    glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &tex);
    return ok;
}

bool Renderer::readLayerPixels(int layerId, std::vector<uint8_t>& out, int& w, int& h) {
    const auto t = textures_.find(layerId);
    if (t == textures_.end() || t->second.w <= 0 || t->second.h <= 0) return false;
    w = t->second.w;
    h = t->second.h;
    out.assign(static_cast<size_t>(w) * h * 4, 0);
    GLint prev = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev);
    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t->second.id, 0);
    const bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (ok) {
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out.data());
    }
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prev));
    glDeleteFramebuffers(1, &fbo);
    return ok;
}

int Renderer::hitTest(float sx, float sy) const {
    float a[6];
    viewAffine(a);
    const float det = a[0] * a[3] - a[2] * a[1];
    if (std::fabs(det) < 1e-12f) return -1;
    const float dx = sx - a[4], dy = sy - a[5];
    const float px = (a[3] * dx - a[2] * dy) / det;   // координаты на холсте
    const float py = (-a[1] * dx + a[0] * dy) / det;
    for (size_t i = scene_.size(); i-- > 0;) {
        const Item& it = scene_[i];
        if (it.op != kLayer || it.opacity < 0.01f) continue;
        const float a = it.m[0], b = it.m[1], c = it.m[2], d = it.m[3];
        const float det = a * d - b * c;
        if (std::fabs(det) < 1e-9f) continue;   // вырожденная матрица
        const float rx = px - it.m[4], ry = py - it.m[5];
        const float u = (d * rx - c * ry) / det;
        const float v = (-b * rx + a * ry) / det;
        // слой с сеткой: точка ищется на ДЕФОРМИРОВАННОЙ сетке (позиции u+dx, v+dy), текстурные координаты берутся из треугольника
        const auto mi = meshes_.find(it.layerId);
        if (mi != meshes_.end() && !mi->second.idx.empty()) {
            const MeshGeom& g = mi->second;
            const auto tm = textures_.find(it.layerId);
            const bool hasAlpha = tm != textures_.end() && !tm->second.alpha.empty();
            for (size_t k = 0; k + 2 < g.idx.size(); k += 3) {
                const float* A = &g.verts[static_cast<size_t>(g.idx[k]) * 4];
                const float* B = &g.verts[static_cast<size_t>(g.idx[k + 1]) * 4];
                const float* C = &g.verts[static_cast<size_t>(g.idx[k + 2]) * 4];
                const float dd = (B[1] - C[1]) * (A[0] - C[0]) + (C[0] - B[0]) * (A[1] - C[1]);
                if (std::fabs(dd) < 1e-12f) continue;
                const float w1 = ((B[1] - C[1]) * (u - C[0]) + (C[0] - B[0]) * (v - C[1])) / dd;
                const float w2 = ((C[1] - A[1]) * (u - C[0]) + (A[0] - C[0]) * (v - C[1])) / dd;
                const float w3 = 1.f - w1 - w2;
                if (w1 < -1e-4f || w2 < -1e-4f || w3 < -1e-4f) continue;
                if (it.flags & kFlagHitBbox || !hasAlpha) return it.layerId;
                const float tu = w1 * A[2] + w2 * B[2] + w3 * C[2];
                const float tv = w1 * A[3] + w2 * B[3] + w3 * C[3];
                const Tex& tex = tm->second;
                const int cx = std::min(tex.w - 1, std::max(0, static_cast<int>(tu * tex.w)));
                const int cy = std::min(tex.h - 1, std::max(0, static_cast<int>(tv * tex.h)));
                // небольшой допуск (+-2 текселя): тонкие пряди ловятся пальцем
                for (int oy = -2; oy <= 2; ++oy) for (int ox = -2; ox <= 2; ++ox) {
                    const int px2 = std::min(tex.w - 1, std::max(0, cx + ox)), py2 = std::min(tex.h - 1, std::max(0, cy + oy));
                    if (tex.alpha[static_cast<size_t>(py2) * tex.w + px2] > 20) return it.layerId;
                }
            }
            continue;
        }
        if (u < 0.f || u >= 1.f || v < 0.f || v >= 1.f) continue;
        if (it.flags & kFlagHitBbox) return it.layerId;   // текст: достаточно попасть в рамку
        auto t = textures_.find(it.layerId);
        if (t == textures_.end() || t->second.alpha.empty()) continue;
        const Tex& tex = t->second;
        const int tx = std::min(tex.w - 1, static_cast<int>(u * tex.w));
        const int ty = std::min(tex.h - 1, static_cast<int>(v * tex.h));
        if (tex.alpha[static_cast<size_t>(ty) * tex.w + tx] > 20) return it.layerId;
    }
    return -1;
}

// Рамка выделения: 4 ребра (толщина ~3 px экрана) по углам quad из координат холста.
// Рисуется в пространстве экрана, поэтому толщина одинакова при любом перекосе и масштабе.
void Renderer::drawSelection() {
    if (selQuads_.empty()) return;
    float a[6];
    viewAffine(a);
    glUseProgram(progSolid_);
    glUniform4f(uSolidColor_, 1.f, 0.75f, 0.f, 1.f);
    GLfloat mvp[9];
    const float half = 1.5f;   // половина толщины в пикселях
    for (size_t q = 0; q + 8 <= selQuads_.size(); q += 8) {
    float sxp[4], syp[4];
    for (int i = 0; i < 4; ++i) {
        const float cx = selQuads_[q + i * 2], cy = selQuads_[q + i * 2 + 1];
        sxp[i] = a[0] * cx + a[2] * cy + a[4];
        syp[i] = a[1] * cx + a[3] * cy + a[5];
    }
    for (int i = 0; i < 4; ++i) {
        const int j = (i + 1) % 4;
        float ex = sxp[j] - sxp[i], ey = syp[j] - syp[i];
        const float len = std::sqrt(ex * ex + ey * ey);
        if (len < 1e-3f) continue;
        const float nx = -ey / len * half, ny = ex / len * half;   // нормаль длиной half
        // ребро чуть удлиняем на half, чтобы углы смыкались
        const float ux = ex / len * half, uy = ey / len * half;
        mvpScreen(sxp[i] - nx - ux, syp[i] - ny - uy, ex + 2 * ux, ey + 2 * uy, 2 * nx, 2 * ny, mvp);
        glUniformMatrix3fv(uSolidMvp_, 1, GL_FALSE, mvp);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }
    }
}

void Renderer::clearTextures() {
    for (auto& kv : textures_) glDeleteTextures(1, &kv.second.id);
    textures_.clear();
    textureBytes_ = 0;
    clearShadows();
    dropFxCache();
    bumpEpoch();
}

void Renderer::clearShadows() {
    for (auto& kv : shadowTex_) { glDeleteTextures(1, &kv.second.id); textureBytes_ -= std::min(textureBytes_, kv.second.bytes); }
    shadowTex_.clear();
}

void Renderer::setInteractive(bool on) {
    if (interactive_ == on) return;
    interactive_ = on;
}

const Renderer::Tex* Renderer::findTex(int layerId) const {
    if (useShadows_) {
        const auto s = shadowTex_.find(layerId);
        if (s != shadowTex_.end()) return &s->second;
    }
    const auto t = textures_.find(layerId);
    return t == textures_.end() ? nullptr : &t->second;
}

// Отдельная проверяемая замена; не изменяет прежнюю текстуру при отказе.
// Вызывающий обязан сериализовать правки слоя и публикацию геометрии.
int Renderer::replaceTextureChecked(int layerId, int expectedW, int expectedH, int w, int h, const uint8_t* rgba) {
    if (!rgba || w <= 0 || h <= 0 || w > 4096 || h > 4096 ||
        static_cast<uint64_t>(w) * h > 4194304u) return -1;
    auto old = textures_.find(layerId);
    if (old == textures_.end() || old->second.w != expectedW || old->second.h != expectedH) return -2;
    uploadQueue::EmptyGuard queueGuard;
    if (!queueGuard.acquired()) return -3;
    if (!queueGuard.prepareLayer(layerId)) return -5;
    // Консервативная оценка всех mip-уровней, включая пик одновременного существования обеих текстур.
    const size_t bytes = static_cast<size_t>(w) * static_cast<size_t>(h) * 8;
    if (bytes > kTextureBudgetBytes || textureBytes_ > kTextureBudgetBytes - bytes) return -4;
    GLint maxTex = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    if (w > maxTex || h > maxTex) return -4;
    Tex t;
    t.w = w; t.h = h; t.bytes = bytes;
    try {
        t.alpha.resize(static_cast<size_t>(w) * h);
    } catch (const std::bad_alloc&) { return -5; }
    for (size_t i = 0; i < t.alpha.size(); ++i) t.alpha[i] = rgba[i * 4 + 3];
    GLint binding = 0, alignment = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &binding);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &alignment);
    checkGl("before replaceTextureChecked");
    glGenTextures(1, &t.id);
    glBindTexture(GL_TEXTURE_2D, t.id);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    const int levels = 1 + static_cast<int>(std::floor(std::log2(std::max(w, h))));
    glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA8, w, h);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glGenerateMipmap(GL_TEXTURE_2D);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    const bool ok = t.id && checkGl("replaceTextureChecked");
    glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
    if (!ok) {
        if (t.id) glDeleteTextures(1, &t.id);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(binding));
        return -6;
    }
    queueGuard.invalidateLayer(layerId);
    bumpEpoch();
    const GLuint oldId = old->second.id;
    textureBytes_ -= old->second.bytes;
    old->second = std::move(t);
    textureBytes_ += bytes;
    glDeleteTextures(1, &oldId);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(binding) == oldId ? old->second.id : static_cast<GLuint>(binding));
    return 0;
}

bool Renderer::makeTexture(const PendingUpload& up, size_t bytes, Tex& t) {
    t.bytes = bytes;
    t.w = up.width;
    t.h = up.height;
    t.alpha.resize(static_cast<size_t>(up.width) * up.height);
    for (size_t i = 0; i < t.alpha.size(); ++i) t.alpha[i] = up.rgba[i * 4 + 3];
    // Отделяем ошибку новой загрузки от оставшихся ошибок предыдущего кадра.
    checkGl("before uploadPending");
    glGenTextures(1, &t.id);
    glBindTexture(GL_TEXTURE_2D, t.id);
    const int levels = 1 + static_cast<int>(std::floor(std::log2(std::max(up.width, up.height))));
    glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA8, up.width, up.height);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, up.width, up.height, GL_RGBA, GL_UNSIGNED_BYTE,
                    up.rgba.data());
    glGenerateMipmap(GL_TEXTURE_2D);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (!t.id || !checkGl("uploadPending texture")) {
        if (t.id) glDeleteTextures(1, &t.id);
        t.id = 0;
        LOGW("texture allocation failed, layer %d unchanged", up.layerId);
        return false;
    }
    return true;
}

void Renderer::uploadPending() {
    size_t uploaded = 0;
    PendingUpload up;
    while (uploaded < kUploadBytesPerFrame && uploadQueue::pop(up)) {
        const size_t bytes = static_cast<size_t>(up.width) * up.height * 4 * 4 / 3;  // с mip
        auto old = textures_.find(up.layerId);
        // Старую текстуру держим до успешного создания новой: бюджет учитывает пик обеих.
        if (up.width <= 0 || up.height <= 0 || bytes > kTextureBudgetBytes ||
            textureBytes_ > kTextureBudgetBytes - bytes) {
            LOGW("texture budget exceeded, layer %d unchanged (%zu MB in use)", up.layerId,
                 textureBytes_ / (1024 * 1024));
            continue;
        }
        GLint maxTex = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
        if (up.width > maxTex || up.height > maxTex) {
            LOGW("texture dimensions exceed GL limit, layer %d unchanged", up.layerId);
            continue;
        }
        Tex t;
        if (!makeTexture(up, bytes, t)) continue;
        if (old != textures_.end()) {
            glDeleteTextures(1, &old->second.id);
            textureBytes_ -= old->second.bytes;
            textures_.erase(old);
        }
        textures_[up.layerId] = std::move(t);
        textureBytes_ += bytes;
        uploaded += up.rgba.size();
        dropFxEntry(up.layerId);
        bumpEpoch();
        up = PendingUpload();  // освобождаем CPU-буфер сразу
    }
    checkGl("uploadPending");
}

// Теневые текстуры снимка символа: кадр символа рисуется своими рисунками участников, а текстуры слоёв
// редактора (кадр сцены) не трогаются - нет гонки «снимок символа подменил рот на холсте».
void Renderer::uploadShadows() {
    PendingUpload up;
    while (uploadQueue::popShadow(up)) {
        const size_t bytes = static_cast<size_t>(up.width) * up.height * 4 * 4 / 3;
        if (up.width <= 0 || up.height <= 0 || bytes > kTextureBudgetBytes ||
            textureBytes_ > kTextureBudgetBytes - bytes) {
            LOGW("shadow texture budget exceeded, layer %d", up.layerId);
            continue;
        }
        GLint maxTex = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
        if (up.width > maxTex || up.height > maxTex) continue;
        Tex t;
        if (!makeTexture(up, bytes, t)) continue;
        auto old = shadowTex_.find(up.layerId);
        if (old != shadowTex_.end()) {
            glDeleteTextures(1, &old->second.id);
            textureBytes_ -= std::min(textureBytes_, old->second.bytes);
            shadowTex_.erase(old);
        }
        shadowTex_[up.layerId] = std::move(t);
        textureBytes_ += bytes;
        up = PendingUpload();
    }
    checkGl("uploadShadows");
}

// ---------------------------------------------------------------- цели рендера

void Renderer::bindTarget(GLuint fbo) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    curFbo_ = fbo;
    glViewport(0, 0, width_, height_);
}

// Берёт FBO из пула (или создаёт), делает текущей целью и очищает в прозрачный.
Renderer::Fbo Renderer::acquireFbo() {
    Fbo f;
    if (!fboPool_.empty()) {
        f = fboPool_.back();
        fboPool_.pop_back();
    } else {
        glGenTextures(1, &f.tex);
        glBindTexture(GL_TEXTURE_2D, f.tex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width_, height_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenFramebuffers(1, &f.fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, f.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, f.tex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            LOGE("FBO incomplete");
        ++fboCreated_;
        LOGI("FBO #%d created %dx%d (%.1f MB)", fboCreated_, width_, height_,
             width_ * height_ * 4 / 1048576.0);
    }
    bindTarget(f.fbo);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    return f;
}

void Renderer::releaseFbo(const Fbo& f) { fboPool_.push_back(f); }

void Renderer::releaseTargets() {
    for (const Fbo& f : fboPool_) {
        glDeleteFramebuffers(1, &f.fbo);
        glDeleteTextures(1, &f.tex);
    }
    fboPool_.clear();
    fboCreated_ = 0;
    if (backdropFbo_) glDeleteFramebuffers(1, &backdropFbo_);
    if (backdropTex_) glDeleteTextures(1, &backdropTex_);
    backdropFbo_ = backdropTex_ = 0;
}

void Renderer::ensureBackdrop() {
    if (backdropTex_) return;
    glGenTextures(1, &backdropTex_);
    glBindTexture(GL_TEXTURE_2D, backdropTex_);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width_, height_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glGenFramebuffers(1, &backdropFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, backdropFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, backdropTex_, 0);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindFramebuffer(GL_FRAMEBUFFER, curFbo_);
}

// Копирует текущую цель в текстуру подложки (blit, а не glCopyTexSubImage2D:
// экран может быть без альфа-канала, а текстура RGBA).
void Renderer::copyBackdrop() {
    ensureBackdrop();
    glBindFramebuffer(GL_READ_FRAMEBUFFER, curFbo_);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, backdropFbo_);
    glBlitFramebuffer(0, 0, width_, height_, 0, 0, width_, height_, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, curFbo_);
}

// ---------------------------------------------------------------- рисование

// Рисует квад с текстурой tex в текущую цель.
//  - Normal (и любой неподдерживаемый режим): фиксированный blend GL, без копии подложки.
//  - Multiply/Screen/Overlay/SoftLight: копия подложки + шейдер.
//  - clipMode: слой клипуется по альфе уже нарисованной базы (src *= dstAlpha, альфа не меняется), в том числе с режимом наложения.
// Рисует единичный квадрат (6 вершин) или, для Warp, сетку 24x24; после сетки возвращает обычный VAO.
void Renderer::drawQuadOrGrid(const float* warp) {
    if (curMesh_) {
        glBindVertexArray(meshVao_);
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo_);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(curMesh_->verts.size() * sizeof(GLfloat)), curMesh_->verts.data(), GL_STREAM_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, meshIbo_);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLsizeiptr>(curMesh_->idx.size() * sizeof(GLushort)), curMesh_->idx.data(), GL_STREAM_DRAW);
        glDrawElements(GL_TRIANGLES, static_cast<GLsizei>(curMesh_->idx.size()), GL_UNSIGNED_SHORT, nullptr);
        glBindVertexArray(vao_);
    } else if (warp || curPersp_) {
        glBindVertexArray(gridVao_);
        glDrawElements(GL_TRIANGLES, gridIndexCount_, GL_UNSIGNED_SHORT, nullptr);
        glBindVertexArray(vao_);
    } else {
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }
}

void Renderer::drawTexture(GLuint tex, const GLfloat mvp[9], float opacity, int blend, bool clipMode, const float* warp) {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    const bool shaderBlend = blend == kMultiply || blend == kScreen || blend == kOverlay || blend == kSoftLight ||
                             blend == kAdd || blend == kDarken || blend == kLighten || blend == kColorDodge || blend == kColorBurn;
    if (!shaderBlend) {
        glUseProgram(progTex_);
        glEnable(GL_BLEND);
        if (clipMode)
            glBlendFuncSeparate(GL_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
        else
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUniform1i(uTexSampler_, 0);
        glUniformMatrix3fv(uTexMvp_, 1, GL_FALSE, mvp);
        glUniform1f(uTexOpacity_, opacity);
        glUniform1f(uTexLod_, exportLodBias(exportMode_));
        glUniform1i(uTexMesh_, curMesh_ ? 1 : 0);
        glUniform1i(uTexPersp_, curPersp_ ? 1 : 0);
        if (curTurb_) glUniform4fv(uTexTurb_, 1, curTurb_); else glUniform4f(uTexTurb_, 0.f, 0.f, 0.f, 0.f);
        if (curPersp_) glUniformMatrix3fv(uTexH_, 1, GL_FALSE, curPersp_);
        glUniform1i(uTexWarp_, warp ? 1 : 0);
        if (warp) glUniform2fv(uTexOff_, 16, warp);
        drawQuadOrGrid(warp);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        return;
    }
    copyBackdrop();
    glUseProgram(progBlend_);
    glDisable(GL_BLEND);  // шейдер выдаёт готовый результат
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, backdropTex_);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(uBlTex_, 0);
    glUniform1i(uBlBackdrop_, 1);
    glUniform1i(uBlMode_, blend);
    glUniform1i(uBlClip_, clipMode ? 1 : 0);
    glUniformMatrix3fv(uBlMvp_, 1, GL_FALSE, mvp);
    glUniform1f(uBlOpacity_, opacity);
    glUniform1f(uBlLod_, exportLodBias(exportMode_));
    glUniform1i(uBlMesh_, curMesh_ ? 1 : 0);
    glUniform1i(uBlPersp_, curPersp_ ? 1 : 0);
    if (curTurb_) glUniform4fv(uBlTurb_, 1, curTurb_); else glUniform4f(uBlTurb_, 0.f, 0.f, 0.f, 0.f);
    if (curPersp_) glUniformMatrix3fv(uBlH_, 1, GL_FALSE, curPersp_);
    glUniform1i(uBlWarp_, warp ? 1 : 0);
    if (warp) glUniform2fv(uBlOff_, 16, warp);
    drawQuadOrGrid(warp);
    glEnable(GL_BLEND);
}

// Один проход эффекта: читает srcTex, пишет в текущую цель (она уже очищена и привязана).
void Renderer::runEffect(GLuint srcTex, const float* e) {
    if (!progFx_) return;
    float va[6];
    viewAffine(va);
    const float sc = std::sqrt(va[0] * va[0] + va[1] * va[1]);
    const float ox = va[4], oy = va[5];
    glUseProgram(progFx_);
    glDisable(GL_BLEND);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, srcTex);
    glUniform1i(uFxTex_, 0);
    glUniformMatrix3fv(uFxMvp_, 1, GL_FALSE, kFullscreen);
    glUniform2f(uFxSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform2f(uFxCanvas_, static_cast<float>(canvasW_), static_cast<float>(canvasH_));
    glUniform4f(uFxView_, sc, ox, oy, e[9]);
    glUniform1i(uFxMode_, static_cast<int>(e[0]));
    glUniform4f(uFxP0_, e[1], e[2], e[3], e[4]);
    glUniform4f(uFxP1_, e[5], e[6], e[7], e[8]);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
}

// Слой с эффектами: слой -> FBO A, затем каждый эффект читает A и пишет в B (A и B меняются местами),
// в конце результат накладывается на родителя с прозрачностью и режимом слоя.
void Renderer::drawLayer(const Item& it, float opacity, int blend, bool clipMode) {
    const auto fx = effects_.find(it.layerId);
    if (!applyLayerEffect(clipMode, fx != effects_.end(), progFx_ != 0, findTex(it.layerId) != nullptr)) {
        drawLayerPlain(it, opacity, blend, clipMode);
        return;
    }
    // Эффекты считаются только в экранной рамке слоя + охват цепочки (scissor), а не на весь экран:
    // групповой эффект на 60 слоях раньше давал 60 x (очистка + проходы + наложение) полноэкранных FBO.
    float va[6];
    viewAffine(va);
    const float sc = std::sqrt(va[0] * va[0] + va[1] * va[1]);
    std::vector<const float*> chain;
    chain.reserve(fx->second.size());
    for (const auto& e : fx->second) chain.push_back(e.data());
    const float reach = chainReach(chain.data(), static_cast<int>(chain.size()));
    const bool deformed = meshes_.count(it.layerId) || warps_.count(it.layerId) || persps_.count(it.layerId) || turbs_.count(it.layerId);
    // Кэш результата: та же сцена и вид - без проходов эффектов; во время жеста камеры - с преобразованием вида.
    const bool cacheable = fxCacheEnabled() && !deformed && reach >= 0.f;
    if (cacheable && drawFxFromCache(it, va, reach, opacity, blend)) return;
    const float W = static_cast<float>(width_), H = static_cast<float>(height_);
    ScreenRect work{0.f, 0.f, W, H}, clearArea = work;
    if (reach >= 0.f && !deformed) {
        const float pad = reach * sc + 2.f;
        const ScreenRect body = layerScreenRect(va, it.m);
        if (body.empty() || outsideTarget(expand(body, pad), W, H)) { ++stats_.culled; return; }
        work = intersect(expand(body, pad), W, H);
        clearArea = intersect(expand(body, pad * 2.f + 2.f), W, H);   // чтения за рамкой видят нули, а не мусор пула
    }
    int box[4] = {0, 0, width_, height_}, cbox[4] = {0, 0, width_, height_};
    const bool limited = !(work.x0 <= 0.f && work.y0 <= 0.f && work.x1 >= W && work.y1 >= H);
    if (limited) {
        ++stats_.scissoredFx;
        scissorBox(clearArea, height_, cbox);
        scissorBox(work, height_, box);
        glEnable(GL_SCISSOR_TEST);
        glScissor(cbox[0], cbox[1], cbox[2], cbox[3]);
    } else if (canvasScissorOn_) {
        glDisable(GL_SCISSOR_TEST);   // внутренние проходы - на всю цель, обрезка кадром только при наложении
    }
    const GLuint parent = curFbo_;
    Fbo a = acquireFbo();
    if (limited) glScissor(box[0], box[1], box[2], box[3]);
    drawLayerPlain(it, 1.f, kNormal, false);
    for (const auto& e : fx->second) {
        if (limited) glScissor(cbox[0], cbox[1], cbox[2], cbox[3]);   // очистка с запасом
        Fbo b = acquireFbo();
        if (limited) glScissor(box[0], box[1], box[2], box[3]);       // проход - только рамка
        runEffect(a.tex, e.data());
        releaseFbo(a);
        a = b;
        ++stats_.fxPasses;
    }
    if (cacheable) storeFxCache(it, a.fbo, box, va);
    bindTarget(parent);
    // Наложение: рамка слоя, пересечённая с обрезкой кадра (экспорт).
    if (limited || canvasScissorOn_) {
        ScreenRect comp = work;
        if (canvasScissorOn_) {
            comp.x0 = std::max(comp.x0, canvasScissor_.x0); comp.y0 = std::max(comp.y0, canvasScissor_.y0);
            comp.x1 = std::min(comp.x1, canvasScissor_.x1); comp.y1 = std::min(comp.y1, canvasScissor_.y1);
        }
        int cb[4];
        if (!scissorBox(comp, height_, cb)) cb[2] = cb[3] = 0;
        glEnable(GL_SCISSOR_TEST);
        glScissor(cb[0], cb[1], cb[2], cb[3]);
    }
    compositeFbo(a, opacity, blend);
    releaseFbo(a);
    restoreCanvasScissor();
}

// ---------------------------------------------------------------- кэш результата эффектов

namespace {
constexpr size_t kFxCacheBudget = 128u * 1024u * 1024u;
}

bool Renderer::fxCacheEnabled() const {
    return !exportMode_ && !useShadows_ && !paintActive_ && screenFbo_ == 0;
}

bool Renderer::drawFxFromCache(const Item& it, const float va[6], float reach, float opacity, int blend) {
    auto e = fxCache_.find(it.layerId);
    if (e == fxCache_.end() || e->second.epoch != epoch_ || !e->second.tex) return false;
    FxEntry& c = e->second;
    float d[6];
    if (!fxcache::screenDelta(va, c.va, d)) return false;
    const float W = static_cast<float>(width_), H = static_cast<float>(height_);
    if (!fxcache::isIdentity(d)) {
        if (!interactive_ || !fxcache::scaleAcceptable(d)) return false;
        float dInv[6];
        if (!fxcache::invert(d, dInv)) return false;
        const float sc = std::sqrt(va[0] * va[0] + va[1] * va[1]);
        const ScreenRect body = layerScreenRect(va, it.m);
        if (body.empty()) return false;
        const ScreenRect need = intersect(expand(body, reach * sc + 2.f), W, H);
        if (need.empty()) { c.used = true; ++stats_.culled; return true; }   // слой ушёл за экран
        const ScreenRect cached{static_cast<float>(c.bx), H - static_cast<float>(c.by + c.bh),
                                static_cast<float>(c.bx + c.bw), H - static_cast<float>(c.by)};
        if (!fxcache::covers(cached, need, dInv)) return false;   // открылась непосчитанная часть - пересчёт
    }
    // Строка 0 текстуры кэша = нижняя строка рамки (GL): v = 0 -> нижний край рамки на экране.
    float ox, oy;
    fxcache::apply(d, static_cast<float>(c.bx), H - static_cast<float>(c.by), ox, oy);
    const float bw = static_cast<float>(c.bw), bh = static_cast<float>(c.bh);
    GLfloat mvp[9];
    mvpScreen(ox, oy, d[0] * bw, d[1] * bw, -d[2] * bh, -d[3] * bh, mvp);
    restoreCanvasScissor();
    drawTexture(c.tex, mvp, opacity, blend, false);
    c.used = true;
    ++stats_.fxCached;
    return true;
}

void Renderer::storeFxCache(const Item& it, GLuint srcFbo, const int box[4], const float va[6]) {
    const int bw = box[2], bh = box[3];
    if (!fxcache::entryAllowed(bw, bh, width_, height_)) { dropFxEntry(it.layerId); return; }
    const size_t bytes = static_cast<size_t>(bw) * static_cast<size_t>(bh) * 4u;
    auto found = fxCache_.find(it.layerId);
    const size_t oldBytes = (found != fxCache_.end() && found->second.tex) ? static_cast<size_t>(found->second.bw) * found->second.bh * 4u : 0u;
    if (fxCacheBytes_ - std::min(fxCacheBytes_, oldBytes) + bytes > kFxCacheBudget) { dropFxEntry(it.layerId); return; }
    FxEntry& c = fxCache_[it.layerId];
    if (c.tex && (c.bw != bw || c.bh != bh)) {
        glDeleteFramebuffers(1, &c.fbo);
        glDeleteTextures(1, &c.tex);
        fxCacheBytes_ -= std::min(fxCacheBytes_, oldBytes);
        c.fbo = c.tex = 0;
    }
    if (!c.tex) {
        glGenTextures(1, &c.tex);
        glBindTexture(GL_TEXTURE_2D, c.tex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, bw, bh);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenFramebuffers(1, &c.fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, c.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, c.tex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE || !checkGl("fx cache alloc")) {
            glDeleteFramebuffers(1, &c.fbo);
            glDeleteTextures(1, &c.tex);
            fxCache_.erase(it.layerId);
            bindTarget(curFbo_);
            return;
        }
        fxCacheBytes_ += bytes;
    }
    c.bw = bw; c.bh = bh;
    // blit учитывает scissor: на время копии выключаем (вызывающий заново ставит scissor перед наложением)
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, srcFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, c.fbo);
    glBlitFramebuffer(box[0], box[1], box[0] + bw, box[1] + bh, 0, 0, bw, bh, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, curFbo_);
    c.bx = box[0]; c.by = box[1];
    for (int i = 0; i < 6; ++i) c.va[i] = va[i];
    c.epoch = epoch_;
    c.used = true;
}

void Renderer::dropFxEntry(int layerId) {
    auto e = fxCache_.find(layerId);
    if (e == fxCache_.end()) return;
    if (e->second.fbo) glDeleteFramebuffers(1, &e->second.fbo);
    if (e->second.tex) {
        glDeleteTextures(1, &e->second.tex);
        fxCacheBytes_ -= std::min(fxCacheBytes_, static_cast<size_t>(e->second.bw) * e->second.bh * 4u);
    }
    fxCache_.erase(e);
}

void Renderer::dropFxCache() {
    for (auto& kv : fxCache_) {
        if (kv.second.fbo) glDeleteFramebuffers(1, &kv.second.fbo);
        if (kv.second.tex) glDeleteTextures(1, &kv.second.tex);
    }
    fxCache_.clear();
    fxCacheBytes_ = 0;
}

void Renderer::gcFxCache() {
    for (auto it = fxCache_.begin(); it != fxCache_.end();) {
        if (!it->second.used) {
            if (it->second.fbo) glDeleteFramebuffers(1, &it->second.fbo);
            if (it->second.tex) {
                glDeleteTextures(1, &it->second.tex);
                fxCacheBytes_ -= std::min(fxCacheBytes_, static_cast<size_t>(it->second.bw) * it->second.bh * 4u);
            }
            it = fxCache_.erase(it);
        } else {
            it->second.used = false;
            ++it;
        }
    }
}

void Renderer::restoreCanvasScissor() {
    if (canvasScissorOn_) {
        int cb[4];
        scissorBox(canvasScissor_, height_, cb);
        glEnable(GL_SCISSOR_TEST);
        glScissor(cb[0], cb[1], cb[2], cb[3]);
    } else {
        glDisable(GL_SCISSOR_TEST);
    }
}

void Renderer::drawLayerPlain(const Item& it, float opacity, int blend, bool clipMode) {
    const Tex* t = findTex(it.layerId);
    if (!t) {   // текстура ещё не загружена
        if (it.opacity > 0.001f) ++stats_.missing;
        return;
    }
    // Отсечение: слой без деформаций целиком вне цели не рисуется (зум/панорама на большом холсте).
    if (!meshes_.count(it.layerId) && !warps_.count(it.layerId) && !persps_.count(it.layerId)) {
        float va[6];
        viewAffine(va);
        const ScreenRect r = layerScreenRect(va, it.m);
        if (r.empty() || outsideTarget(expand(r, 1.f), static_cast<float>(width_), static_cast<float>(height_))) { ++stats_.culled; return; }
    }
    ++stats_.drawn;
    GLfloat mvp[9];
    mvpFor(it.m, mvp);
    const auto m = meshes_.find(it.layerId);
    curMesh_ = m == meshes_.end() ? nullptr : &m->second;   // Mesh приоритетнее Warp
    const auto pp = persps_.find(it.layerId);
    curPersp_ = pp == persps_.end() ? nullptr : pp->second.data();
    const auto tb = turbs_.find(it.layerId);
    curTurb_ = tb == turbs_.end() ? nullptr : tb->second.data();
    const auto w = warps_.find(it.layerId);
    drawTexture(t->id, mvp, opacity, blend, clipMode, curMesh_ || w == warps_.end() ? nullptr : w->second.data());
    curMesh_ = nullptr;
    curPersp_ = nullptr;
    curTurb_ = nullptr;
}

// Накладывает готовый FBO (группу или clipping-цепочку) на текущую цель.
void Renderer::compositeFbo(const Fbo& f, float opacity, int blend) {
    drawTexture(f.tex, kFullscreen, opacity, blend, false);
}

bool Renderer::drawFrame() {
    uploadPending();

    bindTarget(screenFbo_);
    if (transparentCapture_) glClearColor(0.f, 0.f, 0.f, 0.f);
    else if (exportMode_) glClearColor(bg_[0], bg_[1], bg_[2], 1.f);
    else glClearColor(0.2f, 0.2f, 0.22f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!progSolid_ || !progTex_ || !progBlend_) return false;

    glBindVertexArray(vao_);
    GLfloat mvp[9];

    // Холст: светло-серый непрозрачный прямоугольник.
    glUseProgram(progSolid_);
    mvpForRect(0.f, 0.f, static_cast<float>(canvasW_), static_cast<float>(canvasH_), mvp);
    glUniformMatrix3fv(uSolidMvp_, 1, GL_FALSE, mvp);
    glUniform4f(uSolidColor_, bg_[0], bg_[1], bg_[2], bg_[3]);
    if (!transparentCapture_) glDrawArrays(GL_TRIANGLES, 0, 6);

    // В редакторе содержимое монтажного стола видно на сером фоне; экспорт и capture обрезаны кадром.
    // Scissor действует и на очистку/композитинг FBO, поэтому внутри него группы считаются верно.
    if (clipSceneToCanvas(exportMode_)) {
        // При повороте/отражении вида холст - повёрнутый прямоугольник: scissor по его AABB.
        float a[6];
        viewAffine(a);
        const float corners[4][2] = {{0.f, 0.f}, {static_cast<float>(canvasW_), 0.f},
                                     {static_cast<float>(canvasW_), static_cast<float>(canvasH_)}, {0.f, static_cast<float>(canvasH_)}};
        float minX = 1e30f, minY = 1e30f, maxX = -1e30f, maxY = -1e30f;
        for (const auto& c : corners) {
            const float sx = a[0] * c[0] + a[2] * c[1] + a[4];
            const float sy = a[1] * c[0] + a[3] * c[1] + a[5];
            minX = std::min(minX, sx); maxX = std::max(maxX, sx);
            minY = std::min(minY, sy); maxY = std::max(maxY, sy);
        }
        const int x0 = static_cast<int>(std::floor(minX));
        const int x1 = static_cast<int>(std::ceil(maxX));
        const int y0 = static_cast<int>(std::floor(minY));
        const int y1 = static_cast<int>(std::ceil(maxY));
        glEnable(GL_SCISSOR_TEST);
        glScissor(x0, height_ - y1, std::max(0, x1 - x0), std::max(0, y1 - y0));  // начало координат снизу
        canvasScissorOn_ = true;
        canvasScissor_ = {static_cast<float>(x0), static_cast<float>(y0), static_cast<float>(x1), static_cast<float>(y1)};
    } else {
        canvasScissorOn_ = false;
    }
    stats_ = FrameStats{};

    // Обход потока операций снизу вверх.
    std::vector<Frame> stack;
    const size_t n = scene_.size();
    for (size_t i = 0; i < n; ++i) {
        const Item& it = scene_[i];

        if (it.op == kBeginGroup) {
            // FBO нужен, только если у группы прозрачность < 1 или режим не Normal/Pass-through.
            const bool isolated = (it.blend != kNormal && it.blend != kPass) || it.opacity < 0.999f;
            Frame f{isolated, it.opacity, it.blend, curFbo_, {}};
            if (isolated) f.fbo = acquireFbo();  // делает FBO текущей целью
            stack.push_back(f);

        } else if (it.op == kEndGroup) {
            if (stack.empty()) continue;
            const Frame f = stack.back();
            stack.pop_back();
            if (f.isolated) {
                bindTarget(f.parentFbo);
                compositeFbo(f.fbo, f.opacity, f.blend);
                releaseFbo(f.fbo);
            }

        } else {  // kLayer
            // Clipping: за базовым слоем идут слои с флагом clipped.
            size_t j = i + 1;
            if (!(it.flags & kFlagClipped))
                while (j < n && scene_[j].op == kLayer && (scene_[j].flags & kFlagClipped)) ++j;

            if (j == i + 1) {
                drawLayer(it, it.opacity, it.blend, false);  // обычный слой, без буферов
            } else {
                // База + clipped-слои рисуются в отдельный FBO: база с opacity 1, clipped по её альфе.
                // Затем результат кладётся на родителя с opacity и режимом БАЗЫ.
                const GLuint parent = curFbo_;
                const Fbo f = acquireFbo();
                drawLayer(it, 1.f, kNormal, false);
                for (size_t k = i + 1; k < j; ++k) drawLayer(scene_[k], scene_[k].opacity, scene_[k].blend, true);
                bindTarget(parent);
                compositeFbo(f, it.opacity, it.blend);
                releaseFbo(f);
                i = j - 1;
            }
        }
    }
    // Защита от несбалансированных групп: сворачиваем оставшиеся.
    while (!stack.empty()) {
        const Frame f = stack.back();
        stack.pop_back();
        if (f.isolated) {
            bindTarget(f.parentFbo);
            compositeFbo(f.fbo, f.opacity, f.blend);
            releaseFbo(f.fbo);
        }
    }

    if (exportToSurface_) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, exportFbo_);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, width_, height_, 0, 0, width_, height_, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }
    bindTarget(0);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_SCISSOR_TEST);   // рамка выделения может выходить за холст
    if (!exportMode_) gcFxCache();
    if (!exportMode_) drawSelection();
    glBindVertexArray(0);
    checkGl("drawFrame");
    return !uploadQueue::empty();
}

int Renderer::readPixel(int x, int y) const {
    if (x < 0 || y < 0 || x >= width_ || y >= height_) return 0;
    unsigned char px[4] = {0, 0, 0, 0};
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glReadPixels(x, height_ - 1 - y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    return (0xFF << 24) | (px[0] << 16) | (px[1] << 8) | px[2];
}

void Renderer::release() {
    for (auto& kv : textures_) glDeleteTextures(1, &kv.second.id);
    textures_.clear();
    clearShadows();
    dropFxCache();
    textureBytes_ = 0;
    releaseTargets();
    paintFreeTargets();
    for (GLuint& gt : grainTex_) { if (gt) glDeleteTextures(1, &gt); gt = 0; }
    if (layerFbo_) glDeleteFramebuffers(1, &layerFbo_);
    if (dabVbo_) glDeleteBuffers(1, &dabVbo_);
    if (dabVao_) glDeleteVertexArrays(1, &dabVao_);
    if (progDab_) glDeleteProgram(progDab_);
    layerFbo_ = dabVbo_ = dabVao_ = progDab_ = 0;
    if (vbo_) glDeleteBuffers(1, &vbo_);
    if (vao_) glDeleteVertexArrays(1, &vao_);
    if (progSolid_) glDeleteProgram(progSolid_);
    if (progTex_) glDeleteProgram(progTex_);
    if (progBlend_) glDeleteProgram(progBlend_);
    vbo_ = vao_ = progSolid_ = progTex_ = progBlend_ = 0;
}

}  // namespace vt
