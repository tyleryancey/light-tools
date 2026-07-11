package dev.tyler.lightledger.ui.addentry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.ui.shared.AmountKeypad
import dev.tyler.lightledger.ui.shared.CategoryGrid

class AddEntryScreen(
    sealedActivity: SealedLightActivity,
    private val repository: LedgerRepository,
) : LightScreen<Unit, AddEntryViewModel>(sealedActivity) {

    override val viewModelClass: Class<AddEntryViewModel>
        get() = AddEntryViewModel::class.java

    override fun createViewModel() = AddEntryViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val payeeFieldState = rememberTextFieldState(state.payee)
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state.saved) {
            if (state.saved) goBack(Unit)
        }

        LightTheme(colors = themeColors) {
            when (state.step) {
                AddEntryStep.AMOUNT -> AmountStep(
                    amountText = state.amountText,
                    canContinue = viewModel.canContinueFromAmount(),
                    onDigit = viewModel::onDigit,
                    onDecimal = viewModel::onDecimal,
                    onBackspace = viewModel::onBackspace,
                    onContinue = viewModel::confirmAmount,
                    onBack = { goBack(Unit) },
                )

                AddEntryStep.PAYEE -> LightTextInputEditor(
                    title = "Payee",
                    state = payeeFieldState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = { viewModel.confirmPayee(it.toString()) },
                    onBack = { goBack(null) },
                    submitLabel = "NEXT",
                    modifier = Modifier.fillMaxSize(),
                )

                AddEntryStep.CATEGORY -> CategoryStep(
                    categories = state.categories,
                    onSelect = viewModel::selectCategory,
                    onBack = { goBack(Unit) },
                )
            }
        }
    }
}

@Composable
private fun AmountStep(
    amountText: String,
    canContinue: Boolean,
    onDigit: (String) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text("Add"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Subtitle, not Title (115 design units) — the LP3 panel is ~472dp tall
            // and near-square; Title here plus a fixed 4-row keypad below (no
            // scroll) overflows the same way the Sudoku board once did.
            LightText(
                text = amountText.ifEmpty { "0" },
                variant = LightTextVariant.Subtitle,
                align = TextAlign.Center,
            )
        }

        AmountKeypad(
            onDigit = onDigit,
            onDecimal = onDecimal,
            onBackspace = onBackspace,
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "NEXT", onClick = onContinue).takeIf { canContinue },
            ),
        )
    }
}

@Composable
private fun CategoryStep(
    categories: List<dev.tyler.lightledger.data.Category>,
    onSelect: (dev.tyler.lightledger.data.Category) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text("Category"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        CategoryGrid(
            categories = categories,
            onSelect = onSelect,
            modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp()),
        )
    }
}
