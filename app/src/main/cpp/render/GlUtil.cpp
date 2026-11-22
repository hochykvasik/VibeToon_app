#include "render/GlUtil.h"

#include <vector>

#include "common/Log.h"

namespace vt {
namespace {

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = GL_FALSE;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetShaderiv(s, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(len > 1 ? len : 1);
        glGetShaderInfoLog(s, static_cast<GLsizei>(log.size()), nullptr, log.data());
        LOGE("shader compile failed (%s): %s", type == GL_VERTEX_SHADER ? "vertex" : "fragment",
             log.data());
        glDeleteShader(s);
        return 0;
    }
    return s;
}

}  // namespace

GLuint buildProgram(const char* vertexSrc, const char* fragmentSrc) {
    GLuint vs = compile(GL_VERTEX_SHADER, vertexSrc);
    GLuint fs = compile(GL_FRAGMENT_SHADER, fragmentSrc);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return 0;
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetProgramiv(p, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(len > 1 ? len : 1);
        glGetProgramInfoLog(p, static_cast<GLsizei>(log.size()), nullptr, log.data());
        LOGE("program link failed: %s", log.data());
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

bool checkGl(const char* where) {
    bool clean = true;
    for (GLenum e = glGetError(); e != GL_NO_ERROR; e = glGetError()) {
        LOGE("GL error 0x%x at %s", e, where);
        clean = false;
    }
    return clean;
}

}  // namespace vt
