package dev.tyler.sudoku.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tyler.sudoku.engine.SudokuEngine
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuPalette

@Composable
fun Board(vm: GameViewModel, ui: GameUiState, boardSize: Dp, modifier: Modifier = Modifier) {
    val pal = LocalSudokuPalette.current
    val conflicts = vm.conflicts()
    val sel = ui.selected
    val selVal = if (sel >= 0) ui.values[sel] else 0
    val st = ui.settings
    // Digit/pencil sizes scale with the actual cell size (boardSize/9) rather than a fixed
    // sp value, so the board stays legible on screens too small for the original ~49dp-cell
    // calibration (ratio matches that calibration: 24sp / 49dp given ~= 0.49).
    val cellSize = boardSize / 9
    val digitFontSize = (cellSize.value * 0.49f).sp
    val pencilFontSize = (cellSize.value * 0.2f).sp

    Column(
        modifier
            .aspectRatio(1f, matchHeightConstraintsFirst = true)
            .border(2.dp, pal.frame)
    ) {
        for (r in 0 until 9) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until 9) {
                    val i = r * 9 + c
                    val v = ui.values[i]
                    val fixed = ui.givenMask[i] || ui.lockedMask[i]

                    // background value ramp (selection > samenum > peer > given-tile > bg)
                    val sameRowCol = sel >= 0 && (sel / 9 == r || sel % 9 == c)
                    val sameBox = sel >= 0 && SudokuEngine.boxOf(i) == SudokuEngine.boxOf(sel)
                    val bg = when {
                        i == sel -> pal.sel
                        !st.plain && st.same && selVal != 0 && v == selVal -> pal.sameNum
                        !st.plain && ((st.rowcol && sameRowCol) || (st.box && sameBox)) -> pal.peer
                        fixed -> pal.givenTile
                        else -> pal.bg
                    }
                    val conflict = !st.plain && st.conflicts && v != 0 && conflicts[i]

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(bg)
                            // thin internal lines + thicker 3x3 box separators (right/bottom)
                            .drawBehind {
                                val thin = 1.dp.toPx(); val thick = 2.dp.toPx()
                                if (c < 8) drawLineRight(
                                    if ((c + 1) % 3 == 0) thick else thin,
                                    if ((c + 1) % 3 == 0) pal.box else pal.line,
                                )
                                if (r < 8) drawLineBottom(
                                    if ((r + 1) % 3 == 0) thick else thin,
                                    if ((r + 1) % 3 == 0) pal.box else pal.line,
                                )
                            }
                            .clickable { vm.select(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (conflict) Box(Modifier.matchParentSize().border(2.dp, pal.ring))
                        if (ui.checkErr[i]) Box(
                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp)
                                .background(if (i == sel) pal.selInk else pal.dot, shape = CircleShape)
                        )

                        if (v != 0) {
                            Text(
                                text = v.toString(),
                                color = when {
                                    i == sel -> pal.selInk
                                    fixed -> pal.givenInk
                                    else -> pal.entryInk
                                },
                                fontSize = digitFontSize,
                                fontWeight = if (ui.givenMask[i]) FontWeight.SemiBold else FontWeight.Medium
                            )
                        } else {
                            val mask = if (ui.autoCandidate) {
                                var m = 0
                                for (d in SudokuEngine.autoCandidates(ui.values, i)) m = m or (1 shl (d - 1))
                                m
                            } else ui.candidates[i]
                            if (mask != 0) PencilMarks(mask, dark = i == sel, pal = pal, fontSize = pencilFontSize)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PencilMarks(mask: Int, dark: Boolean, pal: SudokuPalette, fontSize: TextUnit) {
    Column(Modifier.fillMaxSize()) {
        for (br in 0 until 3) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (bc in 0 until 3) {
                    val d = br * 3 + bc + 1
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (mask and (1 shl (d - 1)) != 0)
                            Text(d.toString(), color = if (dark) pal.selInk else pal.pencilInk, fontSize = fontSize)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawLineRight(w: Float, color: Color) {
    drawLine(color, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
}

private fun DrawScope.drawLineBottom(w: Float, color: Color) {
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}
