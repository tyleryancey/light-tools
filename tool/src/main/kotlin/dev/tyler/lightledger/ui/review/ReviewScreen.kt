package dev.tyler.lightledger.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.tyler.lightledger.data.Category
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.ReviewItem
import dev.tyler.lightledger.ui.shared.CategoryGrid
import java.time.LocalDate

/**
 * Review-inbox screen: one NEEDS_REVIEW transaction at a time. Tapping a category confirms it
 * (via [ReviewViewModel.confirm]) and advances to the next item; "Skip" leaves it in the inbox
 * and advances without confirming. When the inbox is empty, shows "Nothing to review." with a
 * BACK affordance so the user isn't stranded.
 */
class ReviewScreen(
    sealedActivity: SealedLightActivity,
    private val repository: LedgerRepository,
) : LightScreen<Unit, ReviewViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReviewViewModel>
        get() = ReviewViewModel::class.java

    override fun createViewModel() = ReviewViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
            val item = state.item
            when {
                item != null -> ItemContent(
                    item = item,
                    remaining = state.remaining,
                    categories = state.categories,
                    onSelect = { viewModel.confirm(it.id) },
                    onSkip = viewModel::skip,
                    onBack = { goBack(Unit) },
                )
                // `done` (not `item == null`) gates the empty state: the ReviewUiState()
                // default is item=null/done=false until reload()'s coroutine completes, so
                // keying off item==null alone would flash "Nothing to review." on every entry
                // into a non-empty inbox while the DB load is in flight.
                state.done -> EmptyContent(onBack = { goBack(Unit) })
                else -> LoadingContent()
            }
        }
    }
}

/**
 * Transient state between screen entry and [ReviewViewModel.reload]'s coroutine completing.
 * Deliberately blank (theme background only, no text) rather than a "Nothing to review."
 * or "0 to review" flash the DB load hasn't confirmed yet.
 */
@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background))
}

@Composable
private fun EmptyContent(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text("Review"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            LightText(
                text = "Nothing to review.",
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ItemContent(
    item: ReviewItem,
    remaining: Int,
    categories: List<Category>,
    onSelect: (Category) -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text("$remaining to review"),
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )

        LightText(
            text = formatDate(item.postedEpochDay),
            variant = LightTextVariant.Detail,
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = item.payee,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
        )
        LightText(
            text = formatAmount(item.amountMinor),
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = item.accountName,
            variant = LightTextVariant.Detail,
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        )

        CategoryGrid(
            categories = categories,
            onSelect = onSelect,
            modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp()),
        )

        LightBottomBar(
            items = listOf(LightBarButton.Text(text = "SKIP", onClick = onSkip)),
        )
    }
}

private fun formatDate(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return date.month.name.take(3) + " " + date.dayOfMonth + ", " + date.year
}

private fun formatAmount(amountMinor: Long): String {
    val format = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US)
    return format.format(amountMinor / 100.0)
}
