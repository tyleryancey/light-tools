package dev.tyler.sudoku.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import dev.tyler.sudoku.data.DataStoreKeyValueStore
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuSurface
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

class GameScreen(
    sealedActivity: SealedLightActivity,
    private val dateKey: String,
    private val difficulty: String,
) : LightScreen<GameResult, GameViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameViewModel>
        get() = GameViewModel::class.java

    override fun createViewModel() = GameViewModel(
        dateKey, difficulty, DataStoreKeyValueStore(lightContext.dataStore)
    )

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()
        val haptic = LocalHapticFeedback.current

        // UI-side timer ticker: the VM stays free of infinite coroutines.
        LaunchedEffect(ui.running) {
            if (ui.running) while (true) { delay(250); viewModel.refreshElapsed() }
        }
        // Solve haptic: fires once when the win overlay appears (never on reveal).
        val won = ui.overlay is Overlay.Win
        LaunchedEffect(won) {
            if (won && ui.settings.sound) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        SudokuSurface {
            val pal = LocalSudokuPalette.current
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                TopBar(ui)
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.hair))
                // The board is ALWAYS full size — it never trades height for the keypad. The keypad
                // floats OVER the board as a z-stacked overlay, so entering a number no longer shrinks
                // the grid. The Box(size = boardSize) is a board-relative anchor for that overlay.
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val boardSize = minOf(maxWidth, maxHeight)
                    Box(Modifier.size(boardSize)) {
                        Board(viewModel, ui, boardSize, Modifier.fillMaxSize())
                        if (viewModel.shouldShowKeypad()) {
                            // ui.keypadDockNow is a State read, so the panel repositions when the margin
                            // changes (a flick, or an auto-avoid hop on selection). Placement is 4-way.
                            val align = when (ui.keypadDockNow) {
                                KeypadDock.TOP -> Alignment.TopCenter
                                KeypadDock.BOTTOM -> Alignment.BottomCenter
                                // Absolute (not Start/End) so a physical leftward flick always docks
                                // physically left, matching flickToMargin's physical dx (RTL-proof).
                                KeypadDock.LEFT -> AbsoluteAlignment.CenterLeft
                                KeypadDock.RIGHT -> AbsoluteAlignment.CenterRight
                            }
                            val horizontal = ui.keypadDockNow == KeypadDock.TOP ||
                                ui.keypadDockNow == KeypadDock.BOTTOM
                            FloatingKeypad(ui, horizontal, boardSize, Modifier.align(align))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // transient chrome
            ui.toast?.let { ToastPill(it) }
            if (ui.generating) GeneratingOverlay()
            GameOverlays(viewModel, ui) {
                // "Past puzzles" from the win modal: close the (already dismissed) overlay
                // is handled by the VM; pop with a result Home reacts to.
                goBack(GameResult.OpenArchive)
            }
        }
    }

    // Top bar carries the difficulty label and (when enabled) the timer + pause, so no
    // separate status row is needed — that reclaims vertical space for the board.
    @Composable
    private fun TopBar(ui: GameUiState) {
        val pal = LocalSudokuPalette.current
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconGlyph("‹", "Back") { goBack(GameResult.Closed) }
            Spacer(Modifier.width(2.dp))
            Text(
                ui.difficulty.replaceFirstChar { it.uppercase() },
                color = pal.txtDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (ui.settings.timer) {
                Text(
                    viewModel.fmtTime(ui.elapsedSec),
                    color = pal.txt, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(4.dp))
                IconGlyph("❚❚", "Pause") { viewModel.pauseTapped() }
            }
            IconGlyph("?", "How to play") { viewModel.showHelp() }
            IconGlyph("⚙", "Settings") { viewModel.showSettings() }
            IconGlyph("⋯", "More") { viewModel.showMenu() }
        }
    }

    @Composable
    private fun IconGlyph(glyph: String, label: String, onClick: () -> Unit) {
        val pal = LocalSudokuPalette.current
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = pal.txt, fontSize = 22.sp)
        }
    }

    // Floating number keypad: overlays the board (never steals its height) at one of four margins
    // (ui.keypadDockNow). Covers at most 3 board cells deep so most of the grid stays visible; it's a
    // wide card (control row + 2 digit rows) when docked TOP/BOTTOM and a narrow strip (3×3 digits +
    // control stack) when docked LEFT/RIGHT. Flick it to another margin (see flickToMargin). An opaque
    // framed card inset off the board edge; its root consumes stray taps so they don't hit the board.
    @Composable
    private fun FloatingKeypad(
        ui: GameUiState,
        horizontal: Boolean,
        boardSize: Dp,
        modifier: Modifier = Modifier,
    ) {
        val pal = LocalSudokuPalette.current
        val counts = IntArray(10)
        for (v in ui.values) if (v != 0) counts[v]++

        // Sized so it covers at most 3 board cells on its docked axis: full width × 3 rows tall for
        // TOP/BOTTOM, 3 columns wide for LEFT/RIGHT. Buttons therefore go sub-minimum, like the cells.
        val sized = if (horizontal) modifier.fillMaxWidth().height(boardSize / 3)
        else modifier.width(boardSize / 3)
        // Shared chrome. flickToMargin catches a directional swipe anywhere on the panel; the no-op
        // tap-consumer (before the 4dp inset) keeps gutter taps from falling through to the board.
        // Digit taps go to the child keys — a stationary tap is never read as a drag/flick.
        val card = sized
            .flickToMargin { viewModel.setKeypadMargin(it) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
            .padding(4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(pal.bg)
            .border(2.dp, pal.frame, RoundedCornerShape(14.dp))
            .padding(6.dp)

        val undoEnabled = ui.undo.isNotEmpty() && !ui.solved
        if (horizontal) {
            // control row + two digit rows, each an equal third of the fixed panel height.
            Column(card, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(pal.btn)) {
                        SegButton("Normal", ui.mode == InputMode.NORMAL, Modifier.weight(1f).fillMaxHeight()) {
                            viewModel.setMode(InputMode.NORMAL)
                        }
                        SegButton("Candidate", ui.mode == InputMode.CANDIDATE, Modifier.weight(1f).fillMaxHeight()) {
                            viewModel.setMode(InputMode.CANDIDATE)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    AutoToggle(ui.autoCandidate) { viewModel.toggleAutoCandidate() }
                    Text(
                        "Undo",
                        color = if (undoEnabled) pal.txt else pal.txtFaint,
                        fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(9.dp))
                            .clickable(enabled = undoEnabled) { viewModel.undo() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    IconGlyph("▾", "Hide keypad") { viewModel.deselect() }
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (d in 1..5) NumKey(d, counts[d] >= 9, Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (d in 6..9) NumKey(d, counts[d] >= 9, Modifier.weight(1f).fillMaxHeight())
                    IconKey("✕", "Erase", on = false, enabled = true, Modifier.weight(1f).fillMaxHeight()) { viewModel.erase() }
                }
            }
        } else {
            // 3×3 digit grid + a compact control stack; every key one board-cell tall.
            val keyH = ((boardSize / 9) - 6.dp).coerceAtLeast(0.dp)
            Column(card, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (r in 0 until 3) {
                    Row(Modifier.fillMaxWidth().height(keyH), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (c in 0 until 3) {
                            val d = r * 3 + c + 1
                            NumKey(d, counts[d] >= 9, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(keyH), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    IconKey("✎", "Candidate mode", on = ui.mode == InputMode.CANDIDATE, enabled = true, Modifier.weight(1f).fillMaxHeight()) {
                        viewModel.setMode(if (ui.mode == InputMode.NORMAL) InputMode.CANDIDATE else InputMode.NORMAL)
                    }
                    IconKey("A", "Auto candidates", on = ui.autoCandidate, enabled = true, Modifier.weight(1f).fillMaxHeight()) {
                        viewModel.toggleAutoCandidate()
                    }
                    IconKey("✕", "Erase", on = false, enabled = true, Modifier.weight(1f).fillMaxHeight()) { viewModel.erase() }
                }
                Row(Modifier.fillMaxWidth().height(keyH), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    IconKey("↺", "Undo", on = false, enabled = undoEnabled, Modifier.weight(1f).fillMaxHeight()) { viewModel.undo() }
                    IconKey("▾", "Hide keypad", on = false, enabled = true, Modifier.weight(2f).fillMaxHeight()) { viewModel.deselect() }
                }
            }
        }
    }

    // Directional-flick detector: a swipe past ~24dp on the panel snaps the keypad to that margin.
    // detectDragGestures is slop-based, so a stationary tap on a key is never read as a flick.
    private fun Modifier.flickToMargin(onFlick: (KeypadDock) -> Unit): Modifier =
        pointerInput(Unit) {
            val slop = 24.dp.toPx()
            var dx = 0f
            var dy = 0f
            detectDragGestures(
                onDragStart = { dx = 0f; dy = 0f },
                onDragEnd = {
                    if (max(abs(dx), abs(dy)) >= slop) {
                        onFlick(
                            if (abs(dx) >= abs(dy)) (if (dx < 0) KeypadDock.LEFT else KeypadDock.RIGHT)
                            else (if (dy < 0) KeypadDock.TOP else KeypadDock.BOTTOM),
                        )
                    }
                },
                onDrag = { change, amount -> change.consume(); dx += amount.x; dy += amount.y },
            )
        }

    // Compact labeled checkbox for auto candidate mode; used in the horizontal control row.
    @Composable
    private fun AutoToggle(on: Boolean, onClick: () -> Unit) {
        val pal = LocalSudokuPalette.current
        Row(
            Modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (on) pal.txt else pal.btn),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Text("✓", color = pal.bg, fontSize = 10.sp)
            }
            Spacer(Modifier.width(5.dp))
            Text("Auto", color = pal.txtDim, fontSize = 12.sp)
        }
    }

    // Segmented mode button — a filled tile with a centered label; fills whatever size it's given.
    @Composable
    private fun SegButton(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val pal = LocalSudokuPalette.current
        Box(
            modifier.clip(RoundedCornerShape(9.dp)).background(if (on) pal.txt else pal.btn)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (on) pal.bg else pal.txtDim,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                // Pin to one line so a narrow slot ellipsizes instead of wrapping "Candidate" vertically.
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // A digit key; fills whatever size the caller gives (board-cell sized inside the keypad).
    @Composable
    private fun NumKey(d: Int, dim: Boolean, modifier: Modifier) {
        val pal = LocalSudokuPalette.current
        Box(
            modifier.clip(RoundedCornerShape(9.dp)).background(pal.btn).clickable { viewModel.input(d) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                d.toString(),
                color = if (dim) pal.txtFaint else pal.txt,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }

    // A generic control key (erase / mode / auto / undo / dismiss): highlighted when [on], faint when
    // disabled. Fills the size it's given so it lines up with the digit keys.
    @Composable
    private fun IconKey(glyph: String, label: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val pal = LocalSudokuPalette.current
        Box(
            modifier.clip(RoundedCornerShape(9.dp)).background(if (on) pal.txt else pal.btn)
                .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                color = if (!enabled) pal.txtFaint else if (on) pal.bg else pal.txt,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @Composable
    private fun ToastPill(text: String) {
        val pal = LocalSudokuPalette.current
        Box(Modifier.fillMaxSize().padding(bottom = 90.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                text,
                color = pal.bg, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(pal.txt)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    @Composable
    private fun GeneratingOverlay() {
        val pal = LocalSudokuPalette.current
        Column(
            Modifier.fillMaxSize().background(pal.bg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("GENERATING", color = pal.txtDim, fontSize = 13.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text("Building today's puzzle…", color = pal.txt, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
