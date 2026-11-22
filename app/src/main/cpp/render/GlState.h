#pragma once
// Изоляция состояния GL для внеэкранных проходов (снимок символа, миниатюра, превью кисти, штрих).
// Все проходы, которые рисуют НЕ в кадр экрана, оборачиваются в GlStateGuard: после них привязки FBO
// (draw и read), viewport, scissor (флаг и рамка), blend (флаг, функция, уравнение), программа, VAO,
// активный текстурный блок, текстуры блоков 0 и 1 и выравнивание pack/unpack возвращаются как были.
// Без этого внеэкранный проход мог оставить привязанным свой FBO/viewport, и следующий кадр
// рисовался в чужую цель (мини-копия холста, перевёрнутая по Y, поверх слоя).
#include <GLES3/gl3.h>

namespace vt {

struct GlStateGuard {
    GLint drawFbo = 0, readFbo = 0, vp[4] = {0, 0, 0, 0}, sc[4] = {0, 0, 0, 0};
    GLint vao = 0, prog = 0, active = GL_TEXTURE0, tex0 = 0, tex1 = 0, pack = 4, unpack = 4;
    GLint srcRgb = GL_ONE, dstRgb = GL_ONE_MINUS_SRC_ALPHA, srcA = GL_ONE, dstA = GL_ONE_MINUS_SRC_ALPHA;
    GLint eqRgb = GL_FUNC_ADD, eqA = GL_FUNC_ADD;
    GLboolean scissor = GL_FALSE, blend = GL_FALSE;

    GlStateGuard() {
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFbo);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFbo);
        glGetIntegerv(GL_VIEWPORT, vp);
        glGetIntegerv(GL_SCISSOR_BOX, sc);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
        glGetIntegerv(GL_CURRENT_PROGRAM, &prog);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
        glActiveTexture(GL_TEXTURE1);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex1);
        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex0);
        glGetIntegerv(GL_PACK_ALIGNMENT, &pack);
        glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpack);
        glGetIntegerv(GL_BLEND_SRC_RGB, &srcRgb);
        glGetIntegerv(GL_BLEND_DST_RGB, &dstRgb);
        glGetIntegerv(GL_BLEND_SRC_ALPHA, &srcA);
        glGetIntegerv(GL_BLEND_DST_ALPHA, &dstA);
        glGetIntegerv(GL_BLEND_EQUATION_RGB, &eqRgb);
        glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &eqA);
        scissor = glIsEnabled(GL_SCISSOR_TEST);
        blend = glIsEnabled(GL_BLEND);
    }

    ~GlStateGuard() {
        // имя, удалённое внутри прохода, не привязываем: в GLES привязка свободного имени создаёт новый пустой объект
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, alive(drawFbo, glIsFramebuffer));
        glBindFramebuffer(GL_READ_FRAMEBUFFER, alive(readFbo, glIsFramebuffer));
        glViewport(vp[0], vp[1], vp[2], vp[3]);
        glScissor(sc[0], sc[1], sc[2], sc[3]);
        if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        glBlendFuncSeparate(static_cast<GLenum>(srcRgb), static_cast<GLenum>(dstRgb), static_cast<GLenum>(srcA), static_cast<GLenum>(dstA));
        glBlendEquationSeparate(static_cast<GLenum>(eqRgb), static_cast<GLenum>(eqA));
        glBindVertexArray(alive(vao, glIsVertexArray));
        glUseProgram(alive(prog, glIsProgram));
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, alive(tex1, glIsTexture));
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, alive(tex0, glIsTexture));
        glActiveTexture(static_cast<GLenum>(active));
        glPixelStorei(GL_PACK_ALIGNMENT, pack);
        glPixelStorei(GL_UNPACK_ALIGNMENT, unpack);
    }

    static GLuint alive(GLint name, GLboolean (*is)(GLuint)) {
        const GLuint n = static_cast<GLuint>(name);
        return n != 0 && is(n) ? n : 0;
    }

    GlStateGuard(const GlStateGuard&) = delete;
    GlStateGuard& operator=(const GlStateGuard&) = delete;
};

}  // namespace vt
