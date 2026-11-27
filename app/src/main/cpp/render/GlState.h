#pragma once
#include <GLES3/gl3.h>

namespace vt {
// All bindings/state touched by the renderer's offscreen passes. Matrices are
// uniforms, not GLES fixed-function state: each draw supplies its own MVP.
class GlState {
    GLint draw_, read_, viewport_[4], scissorBox_[4], program_, vao_, array_;
    GLint active_, textures_[2], pack_, unpack_, packBuffer_, unpackBuffer_;
    GLint srcRgb_, dstRgb_, srcAlpha_, dstAlpha_, eqRgb_, eqAlpha_;
    GLfloat clear_[4];
    GLboolean blend_, scissor_, mask_[4];
public:
    GlState() {
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw_);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read_);
        glGetIntegerv(GL_VIEWPORT, viewport_);
        glGetIntegerv(GL_SCISSOR_BOX, scissorBox_);
        glGetIntegerv(GL_CURRENT_PROGRAM, &program_);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao_);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &array_);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &active_);
        for (int i = 0; i < 2; ++i) {
            glActiveTexture(GL_TEXTURE0 + i);
            glGetIntegerv(GL_TEXTURE_BINDING_2D, &textures_[i]);
        }
        glActiveTexture(active_);
        glGetIntegerv(GL_PACK_ALIGNMENT, &pack_);
        glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpack_);
        glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &packBuffer_);
        glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpackBuffer_);
        glGetIntegerv(GL_BLEND_SRC_RGB, &srcRgb_); glGetIntegerv(GL_BLEND_DST_RGB, &dstRgb_);
        glGetIntegerv(GL_BLEND_SRC_ALPHA, &srcAlpha_); glGetIntegerv(GL_BLEND_DST_ALPHA, &dstAlpha_);
        glGetIntegerv(GL_BLEND_EQUATION_RGB, &eqRgb_); glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &eqAlpha_);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear_);
        glGetBooleanv(GL_COLOR_WRITEMASK, mask_);
        blend_ = glIsEnabled(GL_BLEND); scissor_ = glIsEnabled(GL_SCISSOR_TEST);
        // Offscreen helpers use only texture units 0 and 1, never the caller's active unit.
        glActiveTexture(GL_TEXTURE0);
    }
    ~GlState() {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw_); glBindFramebuffer(GL_READ_FRAMEBUFFER, read_);
        glViewport(viewport_[0], viewport_[1], viewport_[2], viewport_[3]);
        glScissor(scissorBox_[0], scissorBox_[1], scissorBox_[2], scissorBox_[3]);
        glUseProgram(program_); glBindVertexArray(vao_); glBindBuffer(GL_ARRAY_BUFFER, array_);
        for (int i = 0; i < 2; ++i) { glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, textures_[i]); }
        glActiveTexture(active_);
        glPixelStorei(GL_PACK_ALIGNMENT, pack_); glPixelStorei(GL_UNPACK_ALIGNMENT, unpack_);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer_); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer_);
        glBlendEquationSeparate(eqRgb_, eqAlpha_);
        glBlendFuncSeparate(srcRgb_, dstRgb_, srcAlpha_, dstAlpha_);
        glClearColor(clear_[0], clear_[1], clear_[2], clear_[3]);
        glColorMask(mask_[0], mask_[1], mask_[2], mask_[3]);
        if (blend_) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (scissor_) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
    }
    GlState(const GlState&) = delete;
    GlState& operator=(const GlState&) = delete;
};
}
