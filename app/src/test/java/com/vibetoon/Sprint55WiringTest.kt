package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 55: проводка оптимизаций (Kotlin -> JNI -> C++) и инвариантов, текстом исходников. */
class Sprint55WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val gl by lazy { kt("render/VtGLSurfaceView.kt") }
    private val renderer by lazy { src("src/main/cpp/render/Renderer.cpp") }
    private val rendererH by lazy { src("src/main/cpp/render/Renderer.h") }

    private fun body(text: String, start: String): String {
        val i = text.indexOf(start)
        assertTrue("нет $start", i >= 0)
        var depth = 0; var j = text.indexOf('{', i); val from = j
        while (j < text.length) {
            if (text[j] == '{') depth++ else if (text[j] == '}') { depth--; if (depth == 0) break }
            j++
        }
        return text.substring(from, j + 1)
    }

    @Test fun fxCacheKeyedByLayerContentNotGlobalEpoch() {
        val draw = body(renderer, "void Renderer::drawLayer(const Item& it, float opacity, int blend, bool clipMode)")
        assertTrue(draw.contains("fxContentKey(it, fx->second)"))
        assertTrue(draw.contains("drawFxFromCache(it, va, reach, opacity, blend, fxKey)"))
        assertTrue(draw.contains("storeFxCache(it, a.fbo, box, va, fxKey)"))
        assertFalse(draw.contains("std::vector<const float*> chain;"))   // без вектора на каждый слой каждого кадра
        val cached = body(renderer, "bool Renderer::drawFxFromCache(")
        assertTrue(cached.contains("fxkey::entryValid(e->second.key, key"))
        assertFalse(cached.contains("epoch_"))
        assertTrue(rendererH.contains("uint64_t key = 0;"))
    }

    @Test fun glErrorsNotPolledEveryFrame() {
        val frame = body(renderer, "bool Renderer::drawFrame()")
        assertTrue(frame.contains("if (glerr::frameCheckDue(++frameNo_, exportMode_)) checkGl(\"drawFrame\")"))
        assertTrue(frame.contains("std::vector<Frame>& stack = frameStack_;"))
        assertTrue(body(renderer, "void Renderer::uploadPending()").contains("if (glerr::afterQueue(processed)) checkGl(\"uploadPending\")"))
        assertTrue(body(renderer, "void Renderer::uploadShadows()").contains("if (glerr::afterQueue(processed)) checkGl(\"uploadShadows\")"))
    }

    @Test fun clearTexturesReleasesAllProjectGpuState() {
        val clear = body(renderer, "void Renderer::clearTextures()")
        assertTrue(clear.contains("dropFxCache();"))
        assertTrue(clear.contains("dropGroupCache();"))
        assertTrue(clear.contains("texGen_.clear();"))
        assertTrue(clear.contains("droppedTex_.clear();"))
        assertTrue(rendererH.contains("TexTombstones droppedTex_;"))
    }

    @Test fun nativeSceneSentOnlyWhenChanged() {
        val apply = body(gl, "private fun applyScene(s: SceneData)")
        for (slot in listOf("CANVAS", "SCENE", "WARPS", "MESHES", "PERSP", "TURB", "FX", "ALIASES", "CROPS"))
            assertTrue(slot, apply.contains("SceneSectionTracker.$slot"))
        val capture = body(gl, "fun capture(scene: SceneData?, width: Int, height: Int, transparent: Boolean, background: Int, flags: Int = 0): ByteArray?")
        assertTrue(capture.contains("editorSceneStale = true"))
        assertFalse(capture.contains("lastScene?.let { applyScene(it) }"))   // сцена редактора - лениво, перед кадром
        val frame = body(gl, "override fun onDrawFrame(gl: GL10?)")
        assertTrue(frame.contains("ensureEditorScene()"))
        assertTrue(frame.contains("if (afterFrameChecks.isNotEmpty())"))
        assertTrue(body(gl, "fun hitTest(x: Float, y: Float): Int").contains("ensureEditorScene()"))
        assertTrue(body(gl, "override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)").contains("sections.reset()"))
    }

    @Test fun editorReleasesCachesOnTrimAndClose() {
        val trim = body(vm, "fun onTrimMemory(level: Int)")
        assertTrue(trim.contains("playScenes.clear()"))
        assertTrue(trim.contains("deformCache.clear()"))
        assertTrue(trim.contains("cellCache.trim(level)"))
        assertTrue(trim.contains("formCache.trim("))
        val reset = body(vm, "private fun resetEditor()")
        assertTrue(reset.indexOf("staleAfterPlay.clear()") in 0 until reset.indexOf("pause()"))
        assertTrue(reset.contains("playScenes.clear()"))
        assertTrue(reset.contains("resetForms(dropTextures = true)"))
        assertTrue(reset.contains("contentBox.clear()"))
        val box = body(vm, "private suspend fun ensureBox(id: Int)")
        assertTrue(box.contains("val gen = thumbGen"))
        assertTrue(box.contains("if (gen == thumbGen) contentBox[id] = box"))
        val main = kt("MainActivity.kt")
        assertTrue(main.contains("editor.onTrimMemory(level)"))
    }

    @Test fun playDoesNotRecomposeHiddenOverlaysOrScanMediaPerInstance() {
        val screen = kt("ui/MainScreen.kt")
        val pivot = body(screen, "private fun PivotOverlay(vm: MainViewModel)")
        assertTrue(pivot.indexOf("if (playing) return") < pivot.indexOf("frame < 0"))
        val forms = body(vm, "private fun formInstances()")
        assertTrue(forms.contains("val defs = HashMap<Int, MediaItem>()"))
        assertFalse(forms.contains("media.firstOrNull { it.id == inst.symRef"))
        assertTrue(kt("ui/shell/EditorShell.kt").contains("val projectRows = inProject.chunked(3)"))
    }

    @Test fun clipSpeedUsesConsistentFactors() {
        val sp = body(vm, "fun setClipSpeed(speed: Float)")
        assertTrue(sp.contains("ClipSpeed.factors(clip.keySpeed, clip.speed, target)"))
        assertTrue(sp.contains("ClipSpeed.length(clip.length, fs.length)"))
        assertFalse(sp.contains("retimeTrack(id, legacy)"))
    }

    @Test fun nativePerfTestRunsInCi() {
        val ci = (File("../.github/workflows/android-build.yml").takeIf { it.isFile } ?: File(".github/workflows/android-build.yml")).readText()
        assertTrue(ci.contains("app/src/test/cpp/RenderPerfPolicyTest.cpp"))
    }
}
