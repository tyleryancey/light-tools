package dev.tyler.sudoku.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.thelightphone.sdk.ui.LightSurfaceScheme
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.inferredSurfaceScheme

/**
 * Sudoku board tokens, one full ramp per OS theme. Dark is the scaffold's
 * SudokuColors verbatim; Light is the mirrored ramp from the design spec.
 * Both are device-calibration candidates — keep every color in this file.
 */
data class SudokuPalette(
    val bg: Color, val frame: Color, val line: Color, val box: Color,
    val givenTile: Color, val peer: Color, val sameNum: Color, val sel: Color,
    val givenInk: Color, val entryInk: Color, val pencilInk: Color,
    val ring: Color, val dot: Color, val txt: Color, val txtDim: Color,
    val txtFaint: Color, val hair: Color, val btn: Color, val btnLine: Color,
    val selInk: Color,
) {
    companion object {
        val Dark = SudokuPalette(
            bg = Color(0xFF000000), frame = Color(0xFFDCDCDC), line = Color(0xFF1B1B1B),
            box = Color(0xFF4A4A4A), givenTile = Color(0xFF141414), peer = Color(0xFF1F1F1F),
            sameNum = Color(0xFF2F2F2F), sel = Color(0xFFF1F1F1), givenInk = Color(0xFFF0F0F0),
            entryInk = Color(0xFFC9C9C9), pencilInk = Color(0xFF6E6E6E), ring = Color(0xFF8A8A8A),
            dot = Color(0xFFF1F1F1), txt = Color(0xFFEDEDED), txtDim = Color(0xFF7C7C7C),
            txtFaint = Color(0xFF555555), hair = Color(0xFF262626), btn = Color(0xFF161616),
            btnLine = Color(0xFF333333), selInk = Color(0xFF0A0A0A),
        )
        val Light = SudokuPalette(
            bg = Color(0xFFFFFFFF), frame = Color(0xFF232323), line = Color(0xFFE4E4E4),
            box = Color(0xFFB5B5B5), givenTile = Color(0xFFEBEBEB), peer = Color(0xFFE0E0E0),
            sameNum = Color(0xFFD0D0D0), sel = Color(0xFF0E0E0E), givenInk = Color(0xFF0F0F0F),
            entryInk = Color(0xFF363636), pencilInk = Color(0xFF919191), ring = Color(0xFF757575),
            dot = Color(0xFF0E0E0E), txt = Color(0xFF121212), txtDim = Color(0xFF838383),
            txtFaint = Color(0xFFAAAAAA), hair = Color(0xFFD9D9D9), btn = Color(0xFFE9E9E9),
            btnLine = Color(0xFFCCCCCC), selInk = Color(0xFFF5F5F5),
        )
    }
}

val LocalSudokuPalette = staticCompositionLocalOf { SudokuPalette.Dark }

/** Wrap every screen's Content() in this: OS theme -> LightTheme + palette + bg fill. */
@Composable
fun SudokuSurface(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    val palette = if (colors.inferredSurfaceScheme() == LightSurfaceScheme.Dark) {
        SudokuPalette.Dark
    } else {
        SudokuPalette.Light
    }
    LightTheme(colors = colors) {
        CompositionLocalProvider(LocalSudokuPalette provides palette) {
            Box(Modifier.fillMaxSize().background(palette.bg)) { content() }
        }
    }
}
