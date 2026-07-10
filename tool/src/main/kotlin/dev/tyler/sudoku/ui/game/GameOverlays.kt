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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tyler.sudoku.data.Settings
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuPalette

/**
 * All overlays are plain scrim boxes (no window Dialogs) so system back always
 * routes through LightActivity -> goBack() -> GameViewModel.onBackPressed().
 * Scrim tap closes, matching the prototype's outside-tap-dismiss.
 */
@Composable
fun GameOverlays(vm: GameViewModel, ui: GameUiState, onPastPuzzles: () -> Unit) {
    when (val ov = ui.overlay) {
        null -> {}
        Overlay.Menu -> BottomSheet(vm) { MenuMain(vm) }
        Overlay.HintPage -> BottomSheet(vm) { HintPage(vm) }
        Overlay.SettingsSheet -> CenterSheet(vm, scrollable = true) { SettingsSheet(vm, ui.settings) }
        Overlay.Help -> CenterSheet(vm) { HelpSheet(vm) }
        Overlay.ConfirmReset -> CenterSheet(vm) {
            ConfirmSheet(
                vm, "Reset puzzle",
                "This clears everything you have entered. The clues stay.",
            ) { vm.confirmReset() }
        }
        Overlay.ConfirmReveal -> CenterSheet(vm) {
            ConfirmSheet(
                vm, "Reveal puzzle",
                "This fills in the whole solution and ends the puzzle.",
            ) { vm.confirmRevealPuzzle() }
        }
        is Overlay.Win -> CenterSheet(vm) { WinSheet(vm, ov, onPastPuzzles) }
        Overlay.Paused -> PausedOverlay(vm)
    }
}

private val Scrim = Color(0x99000000)

@Composable
private fun BottomSheet(vm: GameViewModel, content: @Composable () -> Unit) {
    val pal = LocalSudokuPalette.current
    Box(Modifier.fillMaxSize().background(Scrim).clickable { vm.dismissOverlay() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(pal.btn)
                .clickable(enabled = false) {}   // swallow taps inside the sheet
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // grabber
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(CircleShape).background(pal.btnLine)
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun CenterSheet(vm: GameViewModel, scrollable: Boolean = false, content: @Composable () -> Unit) {
    val pal = LocalSudokuPalette.current
    Box(
        Modifier.fillMaxSize().background(Scrim).clickable { vm.dismissOverlay() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 340.dp).fillMaxWidth().padding(16.dp)
                .clip(RoundedCornerShape(16.dp)).background(pal.btn)
                .clickable(enabled = false) {}
                .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) { content() }
    }
}

@Composable
private fun SheetLabel(text: String) {
    val pal = LocalSudokuPalette.current
    Text(text.uppercase(), color = pal.txtDim, fontSize = 11.sp, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
}

@Composable
private fun Tile(label: String, caption: String? = null, onClick: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp))
            .background(pal.bg).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, color = pal.txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (caption != null) {
            Text(caption, color = pal.txtDim, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun MenuMain(vm: GameViewModel) {
    val pal = LocalSudokuPalette.current
    SheetLabel("Help")
    Tile("Hint") { vm.showHintPage() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { Tile("Check cell") { vm.checkCell() } }
        Box(Modifier.weight(1f)) { Tile("Check puzzle") { vm.checkPuzzle() } }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.btnLine))
    Spacer(Modifier.height(12.dp))
    SheetLabel("Reveal & reset")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { Tile("Reveal cell") { vm.revealCell() } }
        Box(Modifier.weight(1f)) { Tile("Reveal puzzle") { vm.requestRevealPuzzle() } }
    }
    Tile("Reset puzzle") { vm.requestReset() }
}

@Composable
private fun HintPage(vm: GameViewModel) {
    val pal = LocalSudokuPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.showMenu() }.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", color = pal.txtDim, fontSize = 18.sp)
        Spacer(Modifier.width(6.dp))
        Text("Hint", color = pal.txtDim, fontSize = 14.sp)
    }
    Spacer(Modifier.height(10.dp))
    Tile("Point to a square", "Highlight the next solvable square — you place the number") { vm.pointHint() }
    Tile("Fill in a square", "Fill the next square in for you") { vm.fillHint() }
}

@Composable
private fun ToggleRow(label: String, caption: String? = null, on: Boolean, onToggle: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = pal.txt, fontSize = 15.sp)
            if (caption != null) {
                Text(caption, color = pal.txtDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        // pill toggle
        Box(
            Modifier.width(42.dp).height(24.dp).clip(RoundedCornerShape(12.dp))
                .background(if (on) pal.txt else pal.btnLine),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(3.dp).size(18.dp).clip(CircleShape).background(pal.bg))
        }
    }
}

@Composable
private fun SettingsSheet(vm: GameViewModel, st: Settings) {
    val pal = LocalSudokuPalette.current
    Text("Settings", color = pal.txt, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp))
    // Labels drop the redundant "Highlight" prefix — the section header already says it.
    SheetLabel("Highlighting")
    ToggleRow("Row and column", on = st.rowcol) { vm.toggleSetting("rowcol") }
    ToggleRow("Box", on = st.box) { vm.toggleSetting("box") }
    ToggleRow("Identical numbers", on = st.same) { vm.toggleSetting("same") }
    ToggleRow("Conflicts", on = st.conflicts) { vm.toggleSetting("conflicts") }
    SheetLabel("Assistance")
    ToggleRow("Check guesses when entered", on = st.checkOnEntry) { vm.toggleSetting("checkOnEntry") }
    ToggleRow("Start in auto candidate mode", on = st.autoStart) { vm.toggleSetting("autoStart") }
    SheetLabel("Game")
    ToggleRow("Show timer", on = st.timer) { vm.toggleSetting("timer") }
    ToggleRow("Play sound on solve", on = st.sound) { vm.toggleSetting("sound") }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.btnLine))
    ToggleRow("Plain mode", "Hide every highlight and check for a bare grid", st.plain) {
        vm.toggleSetting("plain")
    }
    Spacer(Modifier.height(8.dp))
    SolidButton("Done") { vm.dismissOverlay() }
}

@Composable
private fun HelpSheet(vm: GameViewModel) {
    val pal = LocalSudokuPalette.current
    Text("How to play", color = pal.txt, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 12.dp))
    Text(
        "Fill the grid so every row, column, and 3×3 box contains 1 through 9, with no repeats.\n\n" +
            "Tap a cell, then a number. Normal places a number; Candidate jots small pencil marks. " +
            "Undo steps back. Repeated numbers are ringed so you can spot clashes.\n\n" +
            "The ⋯ menu can check or reveal a cell, or reset the puzzle. A new set of puzzles arrives each day.",
        color = pal.txtDim, fontSize = 15.sp, lineHeight = 22.sp,
    )
    Spacer(Modifier.height(16.dp))
    SolidButton("Got it") { vm.dismissOverlay() }
}

@Composable
private fun ConfirmSheet(vm: GameViewModel, title: String, message: String, onConfirm: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Text(title, color = pal.txt, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text(message, color = pal.txtDim, fontSize = 15.sp, lineHeight = 21.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { GhostButton("Cancel") { vm.dismissOverlay() } }
        Box(Modifier.weight(1f)) { SolidButton("Confirm", fill = true, onClick = onConfirm) }
    }
}

@Composable
private fun WinSheet(vm: GameViewModel, win: Overlay.Win, onPastPuzzles: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Solved", color = pal.txt, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(win.subtitle, color = pal.txtDim, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        Text(
            win.timeText, color = pal.txt, fontSize = 34.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 14.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                GhostButton("Past puzzles") { vm.dismissOverlay(); onPastPuzzles() }
            }
            Box(Modifier.weight(1f)) { SolidButton("Close", fill = true) { vm.dismissOverlay() } }
        }
    }
}

@Composable
private fun PausedOverlay(vm: GameViewModel) {
    val pal = LocalSudokuPalette.current
    Column(
        Modifier.fillMaxSize().background(pal.bg).clickable { vm.dismissOverlay() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PAUSED", color = pal.txtDim, fontSize = 14.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        Text("Tap to resume", color = pal.txt, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SolidButton(label: String, fill: Boolean = false, onClick: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Text(
        label, color = pal.bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .let { if (fill) it.fillMaxWidth() else it }
            .clip(RoundedCornerShape(12.dp)).background(pal.txt)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    val pal = LocalSudokuPalette.current
    Text(
        label, color = pal.txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(pal.bg)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
