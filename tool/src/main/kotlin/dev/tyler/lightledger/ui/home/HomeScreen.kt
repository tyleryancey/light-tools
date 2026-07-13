package dev.tyler.lightledger.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightJobState
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import dev.tyler.lightledger.data.LedgerDatabase
import dev.tyler.lightledger.data.RoomLedgerRepository
import dev.tyler.lightledger.ui.addentry.AddEntryScreen
import dev.tyler.lightledger.ui.history.HistoryScreen
import dev.tyler.lightledger.ui.review.ReviewScreen
import dev.tyler.lightledger.ui.settings.SettingsScreen
import java.time.YearMonth
import java.util.Locale

/** Must match the `@LightJob("simplefin-sync")` key registered in `simplefin/LedgerJobs.kt`. */
private const val SYNC_JOB_KEY = "simplefin-sync"

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {

    private val repository = RoomLedgerRepository.getInstance {
        lightContext.buildDatabase(LedgerDatabase::class.java, RoomLedgerRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(repository, lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        var syncing by remember { mutableStateOf(false) }

        // Opportunistic background sync: fires at most once per Home-open, only when
        // SimpleFIN is connected and the last sync is stale (see
        // HomeViewModel.isOpportunisticSyncDue). Never blocks the UI.
        LaunchedEffect(Unit) {
            if (viewModel.isOpportunisticSyncDue(System.currentTimeMillis())) {
                LightWork.enqueue(lightContext, SYNC_JOB_KEY)
            }
        }

        // Watches the sync job so Home can show calm "syncing…" text (never a blocking
        // spinner) and refresh the summary when a sync actually completes. `sawActiveSync`
        // guards against a stale retained Succeeded from an earlier session — WorkManager
        // keeps the last terminal WorkInfo, so without this guard the very first emission on
        // a fresh cold Home open could fire a spurious reload for a sync that didn't just run.
        LaunchedEffect(Unit) {
            var sawActiveSync = false
            LightWork.observe(lightContext, SYNC_JOB_KEY).collect { jobState ->
                when (jobState) {
                    is LightJobState.Enqueued, is LightJobState.Running -> {
                        sawActiveSync = true
                        syncing = true
                    }
                    is LightJobState.Succeeded -> {
                        if (sawActiveSync) {
                            viewModel.reload()
                        }
                        syncing = false
                    }
                    else -> syncing = false
                }
            }
        }

        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    center = LightTopBarCenter.Text(monthTitle(state.month)),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )

                LightText(
                    text = formatAmount(totalSpentMinor(state.categoryTotals)) + " spent",
                    variant = LightTextVariant.Title,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()),
                )

                if (state.needsReviewCount > 0) {
                    LightText(
                        text = "${state.needsReviewCount} to review →",
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                navigateTo(screenFactory = { ReviewScreen(it, repository) }) {
                                    viewModel.reload()
                                }
                            }
                            .padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                }

                if (syncing) {
                    LightText(
                        text = "syncing…",
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                }

                when {
                    state.loading -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(text = "Loading…", variant = LightTextVariant.Copy)
                    }

                    state.categoryTotals.isEmpty() -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "Nothing yet this month.",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                        )
                    }

                    else -> LightScrollView(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        state.categoryTotals.forEach { total ->
                            LightText(
                                text = "${total.categoryName}  ${formatAmount(total.totalMinor)}",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            onClick = {
                                navigateTo(screenFactory = { AddEntryScreen(it, repository) }) {
                                    viewModel.reload()
                                }
                            },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.SEARCH,
                            onClick = { navigateTo(screenFactory = { HistoryScreen(it, repository) }) },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { navigateTo(screenFactory = { SettingsScreen(it, repository) }) },
                        ),
                    ),
                )
            }
        }
    }
}

private fun monthTitle(month: YearMonth): String = month.month.name.take(3) + " " + month.year

private fun formatAmount(amountMinor: Long): String {
    val format = java.text.NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(amountMinor / 100.0)
}
