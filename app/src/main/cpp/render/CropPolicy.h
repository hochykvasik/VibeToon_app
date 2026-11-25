#pragma once
// Спринт 45: вкладка «Обрезка» - прямоугольник видимой части слоя в долях его текстуры (l, t, r, b; строка 0 = верх).
// Обрезка неразрушающая: файл картинки не меняется, фрагменты вне рамки отбрасываются шейдером (в пространстве
// текстуры слоя - поэтому рамка едет вместе с поворотом, масштабом, Mesh/Warp/перспективой и анимацией), касания
// вне рамки не ловятся. Чистые функции без GL: проверяются нативным тестом.
namespace vt {
namespace croppolicy {

// Наименьшая сторона рамки (доля текстуры): меньше - вырожденная рамка, обрезка не применяется.
constexpr float kMinSide = 1.f / 4096.f;

// Приводит рамку к допустимой: доли 0..1, l < r, t < b. false - обрезки нет (вся текстура или мусор).
inline bool sanitize(float r[4]) {
    for (int i = 0; i < 4; ++i) if (!(r[i] == r[i])) return false;   // NaN
    for (int i = 0; i < 4; ++i) r[i] = r[i] < 0.f ? 0.f : (r[i] > 1.f ? 1.f : r[i]);
    if (r[2] - r[0] < kMinSide || r[3] - r[1] < kMinSide) return false;
    return !(r[0] <= 0.f && r[1] <= 0.f && r[2] >= 1.f && r[3] >= 1.f);
}

// Видна ли точка текстуры (u, v): та же проверка, что в шейдере (границы включительно).
inline bool inside(const float r[4], float u, float v) {
    return u >= r[0] && u <= r[2] && v >= r[1] && v <= r[3];
}

// Значение uniform uCrop: рамка или нули (r <= l - шейдер ничего не отбрасывает).
inline void uniformValue(const float* crop, float out[4]) {
    for (int i = 0; i < 4; ++i) out[i] = crop ? crop[i] : 0.f;
}

// Шейдер: обрезка включена, только если r > l (нулевой uniform по умолчанию - выкл).
inline bool shaderActive(const float u[4]) { return u[2] > u[0]; }

}  // namespace croppolicy
}  // namespace vt
