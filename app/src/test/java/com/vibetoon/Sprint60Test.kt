package com.vibetoon

import com.vibetoon.domain.project.ContentBoxGate
import com.vibetoon.domain.project.SymbolMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 60: вечный ANR после «Создать символ → Слои как кадры» (новый проект с PSD) и защита создания символа.
 *
 * Модель ниже повторяет проводку MainViewModel: рамка выделения запрашивает границы содержимого слоя, расчёт идёт
 * задачей главного потока (очередь), у экземпляра символа без готовых форм расчёт не даёт результата.
 */
class Sprint60Test {
    private class MainLoopModel(private val gate: ContentBoxGate?) {
        val cache = HashMap<Int, FloatArray>()
        var forms = 0          // готовые формы символа экземпляра
        var runs = 0           // сколько раз считались границы
        var frames = 0         // сколько раз перерисована рамка выделения
        private val queue = ArrayDeque<() -> Unit>()

        /** ensureBox: форм нет - «данных нет» без кэша (как у экземпляра сразу после создания символа). */
        private fun ensure(id: Int) { if (forms > 0) cache[id] = floatArrayOf(0.4f, 0.55f, 0.6f, 0.7f) }

        fun schedule(id: Int) {
            if (gate != null && !gate.begin(id)) return
            queue.addLast {
                runs++
                ensure(id)
                val cached = cache[id] != null
                if (gate == null || gate.end(id, cached)) selectionFrame(id)
            }
        }

        /** updateSelectionFrame -> contentQuad: границ нет в кэше - запросить расчёт. */
        fun selectionFrame(id: Int) {
            frames++
            if (cache[id] == null) schedule(id)
        }

        /** Форма готова: рамка экземпляра сбрасывается (dropBox) и миниатюра пересоздаётся (invalidateThumb). */
        fun formReady(id: Int) {
            forms++
            cache.remove(id); gate?.forget(id)
            schedule(id)
        }

        /** Разобрать очередь главного потока; false - очередь не кончилась за [cap] задач (главный поток завис). */
        fun drain(cap: Int = 10_000): Boolean {
            var n = 0
            while (queue.isNotEmpty()) {
                if (n++ >= cap) return false
                queue.removeFirst()()
            }
            return true
        }
    }

    private val inst = 1_000_123

    @Test fun oldWiringSpinsForeverWhileTheSymbolHasNoForms() {
        // прежняя проводка: расчёт без результата сразу перерисовывал рамку, та снова запрашивала расчёт
        val m = MainLoopModel(gate = null)
        m.selectionFrame(inst)   // selectLayer(экземпляр) в конце createSymbol
        assertFalse("главный поток не освобождается", m.drain())
        assertTrue(m.runs >= 10_000)
    }

    @Test fun createSymbolSelectingInstanceWithoutFormsReturnsControlToTheUi() {
        val m = MainLoopModel(ContentBoxGate())
        m.selectionFrame(inst)
        assertTrue(m.drain())
        assertEquals(1, m.runs)       // одна попытка, без повторов по кругу
        assertEquals(1, m.frames)     // рамка не перерисовывается без нового результата
        assertFalse(m.cache.containsKey(inst))
        // любые перерисовки рамки (смена кадра, инспектор) больше не запускают расчёт, пока форм нет
        repeat(50) { m.selectionFrame(inst) }
        assertTrue(m.drain())
        assertEquals(1, m.runs)
    }

    @Test fun firstReadyFormGivesTheInstanceItsContentFrameOnce() {
        val m = MainLoopModel(ContentBoxGate())
        m.selectionFrame(inst); m.drain()
        m.formReady(inst)
        assertTrue(m.drain())
        assertEquals(2, m.runs)
        assertTrue(m.cache.containsKey(inst))
        val framesAfter = m.frames
        m.selectionFrame(inst); m.drain()
        assertEquals(2, m.runs)   // из кэша
        assertEquals(framesAfter + 1, m.frames)
        // следующие формы - снова один расчёт на каждую
        m.formReady(inst); m.formReady(inst)
        assertTrue(m.drain())
        assertEquals(3, m.runs)   // второй запрос, пока первый в очереди, не дублируется
    }

    @Test fun gateRunsOneComputationPerLayerAtATime() {
        val g = ContentBoxGate()
        assertTrue(g.begin(5))
        assertFalse(g.begin(5))
        assertTrue(g.begin(6))       // другой слой - независимо
        assertTrue(g.isBusy(5))
        assertTrue(g.end(5, cached = true))
        assertFalse(g.isParked(5))
        assertTrue(g.begin(5))
        assertFalse(g.end(5, cached = false))
        assertTrue(g.isParked(5))
        assertFalse(g.begin(5))      // ждёт новых данных
        g.forget(5)
        assertTrue(g.begin(5))
        g.clear()
        assertFalse(g.isBusy(5)); assertFalse(g.isBusy(6))
        assertTrue(g.begin(6))       // новый проект - всё заново
    }

    @Test fun gateDiscardsResultOfComputationThatRacedAnEdit() {
        // кэш границ как в MainViewModel: расчёт начался, слой правят (dropBox), расчёт дописывает старые границы
        val cache = HashMap<Int, String>()
        val g = ContentBoxGate()
        assertTrue(g.begin(7))
        cache.remove(7); g.forget(7)          // правка слоя посреди расчёта
        cache[7] = "old"                       // устаревший результат лёг в кэш
        val stale = g.takeStale(7)
        assertTrue(stale)
        if (stale) cache.remove(7)
        assertTrue("рамка перерисуется и запросит новый расчёт", g.end(7, cached = false || stale))
        assertFalse(g.isParked(7)); assertFalse(cache.containsKey(7))
        assertTrue(g.begin(7))                 // новый расчёт разрешён
        assertFalse(g.takeStale(7))            // без правки - не устарел
        assertTrue(g.end(7, cached = true))
        // правка без идущего расчёта ничего не помечает
        g.forget(8); assertTrue(g.begin(8)); assertFalse(g.takeStale(8))
        // смена проекта сбрасывает пометки
        g.forget(8); g.clear(); assertFalse(g.takeStale(8))
    }

    @Test fun layersAsFramesTakesSelectedPlainLayersBottomToTop() {
        // новый проект с PSD: 57 записей (папки, их концы, слои), выделены 3 слоя рта в папке головы
        val order = (0 until 57).toList()
        val groups = setOf(10, 20, 30, 40, 50, 56)
        val leaf = { id: Int -> id !in groups }
        assertEquals(listOf(21, 22, 25), SymbolMath.frameLeaves(setOf(25, 21, 22), order, leaf))
        // папка в выделении пропускается, кость и удалённый слой - тоже
        assertEquals(listOf(21, 25), SymbolMath.frameLeaves(setOf(25, 20, 21, 2_000_000_001, 999), order, leaf))
        assertEquals(emptyList<Int>(), SymbolMath.frameLeaves(emptySet(), order, leaf))
        assertEquals(emptyList<Int>(), SymbolMath.frameLeaves(setOf(1, 2), emptyList(), leaf))
        // повтор id в стопке не даёт повторного кадра
        assertEquals(listOf(3), SymbolMath.frameLeaves(setOf(3), listOf(3, 3, 3), leaf))
    }

    @Test fun mouthPlanFromRealLayerNamesHasOneFramePerLayerAndUniqueMarkers() {
        val ids = listOf(21, 22, 25)
        val plan = SymbolMath.framesPlan(ids, listOf("Рот A", "Рот A", "  "), frameName = { "Кадр $it" })
        assertEquals(3, plan.duration)
        assertEquals(listOf(Triple(21, 0, 1), Triple(22, 1, 2), Triple(25, 2, 3)), plan.clips)
        assertEquals(listOf("Рот A", "Рот A 2", "Кадр 3"), plan.markers.map { it.name })
        assertEquals(listOf(0, 1, 2), plan.markers.map { it.frame })
        // имён меньше, чем слоёв, и имя, уже совпадающее с номером повтора
        val odd = SymbolMath.framesPlan(listOf(1, 2, 3), listOf("O", "O 2"), frameName = { "O" })
        assertEquals(listOf("O", "O 2", "O 3"), odd.markers.map { it.name })
        assertEquals(3, odd.markers.map { it.name }.toSet().size)
    }

    @Test fun bigSelectionsStayFast() {
        // 2000 одинаково названных слоёв: план и порядок строятся мгновенно (ANR не из-за них)
        val n = 2000
        val t0 = System.nanoTime()
        val order = (0 until n).toList()
        val leaves = SymbolMath.frameLeaves(order.toSet(), order) { true }
        val plan = SymbolMath.framesPlan(leaves, List(n) { "Рот" })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(n, plan.duration)
        assertEquals(n, plan.markers.map { it.name }.toSet().size)
        assertTrue("$ms ms", ms < 3000)
    }

    @Test fun descendantsSurviveParentCycles() {
        // повреждённые данные (цикл родителей) не вешают поиск участников символа
        val parentOf = mapOf(1 to 2, 2 to 1, 3 to 1, 4 to 99)
        val d = SymbolMath.descendants(1, parentOf)
        assertEquals(setOf(1, 2, 3), d)
    }
}
