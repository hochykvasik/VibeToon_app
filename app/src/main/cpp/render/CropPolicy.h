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

// Спринт 46: обрезка меняет ГЕОМЕТРИЮ слоя. SceneBuilder ставит матрицу слоя на видимую рамку (единичный квадрат =
// обрезанная часть), поэтому рамка выделения, углы перспективы, сетки Mesh/Warp и деформеры строятся по видимой части.
// Текстурные координаты квадрата (u, v) переводятся в долю картинки: tex = (l, t) + (u, v) * (r - l, b - t).

// Значение uniform uUvRect вершинного шейдера: x, y, w, h или нули (w <= 0 - вся текстура, как раньше).
inline void uvRect(const float* crop, float out[4]) {
    if (!crop) { out[0] = out[1] = out[2] = out[3] = 0.f; return; }
    out[0] = crop[0]; out[1] = crop[1]; out[2] = crop[2] - crop[0]; out[3] = crop[3] - crop[1];
}

// (u, v) обрезанного квадрата -> доля текстуры (то же, что вершинный шейдер с uUvRect).
inline void toTexture(const float c[4], float u, float v, float& tu, float& tv) {
    tu = c[0] + u * (c[2] - c[0]);
    tv = c[1] + v * (c[3] - c[1]);
}

// Форма символа (под-прямоугольник rect = x, y, w, h квадрата экземпляра) у обрезанного экземпляра: квадрат слоя уже
// равен рамке обрезки, поэтому под-прямоугольник формы пересчитывается в доли рамки.
inline void aliasRect(const float c[4], const float rect[4], float out[4]) {
    const float W = c[2] - c[0], H = c[3] - c[1];
    out[0] = (rect[0] - c[0]) / W; out[1] = (rect[1] - c[1]) / H;
    out[2] = rect[2] / W; out[3] = rect[3] / H;
}

// Рамка обрезки в долях текстуры ФОРМЫ (для отбрасывания фрагментов формы вне видимой части экземпляра).
inline void formCrop(const float c[4], const float rect[4], float out[4]) {
    out[0] = (c[0] - rect[0]) / rect[2]; out[1] = (c[1] - rect[1]) / rect[3];
    out[2] = (c[2] - rect[0]) / rect[2]; out[3] = (c[3] - rect[1]) / rect[3];
}

}  // namespace croppolicy
}  // namespace vt
