package dev.tyler.lightledger.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private val KEYPAD_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(".", "0", "⌫"),
)

@Composable
fun AmountKeypad(
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        KEYPAD_ROWS.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { key ->
                    // Subheading, not Heading, and tight vertical padding — 4 rows
                    // of keys sit above a fixed (non-scrolling) bottom bar on the
                    // LP3's ~472dp-tall panel; see AmountStep's comment for why.
                    LightText(
                        text = key,
                        variant = LightTextVariant.Subheading,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .lightClickable {
                                when (key) {
                                    "." -> onDecimal()
                                    "⌫" -> onBackspace()
                                    else -> onDigit(key)
                                }
                            }
                            .padding(vertical = 0.5f.gridUnitsAsDp(), horizontal = 1.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
