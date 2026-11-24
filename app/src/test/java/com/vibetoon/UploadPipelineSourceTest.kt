package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 31: обмен Kotlin <-> C++ без лишних выделений и копий (поведение пула проверяет нативный тест). */
class UploadPipelineSourceTest {
    private fun cpp(path: String) = File("src/main/cpp/$path").readText()

    @Test fun bridgeTakesBufferFromPoolAndCopiesOnceWithoutStride() {
        val bridge = cpp("jni/RenderBridge.cpp")
        assertTrue(bridge.contains("vt::uploadQueue::acquireBuffer(total)"))
        assertTrue(bridge.contains("vt::uploadbuf::singleCopy(info.stride, info.width)"))
        assertTrue(bridge.contains("std::memcpy(up.rgba.data(), src, total)"))
        assertFalse("новый вектор на каждый кадр", bridge.contains("up.rgba.resize("))
        // устаревший кадр (билет не совпал) возвращает буфер в пул
        assertTrue(bridge.contains("vt::uploadQueue::recycleBuffer(std::move(up.rgba))"))
    }

    @Test fun rendererRecyclesBuffersAndUsesPboWithFallback() {
        val r = cpp("render/Renderer.cpp")
        assertTrue(r.contains("if (!uploadQueue::pop(up)) break;"))
        assertTrue(r.contains("recycleUpload(up)"))
        assertFalse("буфер освобождался вместо возврата в пул", r.contains("up = PendingUpload();"))
        assertTrue(r.contains("glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo)"))
        assertTrue(r.contains("glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)"))
        assertTrue(r.contains("uploadPboBroken_ = true"))
        assertTrue(r.contains("if (!uploadViaPbo(up))"))
        assertTrue(r.contains("for (GLuint& pb : uploadPbo_)"))   // release() удаляет PBO
    }

    @Test fun ciRunsBufferPolicyTest() {
        val ci = File("../.github/workflows/android-build.yml").readText()
        assertTrue(ci.contains("UploadBufferPolicyTest.cpp"))
        assertTrue(ci.contains("UploadQueueTest.cpp app/src/main/cpp/render/UploadQueue.cpp"))
    }
}
