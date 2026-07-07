package dev.tyler.sudoku.ui.game

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.sudoku.data.Codecs
import dev.tyler.sudoku.data.DateKeys
import dev.tyler.sudoku.data.IndexEntry
import dev.tyler.sudoku.data.KeyValueStore
import dev.tyler.sudoku.data.ProgressDto
import dev.tyler.sudoku.data.Settings
import dev.tyler.sudoku.data.StoreKeys
import dev.tyler.sudoku.engine.SudokuEngine
import dev.tyler.sudoku.feedback.SolveFeedback
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InputMode { NORMAL, CANDIDATE }

/** Transient UI as synchronous state so onBackPressed() can answer "is something open?". */
sealed interface Overlay {
    data object Menu : Overlay
    data object HintPage : Overlay
    data object SettingsSheet : Overlay
    data object Help : Overlay
    data object Paused : Overlay
    data object ConfirmReset : Overlay
    data object ConfirmReveal : Overlay
    data class Win(val timeText: String, val subtitle: String) : Overlay
}

sealed interface GameResult {
    data object Closed : GameResult
    data object OpenArchive : GameResult
}

data class UndoFrame(val i: Int, val value: Int, val cand: Int, val err: Boolean, val locked: Boolean)

data class GameUiState(
    val dateKey: String = "",
    val difficulty: String = "medium",
    val solution: IntArray = IntArray(81),
    val values: IntArray = IntArray(81),
    val givenMask: BooleanArray = BooleanArray(81),
    val lockedMask: BooleanArray = BooleanArray(81),
    val candidates: IntArray = IntArray(81),   // pencil bitmasks (manual mode)
    val checkErr: BooleanArray = BooleanArray(81),
    val selected: Int = -1,
    val mode: InputMode = InputMode.NORMAL,
    val autoCandidate: Boolean = false,
    val undo: List<UndoFrame> = emptyList(),
    val solved: Boolean = false,
    val usedReveal: Boolean = false,
    val elapsedSec: Int = 0,
    val running: Boolean = false,
    val settings: Settings = Settings(),
    val generating: Boolean = true,
    val overlay: Overlay? = null,
    val toast: String? = null,
)

class GameViewModel(
    private val dateKey: String,
    private val difficulty: String,
    private val store: KeyValueStore,
    private val playChime: () -> Unit = SolveFeedback::playChime,
    private val now: () -> Long = System::currentTimeMillis,
    private val generationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : LightViewModel<GameResult>() {

    private val _ui = MutableStateFlow(GameUiState(dateKey = dateKey, difficulty = difficulty))
    val ui = _ui.asStateFlow()
    private val s get() = _ui.value

    // Timer accounting in ms; UI shows floor(seconds).
    private var baseMs = 0L
    private var startTs = 0L
    private var clueLayout: IntArray = IntArray(81)   // original clue grid, captured at open

    init { viewModelScope.launch { open() } }

    // ---------- open / generate ----------
    private suspend fun open() {
        val settings = Codecs.decodeSettings(store.get(StoreKeys.SETTINGS))
        val cacheKey = StoreKeys.puzzle(dateKey, difficulty)
        val p = Codecs.decodePuzzle(store.get(cacheKey)) ?: withContext(generationDispatcher) {
            SudokuEngine.generatePuzzle(dateKey, difficulty)
        }.also { store.set(cacheKey, Codecs.encodePuzzle(it)) }

        val given = BooleanArray(81) { p.puzzle[it] != 0 }
        clueLayout = p.puzzle.copyOf()
        val values = p.puzzle.copyOf()
        var cand = IntArray(81)
        var locked = BooleanArray(81)
        var auto = settings.autoStart
        var solved = false
        var usedReveal = false
        var elapsed = 0

        Codecs.decodeProgress(store.get(StoreKeys.progress(dateKey, difficulty)))?.let { o ->
            for (i in 0 until 81) if (!given[i]) values[i] = o.v.getOrElse(i) { 0 }
            cand = IntArray(81) { o.c.getOrElse(it) { 0 } }
            locked = BooleanArray(81) { o.l.getOrElse(it) { 0 } == 1 }
            auto = o.a == 1; solved = o.s == 1; usedReveal = o.r == 1; elapsed = o.t
        }

        baseMs = elapsed * 1000L
        startTs = 0L
        _ui.value = s.copy(
            generating = false, solution = p.solution, values = values, givenMask = given,
            lockedMask = locked, candidates = cand, checkErr = BooleanArray(81),
            selected = -1, mode = InputMode.NORMAL, autoCandidate = auto,
            undo = emptyList(), solved = solved, usedReveal = usedReveal,
            elapsedSec = elapsed, running = false, settings = settings,
        )
        if (!solved) startTimer()
    }

    // ---------- timer ----------
    private fun startTimer() {
        if (s.running || s.solved) return
        startTs = now()
        _ui.value = s.copy(running = true)
    }

    private fun stopTimer() {
        if (!s.running) return
        baseMs += now() - startTs
        _ui.value = s.copy(running = false, elapsedSec = (baseMs / 1000).toInt())
    }

    private fun elapsedSec(): Int {
        var ms = baseMs
        if (s.running) ms += now() - startTs
        return (ms / 1000).toInt()
    }

    /** Called by the UI's 250ms LaunchedEffect while running — no coroutine loop in the VM. */
    fun refreshElapsed() {
        if (s.running) _ui.value = s.copy(elapsedSec = elapsedSec())
    }

    // ---------- lifecycle ----------
    override fun onScreenShow(screen: SimpleLightScreen<GameResult>) { resumeFromShow() }
    override fun onScreenHide(screen: SimpleLightScreen<GameResult>) { pauseForBackground() }
    override fun onAppPause() { pauseForBackground() }

    fun resumeFromShow() {
        if (!s.generating && !s.solved && s.overlay == null) startTimer()
    }

    private fun pauseForBackground() {
        if (!s.running) return
        stopTimer()
        persistProgress()
    }

    /** Back closes overlays (hint page steps back to the menu) before popping the screen. */
    override fun onBackPressed(): Boolean {
        when (s.overlay) {
            null -> {
                stopTimer()
                persistProgress()
                return false
            }
            Overlay.HintPage -> _ui.value = s.copy(overlay = Overlay.Menu)
            else -> dismissOverlay()
        }
        return true
    }

    // ---------- overlays (openers for part B UI; dismiss shared) ----------
    fun dismissOverlay() {
        val wasPaused = s.overlay == Overlay.Paused
        _ui.value = s.copy(overlay = null)
        if (wasPaused && !s.solved) startTimer()
    }

    fun pauseTapped() {
        if (s.solved) return
        stopTimer()
        persistProgress()
        _ui.value = s.copy(overlay = Overlay.Paused)
    }

    // ---------- settings ----------
    fun toggleSetting(name: String) {
        val st = s.settings
        val next = when (name) {
            "rowcol" -> st.copy(rowcol = !st.rowcol)
            "box" -> st.copy(box = !st.box)
            "same" -> st.copy(same = !st.same)
            "conflicts" -> st.copy(conflicts = !st.conflicts)
            "checkOnEntry" -> st.copy(checkOnEntry = !st.checkOnEntry)
            "autoStart" -> st.copy(autoStart = !st.autoStart)
            "timer" -> st.copy(timer = !st.timer)
            "sound" -> st.copy(sound = !st.sound)
            "plain" -> st.copy(plain = !st.plain)
            else -> st
        }
        _ui.value = s.copy(settings = next)
        // retroactively flag existing entries when enabling check-on-entry (matches prototype)
        if (name == "checkOnEntry" && next.checkOnEntry && !next.plain) checkPuzzleSilent()
        viewModelScope.launch { store.set(StoreKeys.SETTINGS, Codecs.encodeSettings(next)) }
    }

    private fun checkPuzzleSilent() {
        val err = s.checkErr.copyOf()
        for (i in 0 until 81) {
            if (s.givenMask[i] || s.lockedMask[i] || s.values[i] == 0) continue
            err[i] = s.values[i] != s.solution[i]
        }
        _ui.value = s.copy(checkErr = err)
    }

    // ---------- persistence ----------
    private var persistJob: Job? = null

    fun persistProgress() {
        if (s.generating) return
        val p = ProgressDto(
            v = s.values.toList(), c = s.candidates.toList(),
            l = s.lockedMask.map { if (it) 1 else 0 },
            a = if (s.autoCandidate) 1 else 0, t = elapsedSec(),
            s = if (s.solved) 1 else 0, r = if (s.usedReveal) 1 else 0,
        )
        viewModelScope.launch {
            store.set(StoreKeys.progress(dateKey, difficulty), Codecs.encodeProgress(p))
            val cur = Codecs.decodeIndex(store.get(StoreKeys.INDEX))["$dateKey:$difficulty"]
            if (cur?.status != "done") {
                writeIndex(
                    when { s.solved -> "done"; isStarted() -> "progress"; else -> "new" },
                    if (s.solved) elapsedSec() else null,
                )
            }
        }
    }

    internal fun persistProgressSoon() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch { delay(400); persistProgress() }
    }

    private suspend fun writeIndex(status: String, time: Int?) {
        val ix = Codecs.decodeIndex(store.get(StoreKeys.INDEX)).toMutableMap()
        ix["$dateKey:$difficulty"] = IndexEntry(status, time)
        store.set(StoreKeys.INDEX, Codecs.encodeIndex(ix))
    }

    private fun isStarted(): Boolean {
        for (i in 0 until 81) {
            if (s.givenMask[i]) continue
            if (s.values[i] != 0 || s.candidates[i] != 0 || s.lockedMask[i]) return true
        }
        return false
    }

    // ---------- selection / input ----------
    fun select(i: Int) { _ui.value = s.copy(selected = i, elapsedSec = elapsedSec()) }

    fun setMode(m: InputMode) { if (!s.autoCandidate) _ui.value = s.copy(mode = m) }

    fun toggleAutoCandidate() {
        val on = !s.autoCandidate
        _ui.value = s.copy(autoCandidate = on, mode = if (on) InputMode.NORMAL else s.mode)
        persistProgressSoon()
    }

    private fun canEdit(i: Int) = i in 0..80 && !s.givenMask[i] && !s.lockedMask[i] && !s.solved

    private fun pushUndo(i: Int): List<UndoFrame> =
        (s.undo + UndoFrame(i, s.values[i], s.candidates[i], s.checkErr[i], s.lockedMask[i])).takeLast(400)

    fun input(d: Int) {
        val i = s.selected
        if (i < 0 || s.solved) return
        if (s.mode == InputMode.CANDIDATE && !s.autoCandidate) {
            if (!canEdit(i) || s.values[i] != 0) return
            val cand = s.candidates.copyOf()
            cand[i] = cand[i] xor (1 shl (d - 1))
            _ui.value = s.copy(candidates = cand, undo = pushUndo(i))
            persistProgressSoon()
            return
        }
        if (!canEdit(i)) return
        val undo = pushUndo(i)
        val values = s.values.copyOf(); val cand = s.candidates.copyOf(); val err = s.checkErr.copyOf()
        values[i] = d; cand[i] = 0; err[i] = false
        if (s.settings.checkOnEntry && !s.settings.plain) err[i] = d != s.solution[i]
        // courtesy: clear this digit from peers' manual pencil marks
        for (j in peersOf(i)) if (values[j] == 0 && (cand[j] and (1 shl (d - 1))) != 0) {
            cand[j] = cand[j] and (1 shl (d - 1)).inv()
        }
        _ui.value = s.copy(values = values, candidates = cand, checkErr = err, undo = undo)
        persistProgressSoon()
        checkWin()
    }

    fun erase() {
        val i = s.selected
        if (!canEdit(i)) return
        if (s.values[i] == 0 && s.candidates[i] == 0) return
        val undo = pushUndo(i)
        val values = s.values.copyOf(); val cand = s.candidates.copyOf(); val err = s.checkErr.copyOf()
        values[i] = 0; cand[i] = 0; err[i] = false
        _ui.value = s.copy(values = values, candidates = cand, checkErr = err, undo = undo)
        persistProgressSoon()
    }

    fun undo() {
        if (s.solved || s.undo.isEmpty()) return
        val u = s.undo.last()
        val values = s.values.copyOf(); val cand = s.candidates.copyOf()
        val err = s.checkErr.copyOf(); val locked = s.lockedMask.copyOf()
        values[u.i] = u.value; cand[u.i] = u.cand; err[u.i] = u.err; locked[u.i] = u.locked
        _ui.value = s.copy(
            values = values, candidates = cand, checkErr = err, lockedMask = locked,
            selected = u.i, undo = s.undo.dropLast(1),
        )
        persistProgressSoon()
    }

    // ---------- overlay openers ----------
    fun showMenu() { _ui.value = s.copy(overlay = Overlay.Menu) }
    fun showHintPage() { _ui.value = s.copy(overlay = Overlay.HintPage) }
    fun showSettings() { _ui.value = s.copy(overlay = Overlay.SettingsSheet) }
    fun showHelp() { _ui.value = s.copy(overlay = Overlay.Help) }
    fun requestRevealPuzzle() { _ui.value = s.copy(overlay = Overlay.ConfirmReveal) }
    fun requestReset() { _ui.value = s.copy(overlay = Overlay.ConfirmReset) }

    // ---------- hints ----------
    fun pointHint() {
        dismissOverlay()
        if (s.solved) { toast("Puzzle is finished"); return }
        if (s.values.all { it != 0 }) { toast("Board is already full"); return }
        val g = SudokuEngine.logicalSolve(s.values, 7)
        val target = g.firstStep?.index ?: s.values.indexOfFirst { it == 0 }
        if (target < 0) { toast("Nothing to point to"); return }
        _ui.value = s.copy(selected = target)
        toast(if (g.firstStep != null) "This square is solvable next" else "Try this square")
    }

    fun fillHint() {
        dismissOverlay()
        if (s.solved) { toast("Puzzle is finished"); return }
        val g = SudokuEngine.logicalSolve(s.values, 7)
        val step = g.firstStep
        val i = step?.index
            ?: (if (s.selected >= 0 && s.values[s.selected] == 0) s.selected else s.values.indexOfFirst { it == 0 })
        if (i < 0) { toast("Board is already full"); return }
        val digit = step?.digit ?: s.solution[i]
        val undo = pushUndo(i)
        val values = s.values.copyOf(); val cand = s.candidates.copyOf()
        val err = s.checkErr.copyOf(); val locked = s.lockedMask.copyOf()
        values[i] = digit; cand[i] = 0; err[i] = false; locked[i] = true
        _ui.value = s.copy(
            values = values, candidates = cand, checkErr = err, lockedMask = locked,
            selected = i, undo = undo,
        )
        persistProgressSoon()
        checkWin()
        if (!s.solved) toast("Filled one square for you")
    }

    // ---------- checks ----------
    fun checkCell() {
        dismissOverlay()
        val i = s.selected
        if (i < 0 || s.givenMask[i]) { toast("Pick a square to check"); return }
        if (s.values[i] == 0) { toast("That square is empty"); return }
        val ok = s.values[i] == s.solution[i]
        val err = s.checkErr.copyOf(); err[i] = !ok
        _ui.value = s.copy(checkErr = err)
        persistProgressSoon()
        toast(if (ok) "Looks right" else "That number is off")
    }

    fun checkPuzzle() {
        dismissOverlay()
        val err = s.checkErr.copyOf(); var wrong = 0; var filled = 0
        for (i in 0 until 81) {
            if (s.givenMask[i] || s.lockedMask[i]) continue
            if (s.values[i] == 0) { err[i] = false; continue }
            filled++
            val bad = s.values[i] != s.solution[i]
            err[i] = bad
            if (bad) wrong++
        }
        _ui.value = s.copy(checkErr = err)
        persistProgressSoon()
        toast(when {
            filled == 0 -> "Nothing to check yet"
            wrong == 0 -> "No mistakes so far"
            wrong == 1 -> "1 number is off"
            else -> "$wrong numbers are off"
        })
    }

    // ---------- reveals / reset ----------
    fun revealCell() {
        dismissOverlay()
        val i = s.selected
        if (i < 0 || s.givenMask[i]) { toast("Pick a square to reveal"); return }
        if (s.values[i] == s.solution[i] && s.values[i] != 0) { toast("Already correct"); return }
        val undo = pushUndo(i)
        val values = s.values.copyOf(); val cand = s.candidates.copyOf()
        val err = s.checkErr.copyOf(); val locked = s.lockedMask.copyOf()
        values[i] = s.solution[i]; cand[i] = 0; err[i] = false; locked[i] = true
        _ui.value = s.copy(
            values = values, candidates = cand, checkErr = err, lockedMask = locked, undo = undo,
        )
        persistProgressSoon()
        checkWin()
    }

    fun confirmRevealPuzzle() {
        val values = s.values.copyOf(); val locked = s.lockedMask.copyOf()
        for (i in 0 until 81) if (!s.givenMask[i]) { values[i] = s.solution[i]; locked[i] = true }
        stopTimer()
        _ui.value = s.copy(
            values = values, candidates = IntArray(81), lockedMask = locked,
            checkErr = BooleanArray(81), usedReveal = true, solved = true,
            undo = emptyList(), selected = -1, overlay = null,
        )
        viewModelScope.launch { writeIndex("done", null) }
        persistProgress()
        toast("Puzzle revealed")
    }

    fun confirmReset() {
        baseMs = 0; startTs = 0
        _ui.value = s.copy(
            values = clueLayout.copyOf(), candidates = IntArray(81),
            lockedMask = BooleanArray(81), checkErr = BooleanArray(81), undo = emptyList(),
            solved = false, usedReveal = false, selected = -1, elapsedSec = 0,
            running = false, overlay = null,
        )
        viewModelScope.launch { writeIndex("new", null) }
        persistProgress()
        startTimer()
    }

    // ---------- win ----------
    private fun checkWin() {
        if (s.solved || s.usedReveal) return
        if (s.values.any { it == 0 }) return
        if (!s.values.indices.all { s.values[it] == s.solution[it] }) return
        stopTimer()
        val sec = elapsedSec()
        val subtitle = "${difficulty.replaceFirstChar { it.uppercase() }} · ${DateKeys.prettyShort(dateKey)}"
        _ui.value = s.copy(
            solved = true, elapsedSec = sec,
            overlay = Overlay.Win(fmtTime(sec), subtitle),
        )
        if (s.settings.sound) runCatching { playChime() }
        viewModelScope.launch { writeIndex("done", sec) }
        persistProgress()
    }

    // ---------- conflicts (computed for rendering) ----------
    fun conflicts(): BooleanArray {
        val bad = BooleanArray(81)
        fun scan(idx: IntArray) {
            val seen = HashMap<Int, MutableList<Int>>()
            for (i in idx) {
                val v = s.values[i]; if (v == 0) continue
                seen.getOrPut(v) { mutableListOf() }.add(i)
            }
            for ((_, list) in seen) if (list.size > 1) for (i in list) bad[i] = true
        }
        for (r in 0 until 9) scan(IntArray(9) { r * 9 + it })
        for (c in 0 until 9) scan(IntArray(9) { it * 9 + c })
        for (b in 0 until 9) scan(IntArray(9) { (b / 3 * 3 + it / 3) * 9 + (b % 3 * 3 + it % 3) })
        return bad
    }

    private fun peersOf(i: Int): List<Int> {
        val r = i / 9; val c = i % 9; val b = SudokuEngine.boxOf(i)
        val set = LinkedHashSet<Int>()
        for (k in 0 until 9) { set.add(r * 9 + k); set.add(k * 9 + c) }
        for (k in 0 until 9) set.add((b / 3 * 3 + k / 3) * 9 + (b % 3 * 3 + k % 3))
        set.remove(i)
        return set.toList()
    }

    // ---------- toast ----------
    private var toastJob: Job? = null
    internal fun toast(t: String) {
        _ui.value = s.copy(toast = t)
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(1700); _ui.value = s.copy(toast = null) }
    }

    // ---------- helpers ----------
    fun fmtTime(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val ss = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }
}
