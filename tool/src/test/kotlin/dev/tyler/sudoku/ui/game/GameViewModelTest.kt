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

    @Test fun timerResumesAfterBackgroundWithSheetOpen() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.showSettings()
        assertTrue(vm.ui.value.running, "an open sheet alone does not stop the timer")
        vm.onAppPause(); advanceUntilIdle()
        assertFalse(vm.ui.value.running)
        vm.resumeFromShow()
        assertTrue(vm.ui.value.running, "timer restarts on return even with a sheet open")
        // explicit pause still wins across a background round-trip
        vm.dismissOverlay()
        vm.pauseTapped(); advanceUntilIdle()
        vm.onAppPause(); advanceUntilIdle()
        vm.resumeFromShow()
        assertFalse(vm.ui.value.running, "Paused overlay keeps the timer stopped")
    }

    @Test fun solvingLegitimatelyWinsChimesAndMarksIndexDone() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        // enable sound so the chime fires
        vm.toggleSetting("sound"); advanceUntilIdle()
        clock = 90_000L
        for (i in 0 until 81) if (!ui.givenMask[i]) {
            vm.select(i); vm.input(ui.solution[i])
        }
        advanceUntilIdle()
        val end = vm.ui.value
        assertTrue(end.solved)
        assertTrue(end.overlay is Overlay.Win)
        assertEquals("1:30", (end.overlay as Overlay.Win).timeText)
        assertEquals(1, chimes)
        assertFalse(end.running)
        val ix = Codecs.decodeIndex(store.map[StoreKeys.INDEX])
        assertEquals("done", ix["2026-06-16:easy"]!!.status)
        assertEquals(90, ix["2026-06-16:easy"]!!.time)
    }

    @Test fun revealPuzzleBlocksWinCelebration() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.toggleSetting("sound"); advanceUntilIdle()
        vm.requestRevealPuzzle()
        assertEquals(Overlay.ConfirmReveal, vm.ui.value.overlay)
        vm.confirmRevealPuzzle()
        // assert the toast BEFORE advanceUntilIdle — the 1700ms auto-clear runs on virtual time
        assertEquals("Puzzle revealed", vm.ui.value.toast)
        advanceUntilIdle()
        val end = vm.ui.value
        assertTrue(end.solved)
        assertTrue(end.usedReveal)
        assertEquals(0, chimes, "reveal must not chime")
        assertTrue(end.overlay !is Overlay.Win)
        // index done with no time
        val ix = Codecs.decodeIndex(store.map[StoreKeys.INDEX])
        assertEquals("done", ix["2026-06-16:easy"]!!.status)
        assertEquals(null, ix["2026-06-16:easy"]!!.time)
    }

    @Test fun implicitPersistAfterRevealNeverStampsASolveTime() = runTest {
        val vm = vm(); advanceUntilIdle()
        clock = 60_000L
        vm.requestRevealPuzzle()
        vm.confirmRevealPuzzle(); advanceUntilIdle()
        // a later background/pop persist must not rewrite the revealed entry with a time
        vm.persistProgress(); advanceUntilIdle()
        val e = Codecs.decodeIndex(store.map[StoreKeys.INDEX])["2026-06-16:easy"]!!
        assertEquals("done", e.status)
        assertEquals(null, e.time, "revealed puzzle must never gain a solve time")
    }

    @Test fun candidateModeTogglesPencilBit() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = (0 until 81).first { !vm.ui.value.givenMask[it] }
        vm.setMode(InputMode.CANDIDATE)
        vm.select(i)
        vm.input(3); advanceUntilIdle()
        assertEquals(1 shl 2, vm.ui.value.candidates[i])
        vm.input(3); advanceUntilIdle()
        assertEquals(0, vm.ui.value.candidates[i])
        assertEquals(0, vm.ui.value.values[i], "candidate mode never places values")
    }

    @Test fun undoRestoresPriorCellState() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = (0 until 81).first { !vm.ui.value.givenMask[it] }
        vm.select(i); vm.input(7); advanceUntilIdle()
        assertEquals(7, vm.ui.value.values[i])
        vm.undo(); advanceUntilIdle()
        assertEquals(0, vm.ui.value.values[i])
        assertEquals(i, vm.ui.value.selected, "undo re-selects the affected cell")
    }

    @Test fun inputClearsDigitFromPeerPencilMarks() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        // find two empty cells in the same row
        val row = (0 until 9).first { r -> (0 until 9).count { !ui.givenMask[r * 9 + it] } >= 2 }
        val cells = (0 until 9).map { row * 9 + it }.filter { !ui.givenMask[it] }
        val (a, b) = cells[0] to cells[1]
        vm.setMode(InputMode.CANDIDATE); vm.select(b); vm.input(4)
        vm.setMode(InputMode.NORMAL); vm.select(a); vm.input(4)
        advanceUntilIdle()
        assertEquals(0, vm.ui.value.candidates[b] and (1 shl 3), "peer pencil 4 cleared")
    }

    @Test fun fillHintLocksCellAndToasts() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.fillHint()
        // toast asserted before advanceUntilIdle (virtual-time auto-clear)
        assertEquals("Filled one square for you", vm.ui.value.toast)
        advanceUntilIdle()
        val ui = vm.ui.value
        val locked = (0 until 81).filter { ui.lockedMask[it] }
        assertEquals(1, locked.size)
        assertEquals(ui.solution[locked[0]], ui.values[locked[0]])
    }

    @Test fun checkPuzzleCountsWrongEntries() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        val i = (0 until 81).first { !ui.givenMask[it] }
        val wrong = if (ui.solution[i] == 1) 2 else 1
        vm.select(i); vm.input(wrong)
        vm.checkPuzzle()
        // toast asserted before advanceUntilIdle (virtual-time auto-clear)
        assertEquals("1 number is off", vm.ui.value.toast)
        advanceUntilIdle()
        assertTrue(vm.ui.value.checkErr[i])
    }

    @Test fun conflictsFlagsDuplicatesInRow() = runTest {
        val vm = vm(); advanceUntilIdle()
        val ui = vm.ui.value
        val row = (0 until 9).first { r -> (0 until 9).count { !ui.givenMask[r * 9 + it] } >= 2 }
        val cells = (0 until 9).map { row * 9 + it }.filter { !ui.givenMask[it] }
        vm.select(cells[0]); vm.input(9)
        vm.select(cells[1]); vm.input(9)
        val bad = vm.conflicts()
        assertTrue(bad[cells[0]] && bad[cells[1]])
    }

    @Test fun resetRestoresCluesAndZeroesTimer() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = (0 until 81).first { !vm.ui.value.givenMask[it] }
        vm.select(i); vm.input(5)
        clock = 30_000L
        vm.requestReset()
        assertEquals(Overlay.ConfirmReset, vm.ui.value.overlay)
        vm.confirmReset(); advanceUntilIdle()
        val end = vm.ui.value
        assertEquals(0, end.values[i])
        assertEquals(0, end.elapsedSec)
        assertTrue(end.running, "reset restarts the timer")
        assertFalse(end.solved)
    }

    @Test fun overlayBackNavigation() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.showMenu()
        vm.showHintPage()
        assertEquals(Overlay.HintPage, vm.ui.value.overlay)
        assertTrue(vm.onBackPressed(), "hint page consumes back")
        assertEquals(Overlay.Menu, vm.ui.value.overlay, "…stepping back to the menu")
        assertTrue(vm.onBackPressed(), "menu consumes back")
        assertEquals(null, vm.ui.value.overlay)
        assertFalse(vm.onBackPressed(), "no overlay: back pops the screen")
    }

    // ---------- auto candidate mode: two independent candidate layers ----------

    private fun firstEmpty(vm: GameViewModel): Int =
        (0 until 81).first { !vm.ui.value.givenMask[it] && vm.ui.value.values[it] == 0 }

    private fun autoMask(vm: GameViewModel, i: Int): Int {
        var m = 0
        for (d in SudokuEngine.autoCandidates(vm.ui.value.values, i)) m = m or (1 shl (d - 1))
        return m
    }

    @Test fun modeSwitchStaysLiveWhenAutoCandidateOn() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.toggleAutoCandidate()
        assertTrue(vm.ui.value.autoCandidate)
        vm.setMode(InputMode.CANDIDATE)
        assertEquals(InputMode.CANDIDATE, vm.ui.value.mode, "switcher works with auto on")
    }

    @Test fun togglingAutoCandidateKeepsCurrentMode() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.setMode(InputMode.CANDIDATE)
        vm.toggleAutoCandidate()
        assertEquals(InputMode.CANDIDATE, vm.ui.value.mode, "toggling auto never forces NORMAL")
    }

    @Test fun autoCandidateModeRevealsAllValidCandidates() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = firstEmpty(vm)
        vm.toggleAutoCandidate()
        val expected = autoMask(vm, i)
        assertTrue(expected != 0, "an empty cell has valid candidates")
        assertEquals(expected, vm.pencilMask(i), "auto on reveals the full valid candidate set")
    }

    @Test fun candidateEditWhileAutoOnUsesAutoLayerNotManual() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = firstEmpty(vm)
        vm.toggleAutoCandidate()
        val shown = vm.pencilMask(i)
        val d = (1..9).first { shown and (1 shl (it - 1)) != 0 }
        vm.setMode(InputMode.CANDIDATE); vm.select(i); vm.input(d); advanceUntilIdle()
        assertEquals(0, vm.pencilMask(i) and (1 shl (d - 1)), "deleting an auto candidate hides it")
        assertTrue(vm.ui.value.autoRemoved[i] and (1 shl (d - 1)) != 0, "diff recorded in auto layer")
        assertEquals(0, vm.ui.value.candidates[i], "manual (auto-off) layer left untouched")
    }

    @Test fun manualAndAutoCandidateLayersDoNotBleed() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = firstEmpty(vm)
        // auto OFF: place a manual pencil mark
        vm.setMode(InputMode.CANDIDATE); vm.select(i); vm.input(7); advanceUntilIdle()
        assertEquals(1 shl 6, vm.pencilMask(i), "auto off shows only manual marks")
        // auto ON: manual mark hidden, full auto set shown instead
        vm.toggleAutoCandidate()
        assertEquals(autoMask(vm, i), vm.pencilMask(i), "auto on shows the auto set, not manual marks")
        // auto OFF again: manual mark returns intact
        vm.toggleAutoCandidate()
        assertEquals(1 shl 6, vm.pencilMask(i), "manual mark preserved across toggles")
    }

    @Test fun undoRestoresAutoCandidateLayerEdit() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = firstEmpty(vm)
        vm.toggleAutoCandidate()
        val before = vm.pencilMask(i)
        val d = (1..9).first { before and (1 shl (it - 1)) != 0 }
        vm.setMode(InputMode.CANDIDATE); vm.select(i); vm.input(d); advanceUntilIdle()
        assertTrue(vm.pencilMask(i) != before, "edit changed the shown mask")
        vm.undo(); advanceUntilIdle()
        assertEquals(before, vm.pencilMask(i), "undo restores the auto-layer edit")
    }

    @Test fun autoCandidateLayerPersistsAcrossReopen() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = firstEmpty(vm)
        vm.toggleAutoCandidate()
        val shown = vm.pencilMask(i)
        val d = (1..9).first { shown and (1 shl (it - 1)) != 0 }
        vm.setMode(InputMode.CANDIDATE); vm.select(i); vm.input(d)
        vm.persistProgress(); advanceUntilIdle()
        val removed = vm.ui.value.autoRemoved[i]
        assertTrue(removed != 0)
        val vm2 = vm(); advanceUntilIdle()
        assertTrue(vm2.ui.value.autoCandidate, "auto flag restored")
        assertEquals(removed, vm2.ui.value.autoRemoved[i], "auto-layer diff restored from storage")
    }

    @Test fun reshownNaturalCandidateStillTracksLiveAutoSet() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = firstEmpty(vm)
        vm.toggleAutoCandidate()
        val d = (1..9).first { autoMask(vm, i) and (1 shl (it - 1)) != 0 }
        // toggle a natural candidate off then on again — it must not get "pinned"
        vm.setMode(InputMode.CANDIDATE); vm.select(i)
        vm.input(d); advanceUntilIdle()   // hide d
        vm.input(d); advanceUntilIdle()   // show d again
        assertTrue(vm.pencilMask(i) and (1 shl (d - 1)) != 0, "d is visible again")
        // make d invalid for cell i by placing it in one of i's peers
        val r = i / 9; val c = i % 9; val b = SudokuEngine.boxOf(i)
        val peer = (0 until 81).first { j ->
            j != i && (j / 9 == r || j % 9 == c || SudokuEngine.boxOf(j) == b) &&
                !vm.ui.value.givenMask[j] && vm.ui.value.values[j] == 0
        }
        vm.setMode(InputMode.NORMAL); vm.select(peer); vm.input(d); advanceUntilIdle()
        assertEquals(
            0, vm.pencilMask(i) and (1 shl (d - 1)),
            "a re-shown natural candidate must still disappear once it becomes invalid",
        )
    }

    @Test fun addingPeerBlockedCandidateWhileAutoOnPinsThenClears() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.toggleAutoCandidate()
        // find an empty cell + a digit that is NOT a valid auto candidate (blocked by a peer)
        val (i, d) = run {
            for (cell in 0 until 81) {
                if (vm.ui.value.givenMask[cell] || vm.ui.value.values[cell] != 0) continue
                val mask = autoMask(vm, cell)
                for (dd in 1..9) if (mask and (1 shl (dd - 1)) == 0) return@run cell to dd
            }
            error("expected an empty cell with a peer-blocked digit")
        }
        val bit = 1 shl (d - 1)
        vm.setMode(InputMode.CANDIDATE); vm.select(i)
        vm.input(d); advanceUntilIdle()
        assertTrue(vm.ui.value.autoAdded[i] and bit != 0, "a non-auto digit is recorded as an addition")
        assertTrue(vm.pencilMask(i) and bit != 0, "and is shown via the (autoSet or autoAdded) term")
        // toggling it back off clears the addition and does NOT record a phantom removal
        vm.input(d); advanceUntilIdle()
        assertEquals(0, vm.ui.value.autoAdded[i] and bit, "addition cleared")
        assertEquals(0, vm.ui.value.autoRemoved[i] and bit, "never recorded as a removal (was not in the auto set)")
        assertEquals(0, vm.pencilMask(i) and bit, "and no longer shown")
    }

    @Test fun placingValueClearsBothCandidateLayers() = runTest {
        val vm = vm(); advanceUntilIdle()
        val i = firstEmpty(vm)
        // seed a manual mark (auto off), then an auto-layer diff (auto on)
        vm.setMode(InputMode.CANDIDATE); vm.select(i); vm.input(7); advanceUntilIdle()
        vm.toggleAutoCandidate()
        val d = (1..9).first { vm.pencilMask(i) and (1 shl (it - 1)) != 0 }
        vm.input(d); advanceUntilIdle()
        // now place a value in NORMAL mode
        vm.setMode(InputMode.NORMAL); vm.input(vm.ui.value.solution[i]); advanceUntilIdle()
        assertEquals(0, vm.ui.value.candidates[i], "manual layer cleared on value entry")
        assertEquals(0, vm.ui.value.autoAdded[i], "auto-added diff cleared on value entry")
        assertEquals(0, vm.ui.value.autoRemoved[i], "auto-removed diff cleared on value entry")
    }
}
