package dev.tyler.sudoku.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import dev.tyler.sudoku.data.DataStoreKeyValueStore
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuSurface
import kotlinx.coroutines.delay

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
                            // keypadDock(ui.selected) is a genuine State read, so the panel repositions
                            // as the selection moves; it docks to the half OPPOSITE the selected cell
                            // (low rows -> top, high rows -> bottom) so that cell stays visible.
                            val dock = viewModel.keypadDock(ui.selected)
                            FloatingKeypad(
                                ui,
                                Modifier.align(
                                    if (dock == KeypadDock.TOP) Alignment.TopCenter
                                    else Alignment.BottomCenter,
                                ),
                            )
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

    // Floating number keypad: overlays the board (never steals its height) and docks to the half
    // OPPOSITE the selected cell (see GameViewModel.keypadDock) so that cell stays visible while you
    // type. An opaque, framed card inset a few dp off the board edge so its 2dp frame doesn't merge
    // with the board's; its root consumes stray taps so they don't fall through to the cell beneath.
    @Composable
    private fun FloatingKeypad(ui: GameUiState, modifier: Modifier = Modifier) {
        val pal = LocalSudokuPalette.current
        Column(
            modifier
                .fillMaxWidth()
                // Consume taps across the FULL panel bounds (incl. the 4dp gutter) BEFORE the outer
                // padding insets the visual card. Modifier order is hit-test order: if this sat after
                // the padding, taps in that gutter would fall through to the board cell beneath and
                // flip/dismiss the keypad mid-entry.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(pal.bg)
                .border(2.dp, pal.frame, RoundedCornerShape(14.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            // One compact row: Normal | Candidate switcher + Auto candidate toggle + Undo + dismiss.
            // The switcher stays lit and usable even with auto candidate mode on — auto only changes
            // which candidate layer is shown, not whether you can switch/enter.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(pal.btn),
                ) {
                    SegButton("Normal", ui.mode == InputMode.NORMAL, Modifier.weight(1f)) {
                        viewModel.setMode(InputMode.NORMAL)
                    }
                    SegButton("Candidate", ui.mode == InputMode.CANDIDATE, Modifier.weight(1f)) {
                        viewModel.setMode(InputMode.CANDIDATE)
                    }
                }
                Spacer(Modifier.width(8.dp))
                AutoToggle(ui.autoCandidate) { viewModel.toggleAutoCandidate() }
                Spacer(Modifier.width(8.dp))
                val undoEnabled = ui.undo.isNotEmpty() && !ui.solved
                Text(
                    "Undo",
                    color = if (undoEnabled) pal.txt else pal.txtFaint,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = undoEnabled) { viewModel.undo() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                // Dismiss the keypad (deselect) to see the whole (already-big) board.
                IconGlyph("▾", "Hide keypad") { viewModel.deselect() }
            }

            // numpad 1..9 + erase; dim digits placed 9 times. Keys are >=44dp tall touch targets.
            val counts = IntArray(10)
            for (v in ui.values) if (v != 0) counts[v]++
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for (d in 1..5) NumKey(d, counts[d] >= 9, Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for (d in 6..9) NumKey(d, counts[d] >= 9, Modifier.weight(1f))
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                        .background(pal.btn).clickable { viewModel.erase() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = pal.txt, fontSize = 18.sp) }
            }
        }
    }

    // Compact labeled checkbox for auto candidate mode; sits inline with the mode switcher.
    @Composable
    private fun AutoToggle(on: Boolean, onClick: () -> Unit) {
        val pal = LocalSudokuPalette.current
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (on) pal.txt else pal.btn),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Text("✓", color = pal.bg, fontSize = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
            Text("Auto", color = pal.txtDim, fontSize = 13.sp)
        }
    }

    @Composable
    private fun SegButton(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val pal = LocalSudokuPalette.current
        Text(
            label,
            color = if (on) pal.bg else pal.txtDim,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            // The two buttons split the switcher width via weight; pin to one line so a narrow
            // slot ellipsizes instead of wrapping "Candidate" to one letter per line (the old bug).
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) pal.txt else pal.btn)
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        )
    }

    @Composable
    private fun NumKey(d: Int, dim: Boolean, modifier: Modifier) {
        val pal = LocalSudokuPalette.current
        Box(
            modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
                .background(pal.btn).clickable { viewModel.input(d) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                d.toString(),
                color = if (dim) pal.txtFaint else pal.txt,
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
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
