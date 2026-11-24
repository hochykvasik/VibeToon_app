package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 42: связка UI - поверхность на всё окно, видимая область холста, правая панель и стрелка, уведомление. */
class Sprint42WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private fun kt(path: String) = src("src/main/java/com/vibetoon/$path")
    private val shell by lazy { kt("ui/shell/EditorShell.kt") }
    private val screen by lazy { kt("ui/MainScreen.kt") }
    private val gl by lazy { kt("render/VtGLSurfaceView.kt") }
    private val work by lazy { kt("work/BackgroundWorkService.kt") }
    private val motion by lazy { kt("ui/motion/Motion.kt") }
    private val pairs by lazy { (File("../tools/i18n/pairs2.py").takeIf { it.isFile } ?: File("tools/i18n/pairs2.py")).readText() }

    private fun between(s: String, from: String, to: String): String {
        val i = s.indexOf(from); assertTrue("нет $from", i >= 0)
        val j = s.indexOf(to, i + from.length); assertTrue("нет $to", j > i)
        return s.substring(i, j)
    }

    // ---------------------------------------------------------------- 1. холст

    @Test fun surfaceIsSeparateFromGestureLayer() {
        val surface = between(screen, "fun EditorSurface(", "fun EditorCanvas(")
        assertTrue(surface.contains("AndroidView(") && surface.contains("RenderHost.view = it"))
        val canvas = screen.substring(screen.indexOf("fun EditorCanvas("))
        assertFalse("слой жестов больше не создаёт поверхность", canvas.substring(0, 1500).contains("AndroidView("))
        assertTrue(canvas.contains("vm.onViewSize("))
    }

    @Test fun shellPlacesSurfaceBehindPanelsAndCanvasInVisibleRect() {
        val s = shell.indexOf("com.vibetoon.ui.EditorSurface(vm, Modifier.fillMaxSize()")
        val c = shell.indexOf("EditorCanvas(vm, Modifier")
        val main = shell.indexOf("Column(Modifier.fillMaxSize()) {")
        assertTrue("поверхность и слой жестов - до панелей (ниже по z)", s in 0 until c && c < main)
        assertTrue(shell.contains("slotBounds.value = androidx.compose.ui.geometry.Rect(c.positionInRoot(), c.size.toSize())"))
        assertTrue(shell.contains("RenderHost.setViewRect(r.left, r.top, r.width, r.height"))
        assertFalse("старое место холста внутри Row убрано", shell.contains("EditorCanvas(vm, Modifier.fillMaxSize())"))
    }

    @Test fun visibleRectAnimatesWithSpringAndRespectsMotionSetting() {
        val block = between(shell, "LaunchedEffect(motionOn)", "LaunchedEffect(Unit)")
        assertTrue(block.contains("!motionOn) viewRect.snapTo(target)"))
        assertTrue(block.contains("spring(") && block.contains("DampingRatioNoBouncy") && block.contains("CANVAS_FIT_STIFFNESS"))
        assertTrue(shell.contains("val motionOn = com.vibetoon.ui.motion.LocalMotion.current.enabled"))
    }

    @Test fun panelsAreOpaqueOverTheSurface() {
        assertTrue(Regex("background\\(MaterialTheme\\.colorScheme\\.background\\)\\.opaqueToCanvas\\(\\)").findAll(shell).count() >= 3)
        assertTrue(shell.contains("surfaceColorAtElevation(1.dp)).opaqueToCanvas()"))
        assertTrue(shell.contains("private fun Modifier.opaqueToCanvas()"))
    }

    @Test fun rendererUsesEffectiveCameraAndMapsTouches() {
        assertTrue(gl.contains("ViewRectFit.effective(p.camera, p.viewRect, bufW, bufH"))
        assertTrue(gl.contains("RenderNative.setCamera(handle, cam.zoom, cam.panX, cam.panY"))
        assertTrue(gl.contains("ViewRectFit.toBuffer(x, y, frameParams().viewRect, bufW, bufH)"))
        assertTrue(gl.contains("ViewRectFit.toBuffer(x.toFloat(), y.toFloat(), p.viewRect, bufW, bufH)"))
        assertTrue(gl.contains("fun setViewRect(x: Float, y: Float, w: Float, h: Float, moving: Boolean)"))
        assertTrue("область переживает пересоздание вида", gl.contains("RenderHost.reapply(this)"))
        assertTrue(gl.contains("p.interactive || p.viewRectMoving"))
    }

    @Test fun timelineDragMarksMovingState() {
        assertTrue(shell.contains("onDragStart = { tlDragging.value = true }"))
        assertTrue(shell.contains("onDragEnd = { tlDragging.value = false }"))
        assertTrue(shell.contains("onDragCancel = { tlDragging.value = false }"))
    }

    // ---------------------------------------------------------------- 2. правая панель

    @Test fun edgeZoneFollowsLeftHandedMode() {
        assertTrue(shell.contains("val edgeAlign = if (com.vibetoon.domain.settings.SidePanelEdge.atRightEdge(leftHanded)) Alignment.CenterEnd else Alignment.CenterStart"))
        assertTrue(shell.contains("SidePanelEdge.opens(sum, swipePx, leftHanded)) rightOpen = true"))
        assertTrue(shell.contains("SidePanelEdge.closes(sum, swipePx, leftHanded)) rightOpen = false"))
        assertFalse("старый порог в пикселях после отпускания убран", shell.contains("if (sum < -30f) rightOpen = true"))
        // leftHanded объявлен один раз - до зоны свайпа
        assertTrue(Regex("val leftHanded = ").findAll(shell).count() == 1)
        assertTrue(shell.indexOf("val leftHanded = ") < shell.indexOf("val edgeAlign"))
    }

    @Test fun edgeHandleShowsArrowAndIsMirrored() {
        val h = between(shell, "private fun EdgeHandle(", "// ---------------------------------------------------------------- верхняя панель")
        assertTrue(h.contains("SidePanelEdge.arrowPointsLeft(open, leftHanded)) Icons.Default.ChevronLeft else Icons.Default.ChevronRight"))
        assertTrue(h.contains("hintWidthDp(phone)") && h.contains("hintHeightDp(phone)"))
        assertTrue(h.contains("contentDescription = label"))
        assertTrue(shell.contains("EdgeHandle(open = false, leftHanded = leftHanded, phone = phone) { rightOpen = true }"))
        assertTrue(shell.contains("EdgeHandle(open = true, leftHanded = leftHanded, phone = false) { rightOpen = false }"))
        assertTrue(shell.contains("Motions.hintEnter()") && shell.contains("Motions.hintExit()"))
    }

    @Test fun rightPanelUsesSofterMotionOnTabletAndPhone() {
        assertTrue(shell.contains("Motions.sidePanelEnter(fromEnd = !leftHanded)"))
        assertTrue(Regex("Motions\\.sidePanelExit\\(toEnd = !leftHanded\\)").findAll(shell).count() == 2)
        assertTrue("телефон: у левши панель слева", shell.contains("if (leftHanded) Alignment.BottomStart else Alignment.BottomEnd"))
        assertTrue(motion.contains("fun sidePanelEnter(fromEnd: Boolean)") && motion.contains("UiMotion.SIDE_PANEL_MS"))
        assertTrue(motion.contains("fun sidePanelExit(toEnd: Boolean)") && motion.contains("UiMotion.SIDE_PANEL_EXIT_MS"))
        val side = between(motion, "fun sidePanelEnter", "fun hintEnter")
        assertTrue("анимации выключены - мгновенно", side.contains("if (!LocalMotion.current.enabled) return EnterTransition.None"))
    }

    // ---------------------------------------------------------------- 3. уведомление

    @Test fun notificationHasAppNameStatusAndBigText() {
        val n = work.substring(work.indexOf("internal fun notification("))
        assertTrue(n.contains("WorkNotice.title(kind, pct)") && n.contains("WorkNotice.status(kind"))
        assertTrue(n.contains("Notification.BigTextStyle().bigText(long)"))
        assertTrue(n.contains("setCategory(Notification.CATEGORY_PROGRESS)"))
        assertTrue(n.contains("setUsesChronometer(true)"))
        assertTrue(n.contains("setProgress(100, pct, false)"))
        assertFalse("без Compose", work.contains("L(\""))
        assertTrue(work.contains("cur.copy(pct = pct, done = done, total = total)"))
    }

    @Test fun newStringsHaveEnglishPairs() {
        for (ru in listOf("VibeToon: экспорт {} %", "VibeToon: экспорт", "VibeToon: импорт {} %", "VibeToon: импорт", "VibeToon: фоновая работа",
            "Подготовка кадров", "Подготовка медиа", "можно свернуть приложение", "Нажмите, чтобы вернуться в VibeToon", "Задач: {}",
            "Кадр {} из {}", "Скрыть панель", "Показать панель"))
            assertTrue("нет пары: $ru", pairs.contains("\n$ru|"))
    }
}
