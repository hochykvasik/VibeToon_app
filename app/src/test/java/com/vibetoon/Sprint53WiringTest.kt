package com.vibetoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 53: уроки и подсказки. Урок «Покадровая анимация», шаг покадровой анимации в «Обзоре приложения», каждая
 * подсвечиваемая кнопка уроков существует в редакторе, названия в «…» - настоящие подписи интерфейса, устаревших
 * названий («Сгенерировать», «По контуру», «+Сетка», «Добавить пустой кадр»…) больше нет. Проверка по исходникам.
 */
class Sprint53WiringTest {
    private fun root(): File = listOf(File("src/main/java/com/vibetoon"), File("app/src/main/java/com/vibetoon")).first { it.isDirectory }
    private fun kt(path: String) = File(root(), path).readText()
    private val tut by lazy { kt("ui/projects/TutorialsScreen.kt") }
    private val tutorials by lazy { tut.substring(tut.indexOf("val TUTORIALS"), tut.indexOf("/** Ряд карточек")) }

    /** Все исходники приложения, кроме сгенерированных таблиц перевода. */
    private val sources by lazy {
        root().walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').contains("/ui/i18n/") }.toList()
    }

    /** Блоки уроков: заголовок -> текст блока. */
    private val blocks by lazy {
        val starts = Regex("""Tutorial\(\s*"([^"]+)"""").findAll(tutorials).toList()
        starts.mapIndexed { i, m -> m.groupValues[1] to tutorials.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: tutorials.length) }.toMap()
    }

    /** Цели подсказок по шагам - как в Tutorial.coachSession(): первая кнопка шага, coach или подпись. */
    private fun coachTargets(block: String): List<Pair<String, String?>> {
        val noTarget = Regex("""private val NO_TARGET = setOf\((.*)\)\n""").find(tut)!!.groupValues[1].let { s -> Regex(""""([^"]+)"""").findAll(s).map { it.groupValues[1] }.toSet() }
        val steps = Regex("""TutorialStep\("([^"]+)"""").findAll(block).toList()
        return steps.mapIndexed { i, m ->
            val part = block.substring(m.range.first, steps.getOrNull(i + 1)?.range?.first ?: block.length)
            val b = Regex("""UiButton\((?:null|Icons\.Default\.\w+), "([^"]+)", "[^"]*"([^)]*)\)""").find(part)
            val target = b?.let {
                val label = it.groupValues[1]
                val chip = it.groupValues[2].contains("chip = true")
                val coach = Regex("""coach = "([^"]+)"""").find(it.groupValues[2])?.groupValues?.get(1)
                (coach ?: when (label) {
                    "Кости" -> if (chip) "chip:Кости" else "Кости"
                    "Сетка" -> "chip:Сетка"
                    "Объект" -> "chip:Объект"
                    "FX" -> "Эффекты (FX)"
                    else -> label
                }).takeIf { label !in noTarget }
            }
            m.groupValues[1] to target
        }
    }

    /** Все id подсказок, которые регистрирует редактор. */
    private val registered by lazy {
        val ids = HashSet<String>()
        val all = sources.joinToString("\n") { it.readText() }
        Regex("""coachTarget\("([^"]+)"\)""").findAll(all).forEach { ids += it.groupValues[1] }
        Regex("""(?:TlIcon|RepeatIcon|BarIcon|RailButton)\(Icons\.Default\.\w+, "([^"]+)"""").findAll(all).forEach { ids += it.groupValues[1] }
        // подпись меняется с состоянием: «Свернуть таймлайн» / «Развернуть таймлайн»
        Regex("""TlIcon\(Icons\.Default\.\w+, if \([^)]*\) "([^"]+)" else "([^"]+)"""").findAll(all).forEach { ids += it.groupValues[1]; ids += it.groupValues[2] }
        val toolEnum = kt("ui/shell/EditorShell.kt").let { s -> s.substring(s.indexOf("enum class ToolId")).let { it.substring(0, it.indexOf("\n}")) } }
        Regex("""[A-Z_]+\("([^"]+)", Icons""").findAll(toolEnum).forEach { ids += it.groupValues[1] }
        ids += "Тестовый PSD"   // цель внутри Popup меню «Действия» (особый случай CoachOverlay)
        Regex("""CoachMenuItem\("([^"]+)"""").findAll(all).forEach { ids += it.groupValues[1] }   // спринт 57: пункты меню «Действия»
        ids
    }

    @Test fun everyCoachTargetExistsInEditor() {
        assertTrue(registered.containsAll(listOf("+ Кадр", "Калька (Onion Skin)", "Новый кадр", "Дубль кадра", "< Кадр", "Добавить слой", "Мультивыбор", "Трансформ")))
        val missing = blocks.flatMap { (title, b) -> coachTargets(b).mapNotNull { (step, t) -> if (t != null && t !in registered) "$title / $step -> $t" else null } }
        assertTrue("нет цели в редакторе: $missing", missing.isEmpty())
    }

    @Test fun quotedNamesAreRealLabels() {
        val lits = HashSet<String>()
        for (f in sources) if (!f.name.endsWith("TutorialsScreen.kt")) Regex(""""((?:[^"\\]++|\\.)*+)"""").findAll(f.readText()).forEach { lits += it.groupValues[1] }
        // склонения, названия самих уроков и описательные имена
        val allowed = setOf("Действиях", "Настройках", "Обучения", "Покадровая анимация", "Новый кадр: N", "ключ", "на двойках", "на единицах")
        val bad = Regex("«([^»]+)»").findAll(tutorials).map { it.groupValues[1] }.distinct().filter { n ->
            val head = n.substringBefore("…")
            n !in allowed && n !in lits && lits.none { it.startsWith(head) }
        }.toList()
        assertTrue("нет таких подписей в интерфейсе: $bad", bad.isEmpty())
    }

    @Test fun frameByFrameTutorialCoversTheWholeWorkflow() {
        val b = blocks["Покадровая анимация"] ?: error("нет урока «Покадровая анимация»")
        assertTrue(b.contains("DiagramKind.FRAMES") && b.contains("lesson = true"))
        for (s in listOf("Анимационный (покадровый)", "Новый кадр: N", "от 1 до 48", "+ Кадр", "Дубль кадра", "Удалить кадр", "< Кадр", "Кадр >",
            "Удерживайте кнопку", "одним «Отменить»", "пустой промежуток", "Рисунки отдельными слоями", "Калька (Onion Skin)", "красным", "зелёным",
            "«Глубина»", "«Прозрачность»", "«Цвет»", "«Шаг»", "«Калька во время воспроизведения»", "«Соседние слои-рисунки… тоже»", "Долгое нажатие",
            "Вперёд/назад: кадры (иначе рисунки)"))
            assertTrue("урок: $s", b.contains(s))
        val targets = coachTargets(b).mapNotNull { it.second }
        assertTrue(targets.containsAll(listOf("Добавить слой", "Новый кадр", "+ Кадр", "Дубль кадра", "Длиннее на кадр (удерживайте)", "< Кадр", "Калька (Onion Skin)", "Играть")))
        assertTrue("схема", tut.contains("DiagramKind.FRAMES -> {") && tut.contains("MESH, FRAMES }"))
    }

    @Test fun welcomeTourHasFrameStepAndIsExplicit() {
        val tour = blocks["Обзор приложения"]!!
        assertTrue(tour.contains("lesson = true, tour = true"))
        assertEquals(1, Regex("""tour = true""").findAll(tutorials).count())
        val steps = coachTargets(tour)
        val frame = steps.indexOfFirst { it.second == "+ Кадр" }
        assertTrue("шаг покадровой анимации в туре", frame > 0)
        // шаг объясняет, что строка рисунков видна только у выбранного слоя (после учебных фигур выделения нет)
        val text = tour.substring(tour.indexOf("TutorialStep(\"Покадровая анимация\""))
        assertTrue(text.contains("Выберите слой") && text.contains("Калька (Onion Skin)") && text.contains("Новый кадр: N"))
        // устаревший порядок «первый урок с учебным проектом» больше не определяет тур
        assertTrue(tut.contains("val WELCOME_TOUR: Tutorial get() = TUTORIALS.first { it.tour }"))
        for (f in listOf("MainActivity.kt", "ui/projects/ProjectsScreen.kt")) {
            assertTrue(f, kt(f).contains("WELCOME_TOUR.coachSession()"))
            assertFalse(f, kt(f).contains("first { it.lesson }"))
        }
    }

    @Test fun staleNamesAreGone() {
        val stale = listOf("Сгенерировать", "По контуру»", "«+Сетка»", "Добавить пустой кадр", "в группы PSD класть нельзя", "＋ Кость", "«＋ Добавить»", "«+ → PSD", "f(x) задаёт", "«Смазкой»", "кнопка «Новый слой») - на нём")
        val found = sources.flatMap { f -> stale.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertTrue("устаревшие названия: $found", found.isEmpty())
        val vm = kt("ui/MainViewModel.kt")
        assertTrue(vm.contains("\"Сетка: выберите слой и нажмите «Авто-сетка» или коснитесь его инструментом «+Пин»\""))
        assertTrue(vm.contains("рисуйте, «+ Кадр» ставит следующий рисунок"))
        assertTrue(kt("ui/shell/EditorShell.kt").contains("Text(\"Авто-сетка\"") || kt("ui/shell/EditorShell.kt").contains("\"Авто-сетка\""))
    }

    @Test fun coachCardAvoidsLowTargetsAndExplainsHiddenButtons() {
        val coach = kt("ui/shell/Coach.kt")
        assertTrue(coach.contains("com.vibetoon.domain.tutorial.CoachPlacement.cardOnTop(rect.center.y, viewport.height)"))
        // спринт 59: окно перетаскивается; пока его не двигали - авторазмещение сверху или снизу по cardOnTop
        assertTrue(coach.contains("CoachWindow.place(Coach.customPos, w.toFloat(), card.height.toFloat(), areaW, areaH, margin, cardOnTop)"))
        assertTrue(coach.contains("Кнопка сейчас не видна: выберите слой"))
    }
}
