package dev.tyler.sudoku.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
                StatusRow(ui)
                Board(viewModel, ui, Modifier.padding(top = 8.dp))
                Spacer(Modifier.weight(1f))
                Controls(ui)
                Spacer(Modifier.height(16.dp))
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

    @Composable
    private fun TopBar(ui: GameUiState) {
        val pal = LocalSudokuPalette.current
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconGlyph("‹", "Back") { goBack(GameResult.Closed) }
            Spacer(Modifier.weight(1f))
            IconGlyph("?", "How to play") { viewModel.showHelp() }
            Spacer(Modifier.width(16.dp))
            IconGlyph("⚙", "Settings") { viewModel.showSettings() }
            Spacer(Modifier.width(16.dp))
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

    @Composable
    private fun StatusRow(ui: GameUiState) {
        val pal = LocalSudokuPalette.current
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ui.difficulty.replaceFirstChar { it.uppercase() },
                color = pal.txtDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (ui.settings.timer) {
                Text(
                    viewModel.fmtTime(ui.elapsedSec),
                    color = pal.txt, fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(10.dp))
                IconGlyph("❚❚", "Pause") { viewModel.pauseTapped() }
            }
        }
    }

    @Composable
    private fun Controls(ui: GameUiState) {
        val pal = LocalSudokuPalette.current
        // Normal | Candidate segmented control + Undo
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(pal.btn),
            ) {
                SegButton("Normal", ui.mode == InputMode.NORMAL && !ui.autoCandidate) {
                    viewModel.setMode(InputMode.NORMAL)
                }
                SegButton("Candidate", ui.mode == InputMode.CANDIDATE && !ui.autoCandidate) {
                    viewModel.setMode(InputMode.CANDIDATE)
                }
            }
            Spacer(Modifier.width(10.dp))
            val undoEnabled = ui.undo.isNotEmpty() && !ui.solved
            Text(
                "Undo",
                color = if (undoEnabled) pal.txt else pal.txtFaint,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = undoEnabled) { viewModel.undo() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // numpad 1..9 + erase; dim digits placed 9 times
        val counts = IntArray(10)
        for (v in ui.values) if (v != 0) counts[v]++
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
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
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(10.dp))
                    .background(pal.btn).clickable { viewModel.erase() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = pal.txt, fontSize = 18.sp) }
        }

        // auto candidate row
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { viewModel.toggleAutoCandidate() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val pal2 = LocalSudokuPalette.current
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (ui.autoCandidate) pal2.txt else pal2.btn),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.autoCandidate) Text("✓", color = pal2.bg, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text("Auto candidate mode", color = pal2.txtDim, fontSize = 14.sp)
        }
    }

    @Composable
    private fun SegButton(label: String, on: Boolean, onClick: () -> Unit) {
        val pal = LocalSudokuPalette.current
        Text(
            label,
            color = if (on) pal.bg else pal.txtDim,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) pal.txt else pal.btn)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }

    @Composable
    private fun NumKey(d: Int, dim: Boolean, modifier: Modifier) {
        val pal = LocalSudokuPalette.current
        Box(
            modifier.height(52.dp).clip(RoundedCornerShape(10.dp))
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
