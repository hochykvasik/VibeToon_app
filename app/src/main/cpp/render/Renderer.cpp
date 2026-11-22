#include "render/Renderer.h"

#include <algorithm>
#include <cmath>
#include <new>

#include "common/Log.h"
#include "render/GlUtil.h"
#include "render/CanvasClipPolicy.h"
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
const char* kFragTex = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uOpacity;
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
    outColor = texture(uTex, uv) * uOpacity;
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
    vec4 s = texture(uTex, uv) * uOpacity;
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
    if (w != width_ || h != height_) releaseTargets();  // FBO привязаны к размеру экрана
    width_ = w;
    height_ = h;
    glViewport(0, 0, width_, height_);
    LOGI("viewport %dx%d", width_, height_);
}

void Renderer::beginExport(int width, int height) {
    savedW_ = width_; savedH_ = height_;
    savedZoom_ = zoom_; savedPanX_ = panX_; savedPanY_ = panY_;
    exportMode_ = true;
    zoom_ = 1.f; panX_ = panY_ = 0.f;
    setViewport(width, height);
}

bool Renderer::captureFrame(int w, int h, bool transparent, std::vector<uint8_t>& out) {
    if (w < 1 || h < 1 || w > 4096 || h > 4096 || exportMode_) return false;
    GLuint tex = 0, fbo = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (ok) {
        beginExport(w, h);
        screenFbo_ = fbo;
        transparentCapture_ = transparent;
        int guard = 0;
        while (drawFrame() && guard++ < 500) { /* ждём все текстуры из очереди */ }
        out.assign(static_cast<size_t>(w) * h * 4, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out.data());
        transparentCapture_ = false;
        screenFbo_ = 0;
        endExport();
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &tex);
    return ok;
}

void Renderer::endExport() {
    if (!exportMode_) return;
    exportMode_ = false;
    zoom_ = savedZoom_; panX_ = savedPanX_; panY_ = savedPanY_;
    setViewport(savedW_, savedH_);
}

void Renderer::setCanvas(int width, int height) {
    canvasW_ = width > 0 ? width : 1;
    canvasH_ = height > 0 ? height : 1;
}

void Renderer::setWarps(const int* ids, const float* offsets, int n) {
    warps_.clear();
    for (int i = 0; i < n; ++i) {
        std::array<float, 32> a;
        for (int k = 0; k < 32; ++k) a[static_cast<size_t>(k)] = offsets[i * 32 + k];
        warps_[ids[i]] = a;
    }
}

void Renderer::setPerspectives(const int* ids, const float* mats, int n) {
    persps_.clear();
    for (int i = 0; i < n; ++i) {
        std::array<float, 9> a;
        for (int k = 0; k < 9; ++k) a[static_cast<size_t>(k)] = mats[i * 9 + k];
        persps_[ids[i]] = a;
    }
}

void Renderer::setTurbulence(const int* ids, const float* data, int n) {
    turbs_.clear();
    for (int i = 0; i < n; ++i) {
        std::array<float, 4> a;
        for (int k = 0; k < 4; ++k) a[static_cast<size_t>(k)] = data[i * 4 + k];
        turbs_[ids[i]] = a;
    }
}

void Renderer::setEffects(const int* ids, const int* counts, int n, const float* data) {
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
    scene_.clear();
    scene_.reserve(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) {
        const int* o = ops + i * 4;
        const float* p = params + i * 8;
        scene_.push_back({o[0], o[1], o[2], o[3], {p[0], p[1], p[2], p[3], p[4], p[5]}, p[6]});
    }
}

void Renderer::setCamera(float zoom, float panX, float panY) {
    zoom_ = zoom > 0.f ? zoom : 1.f;
    panX_ = panX;
    panY_ = panY;
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
    float scale, ox, oy;
    viewTransform(scale, ox, oy);
    o[0] = m[0] * scale / width_ * 2.f;   o[1] = -m[1] * scale / height_ * 2.f;  o[2] = 0.f;
    o[3] = m[2] * scale / width_ * 2.f;   o[4] = -m[3] * scale / height_ * 2.f;  o[5] = 0.f;
    o[6] = (ox + m[4] * scale) / width_ * 2.f - 1.f;
    o[7] = 1.f - (oy + m[5] * scale) / height_ * 2.f;
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
    float scale, ox, oy;
    viewTransform(scale, ox, oy);
    const float px = (sx - ox) / scale;   // координаты на холсте
    const float py = (sy - oy) / scale;
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
    float scale, ox, oy;
    viewTransform(scale, ox, oy);
    glUseProgram(progSolid_);
    glUniform4f(uSolidColor_, 1.f, 0.75f, 0.f, 1.f);
    GLfloat mvp[9];
    const float half = 1.5f;   // половина толщины в пикселях
    for (size_t q = 0; q + 8 <= selQuads_.size(); q += 8) {
    float sxp[4], syp[4];
    for (int i = 0; i < 4; ++i) {
        sxp[i] = ox + selQuads_[q + i * 2] * scale;
        syp[i] = oy + selQuads_[q + i * 2 + 1] * scale;
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
    const GLuint oldId = old->second.id;
    textureBytes_ -= old->second.bytes;
    old->second = std::move(t);
    textureBytes_ += bytes;
    glDeleteTextures(1, &oldId);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(binding) == oldId ? old->second.id : static_cast<GLuint>(binding));
    return 0;
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
            LOGW("texture allocation failed, layer %d unchanged", up.layerId);
            continue;
        }
        if (old != textures_.end()) {
            glDeleteTextures(1, &old->second.id);
            textureBytes_ -= old->second.bytes;
            textures_.erase(old);
        }
        textures_[up.layerId] = std::move(t);
        textureBytes_ += bytes;
        uploaded += up.rgba.size();
        up = PendingUpload();  // освобождаем CPU-буфер сразу
    }
    checkGl("uploadPending");
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
    float sc, ox, oy;
    viewTransform(sc, ox, oy);
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
    if (!applyLayerEffect(clipMode, fx != effects_.end(), progFx_ != 0, textures_.find(it.layerId) != textures_.end())) {
        drawLayerPlain(it, opacity, blend, clipMode);
        return;
    }
    const GLuint parent = curFbo_;
    Fbo a = acquireFbo();
    drawLayerPlain(it, 1.f, kNormal, false);
    for (const auto& e : fx->second) {
        Fbo b = acquireFbo();
        runEffect(a.tex, e.data());
        releaseFbo(a);
        a = b;
    }
    bindTarget(parent);
    compositeFbo(a, opacity, blend);
    releaseFbo(a);
}

void Renderer::drawLayerPlain(const Item& it, float opacity, int blend, bool clipMode) {
    auto t = textures_.find(it.layerId);
    if (t == textures_.end()) return;  // текстура ещё не загружена
    GLfloat mvp[9];
    mvpFor(it.m, mvp);
    const auto m = meshes_.find(it.layerId);
    curMesh_ = m == meshes_.end() ? nullptr : &m->second;   // Mesh приоритетнее Warp
    const auto pp = persps_.find(it.layerId);
    curPersp_ = pp == persps_.end() ? nullptr : pp->second.data();
    const auto tb = turbs_.find(it.layerId);
    curTurb_ = tb == turbs_.end() ? nullptr : tb->second.data();
    const auto w = warps_.find(it.layerId);
    drawTexture(t->second.id, mvp, opacity, blend, clipMode, curMesh_ || w == warps_.end() ? nullptr : w->second.data());
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
        float sc, ox, oy;
        viewTransform(sc, ox, oy);
        const int x0 = static_cast<int>(std::floor(ox));
        const int x1 = static_cast<int>(std::ceil(ox + canvasW_ * sc));
        const int y0 = static_cast<int>(std::floor(oy));
        const int y1 = static_cast<int>(std::ceil(oy + canvasH_ * sc));
        glEnable(GL_SCISSOR_TEST);
        glScissor(x0, height_ - y1, std::max(0, x1 - x0), std::max(0, y1 - y0));  // начало координат снизу
    }

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

    bindTarget(0);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_SCISSOR_TEST);   // рамка выделения может выходить за холст
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
