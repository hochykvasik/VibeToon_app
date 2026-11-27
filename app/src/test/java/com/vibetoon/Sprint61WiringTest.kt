package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 61: проводка прокручиваемой строки действий (экран проектов, шапка редактора) и исправлений аудита -
 * текстом исходников. Поведение раскладки - Sprint61LayoutTest, исправления - Sprint61Test.
 */
class Sprint61WiringTest {
    private fun file(path: String): File = File(path).takeIf { it.exists() } ?: File("../$path").takeIf { it.exists() } ?: File("app/$path")
    private fun kt(path: String) = file("src/main/java/com/vibetoon/$path").readText()
    private val projects by lazy { kt("ui/projects/ProjectsScreen.kt") }
    private val shell by lazy { kt("ui/shell/EditorShell.kt") }
    private val strip by lazy { kt("ui/shell/ActionStrip.kt") }
    private val vm by lazy { kt("ui/MainViewModel.kt") }
    private val app by lazy { kt("ui/AppViewModel.kt") }
    private val theme by lazy { kt("ui/theme/AppTheme.kt") }

    private fun body(text: String, sig: String): String {
        val i = text.indexOf(sig)
        assertTrue("нет $sig", i >= 0)
        var depth = 0; var started = false; var j = text.indexOf('{', i)
        val start = j
        while (j < text.length) {
            val c = text[j]
            if (c == '{') { depth++; started = true } else if (c == '}') { depth--; if (started && depth == 0) break }
            j++
        }
        return text.substring(start, j + 1)
    }

    @Test fun projectsTopBarUsesScrollingStrip() {
        assertTrue(projects.contains("ActionBarLayout.plan("))
        assertTrue(projects.contains("com.vibetoon.ui.shell.ActionStrip(plan.stripDp.dp, plan.mirrored, acts"))
        // кнопки фиксированного размера - не сжимаются
        assertTrue(projects.contains("IconButton(onClick = { showSettings = true }, modifier = Modifier.size(48.dp))"))
        // заголовок больше не забирает строку весом
        assertFalse(projects.contains("Text(\"Ваши проекты\", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))"))
        assertTrue(projects.contains("overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.width(plan.titleDp.dp)"))
        // левша и поиск отдельной строкой на телефоне
        assertTrue(projects.contains("LocalAppSettings.current?.leftHanded == true"))
        assertTrue(projects.contains("if (plan.mirrored) { strip(); title() } else { title(); strip() }"))
        assertTrue(projects.contains("if (searchOn && !inlineSearch && selected.isEmpty())"))
    }

    @Test fun stripScrollsAndMirrors() {
        assertTrue(strip.contains("horizontalScroll(scroll, reverseScrolling = mirrored)"))
        assertTrue(strip.contains("ActionBarLayout.order(items, mirrored)"))
        assertTrue(strip.contains("ActionBarLayout.edgeHints(scroll.value, scroll.maxValue)"))
        assertFalse("кнопки внутри полосы не получают weight", strip.contains("weight("))
    }

    @Test fun editorTopBarRightGroupScrolls() {
        val bar = body(shell, "private fun TopBar(")
        assertTrue(bar.contains("ActionBarLayout.plan("))
        assertTrue(bar.contains("ActionStrip(plan.stripDp.dp, mirrored = false, items = acts"))
        assertTrue(bar.contains("BarIcon(Icons.Default.Settings, \"Настройки\", onClick = onSettings)"))
    }

    @Test fun auditFixesAreWired() {
        val nudge = body(vm, "fun nudgeFrameLength(")
        assertTrue(nudge.contains("FrameMath.nudgedLength(len0, delta, MAX_FRAME_HOLD)"))
        assertFalse("длина без предела больше не считается", nudge.contains("(len0 + delta).coerceAtLeast(1)"))
        assertTrue(theme.contains("SettingsBackup.restorable(cur[k], v)"))
        for (f in listOf("fun rename(id: String, name: String)", "fun duplicate(id: String)", "fun delete(id: String)")) {
            val b = body(app, f)
            assertTrue("$f: ошибки диска - сообщение", b.contains("runCatching"))
            assertTrue("$f: под замком диска", b.contains("diskLock.withLock"))
        }
        assertTrue(body(app, "fun rename(id: String, name: String)").contains("cleanProjectName"))
    }
}
