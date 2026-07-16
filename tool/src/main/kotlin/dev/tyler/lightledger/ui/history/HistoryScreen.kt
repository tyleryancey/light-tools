package dev.tyler.lightledger.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import dev.tyler.lightledger.data.Category
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.Transaction
import dev.tyler.lightledger.ui.shared.CategoryGrid
import dev.tyler.lightledger.ui.shared.LedgerFormat
import java.time.YearMonth

class HistoryScreen(
    sealedActivity: SealedLightActivity,
    private val repository: LedgerRepository,
) : LightScreen<Unit, HistoryViewModel>(sealedActivity) {

    override val viewModelClass: Class<HistoryViewModel>
        get() = HistoryViewModel::class.java

    override fun createViewModel() = HistoryViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
            val selected = state.transactions.firstOrNull { it.id == state.selectedTransactionId }
            if (selected != null) {
                DetailContent(
                    transaction = selected,
                    categories = state.categories,
                    onSelectCategory = viewModel::updateCategory,
                    onDelete = viewModel::deleteSelected,
                    onBack = { goBack(null) },
                )
            } else {
                ListContent(
                    month = state.month,
                    transactions = state.transactions,
                    onPreviousMonth = viewModel::showPreviousMonth,
                    onNextMonth = viewModel::showNextMonth,
                    onSelect = viewModel::openDetail,
                    onClose = { goBack(Unit) },
                )
            }
        }
    }
}

@Composable
private fun ListContent(
    month: YearMonth,
    transactions: List<Transaction>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onPreviousMonth),
            center = LightTopBarCenter.Text(monthTitle(month)),
            rightButton = LightBarButton.LightIcon(icon = LightIcons.ARROW_RIGHT, onClick = onNextMonth),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        if (transactions.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LightText(text = "No transactions this month.", variant = LightTextVariant.Copy)
            }
        } else {
            LightLazyScrollView(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                uniformItemHeightGridUnits = 2.5f,
            ) {
                items(transactions, key = { it.id }) { transaction ->
                    LightText(
                        text = "${transaction.payee}  ${LedgerFormat.amount(transaction.amountMinor, transaction.currency)}",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { onSelect(transaction.id) }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }

        LightBottomBar(items = listOf(LightBarButton.LightIcon(icon = LightIcons.CLOSE, onClick = onClose)))
    }
}

@Composable
private fun DetailContent(
    transaction: Transaction,
    categories: List<Category>,
    onSelectCategory: (Long) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text(transaction.payee),
            rightButton = LightBarButton.LightIcon(icon = LightIcons.TRASH, onClick = onDelete),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightText(
            text = LedgerFormat.amount(transaction.amountMinor, transaction.currency),
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        )

        CategoryGrid(
            categories = categories,
            onSelect = { onSelectCategory(it.id) },
            modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp()),
        )
    }
}

private fun monthTitle(month: YearMonth): String = month.month.name.take(3) + " " + month.year
