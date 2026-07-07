package dev.tyler.sudoku.ui.game

import dev.tyler.sudoku.data.Codecs
import dev.tyler.sudoku.data.InMemoryKeyValueStore
import dev.tyler.sudoku.data.ProgressDto
import dev.tyler.sudoku.data.StoreKeys
import dev.tyler.sudoku.engine.SudokuEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: InMemoryKeyValueStore
    private var clock = 0L
    private var chimes = 0

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = InMemoryKeyValueStore()
        clock = 0L
        chimes = 0
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(date: String = "2026-06-16", diff: String = "easy") =
        GameViewModel(date, diff, store, playChime = { chimes++ }, now = { clock }, generationDispatcher = dispatcher)

    @Test fun openGeneratesCachesAndStartsTimer() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        assertFalse(ui.generating)
        assertTrue(ui.running, "timer starts on open of unsolved puzzle")
        assertEquals(40, ui.givenMask.count { it }, "easy targets 40 clues")
        assertNotNull(store.map[StoreKeys.puzzle("2026-06-16", "easy")], "puzzle cached")
        // deterministic: cache round-trips to the same board
        val p = SudokuEngine.generatePuzzle("2026-06-16", "easy")
        assertTrue(p.puzzle.withIndex().all { (i, v) -> (v != 0) == ui.givenMask[i] })
    }

    @Test fun openRestoresProgress() = runTest {
        val p = SudokuEngine.generatePuzzle("2026-06-16", "easy")
        val editable = (0 until 81).first { p.puzzle[it] == 0 }
        val values = IntArray(81)
        values[editable] = 5
        store.map[StoreKeys.progress("2026-06-16", "easy")] = Codecs.encodeProgress(
            ProgressDto(
                v = values.toList(), c = List(81) { 0 }, l = List(81) { 0 },
                a = 0, t = 77, s = 0, r = 0,
            )
        )
        val vm = vm(); advanceUntilIdle()
        assertEquals(5, vm.ui.value.values[editable])
        assertEquals(77, vm.ui.value.elapsedSec)
        // givens must come from the puzzle, not the stored v-array
        assertTrue(vm.ui.value.givenMask.withIndex().all { (i, g) -> !g || vm.ui.value.values[i] == p.puzzle[i] })
    }

    @Test fun corruptProgressIsIgnored() = runTest {
        store.map[StoreKeys.progress("2026-06-16", "easy")] = "not json"
        val vm = vm(); advanceUntilIdle()
        assertFalse(vm.ui.value.generating)
        assertEquals(0, vm.ui.value.elapsedSec)
    }

    @Test fun timerAccountsWithVirtualClockAndPersistsOnPause() = runTest {
        val vm = vm(); advanceUntilIdle()
        clock = 12_000L
        vm.refreshElapsed()
        assertEquals(12, vm.ui.value.elapsedSec)
        vm.onAppPause(); advanceUntilIdle()
        assertFalse(vm.ui.value.running)
        val prog = Codecs.decodeProgress(store.map[StoreKeys.progress("2026-06-16", "easy")])
        assertEquals(12, prog!!.t)
        // resume continues from the base, not from zero
        clock = 20_000L
        vm.resumeFromShow()
        assertTrue(vm.ui.value.running)
        clock = 25_000L
        vm.refreshElapsed()
        assertEquals(17, vm.ui.value.elapsedSec, "12s banked + 5s since resume")
    }

    @Test fun toggleSettingPersistsAndRetroFlagsCheckOnEntry() = runTest {
        val p = SudokuEngine.generatePuzzle("2026-06-16", "easy")
        val editable = (0 until 81).first { p.puzzle[it] == 0 }
        val wrong = if (p.solution[editable] == 1) 2 else 1
        val values = IntArray(81); values[editable] = wrong
        store.map[StoreKeys.progress("2026-06-16", "easy")] = Codecs.encodeProgress(
            ProgressDto(values.toList(), List(81) { 0 }, List(81) { 0 }, 0, 0, 0, 0)
        )
        val vm = vm(); advanceUntilIdle()
        vm.toggleSetting("checkOnEntry"); advanceUntilIdle()
        assertTrue(vm.ui.value.settings.checkOnEntry)
        assertTrue(vm.ui.value.checkErr[editable], "existing wrong entry flagged retroactively")
        assertTrue(Codecs.decodeSettings(store.map[StoreKeys.SETTINGS]).checkOnEntry, "persisted")
    }

    @Test fun backPressWithoutOverlayPopsAfterPersist() = runTest {
        val vm = vm(); advanceUntilIdle()
        assertFalse(vm.onBackPressed()); advanceUntilIdle()
        assertNotNull(store.map[StoreKeys.progress("2026-06-16", "easy")])
    }

    @Test fun pauseOverlayStopsTimerAndDismissResumes() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.pauseTapped(); advanceUntilIdle()
        assertEquals(Overlay.Paused, vm.ui.value.overlay)
        assertFalse(vm.ui.value.running)
        vm.dismissOverlay(); advanceUntilIdle()
        assertEquals(null, vm.ui.value.overlay)
        assertTrue(vm.ui.value.running)
    }
}
