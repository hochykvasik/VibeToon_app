#pragma once
// Формы символов: экземпляр рисуется текстурой формы, обрезанной по содержимому и лежащей в под-прямоугольнике
// своего квадрата. Чистые функции (без GL): проверяются нативным тестом.
namespace vt {
namespace texalias {

// rect = x, y, w, h в долях единичного квадрата слоя (строка 0 текстуры = верх слоя).
inline bool isFull(const float rect[4]) {
    return rect[0] == 0.f && rect[1] == 0.f && rect[2] == 1.f && rect[3] == 1.f;
}

// Матрица слоя m (a, b, c, d, tx, ty: точка = m * (u, v)) -> матрица под-прямоугольника: точка = m * (x + u*w, y + v*h).
inline void subRect(const float m[6], const float rect[4], float out[6]) {
    out[0] = m[0] * rect[2]; out[1] = m[1] * rect[2];
    out[2] = m[2] * rect[3]; out[3] = m[3] * rect[3];
    out[4] = m[0] * rect[0] + m[2] * rect[1] + m[4];
    out[5] = m[1] * rect[0] + m[3] * rect[1] + m[5];
}

// (u, v) квадрата слоя -> координаты внутри формы; false - точка вне формы (пусто, попадания нет).
inline bool toForm(float u, float v, const float rect[4], float& su, float& sv) {
    if (!(rect[2] > 0.f && rect[3] > 0.f)) return false;
    su = (u - rect[0]) / rect[2];
    sv = (v - rect[1]) / rect[3];
    return su >= 0.f && su < 1.f && sv >= 0.f && sv < 1.f;
}

// Вырожденный прямоугольник (нет данных) - весь квадрат.
inline void sanitize(float rect[4]) {
    if (!(rect[2] > 1e-6f && rect[3] > 1e-6f)) { rect[0] = rect[1] = 0.f; rect[2] = rect[3] = 1.f; }
}

}  // namespace texalias
}  // namespace vt
