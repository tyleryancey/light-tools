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
