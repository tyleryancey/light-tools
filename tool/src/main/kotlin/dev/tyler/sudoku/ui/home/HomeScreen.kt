package dev.tyler.sudoku.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.sudoku.data.DateKeys
import dev.tyler.sudoku.ui.archive.ArchiveScreen
import dev.tyler.sudoku.ui.game.GameResult
import dev.tyler.sudoku.ui.game.GameScreen
import dev.tyler.sudoku.ui.theme.LocalSudokuPalette
import dev.tyler.sudoku.ui.theme.SudokuSurface

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private fun openGame(difficulty: String) {
        val today = DateKeys.today(System.currentTimeMillis())
        navigateTo({ sa -> GameScreen(sa, today, difficulty) }) { result ->
            if (result == GameResult.OpenArchive) openArchive()
        }
    }

    private fun openArchive() {
        navigateTo({ sa -> ArchiveScreen(sa) })
    }

    @Composable
    override fun Content() {
        SudokuSurface {
            val pal = LocalSudokuPalette.current
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Sudoku", color = pal.txt, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text(
                    "A quiet numbers game. No clock pressure, no streaks.",
                    color = pal.txt, fontSize = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
                Spacer(Modifier.height(40.dp))
                Text("CHOOSE YOUR PUZZLE", color = pal.txtDim, fontSize = 13.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(18.dp))

                listOf("easy" to "Easy", "medium" to "Medium", "hard" to "Hard").forEach { (key, label) ->
                    Box(
                        Modifier.fillMaxWidth().widthIn(max = 300.dp).padding(vertical = 7.dp)
                            .clip(RoundedCornerShape(34.dp))
                            .border(2.dp, pal.frame, RoundedCornerShape(34.dp))
                            .clickable { openGame(key) }
                            .height(52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = pal.txt, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(38.dp))
                Text(
                    DateKeys.today(System.currentTimeMillis()),
                    color = pal.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Past puzzles", color = pal.txtDim, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { openArchive() }.padding(8.dp),
                )
            }
        }
    }
}
