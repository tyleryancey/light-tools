package dev.tyler.lightledger.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightJobState
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
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
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.ui.categories.CategoriesScreen
import kotlinx.coroutines.delay

/** Must match the `@LightJob("simplefin-sync")` key registered in `simplefin/LedgerJobs.kt`. */
private const val SYNC_JOB_KEY = "simplefin-sync"

/** How long the "synced" status stays up before clearing, so it reads as a calm confirmation
 * rather than a state the user has to dismiss. */
private const val SYNCED_STATUS_DISPLAY_MS = 3000L

class SettingsScreen(
    sealedActivity: SealedLightActivity,
    private val repository: LedgerRepository,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(repository, lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        var syncStatusText by remember { mutableStateOf<String?>(null) }

        // Watches the background sync job so "Sync now" can show calm, transient status
        // copy instead of a blocking spinner. A Succeeded emission also triggers a VM
        // reload so newly-synced account names show up without a manual re-visit.
        LaunchedEffect(Unit) {
            LightWork.observe(lightContext, SYNC_JOB_KEY).collect { jobState ->
                when (jobState) {
                    is LightJobState.Enqueued, is LightJobState.Running -> syncStatusText = "syncing…"
                    is LightJobState.Succeeded -> {
                        viewModel.reload()
                        syncStatusText = "synced"
                    }
                    else -> syncStatusText = null
                }
            }
        }

        LaunchedEffect(syncStatusText) {
            if (syncStatusText == "synced") {
                delay(SYNCED_STATUS_DISPLAY_MS)
                syncStatusText = null
            }
        }

        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LightText(
                        text = "Categories",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                navigateTo(screenFactory = { CategoriesScreen(it, repository) })
                            }
                            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
                    )

                    if (state.connected) {
                        ConnectedSimpleFinSection(
                            accountNames = state.accountNames,
                            statusText = syncStatusText,
                            onSyncNow = { LightWork.enqueue(lightContext, SYNC_JOB_KEY) },
                            onDisconnect = {
                                LightWork.cancel(lightContext, SYNC_JOB_KEY)
                                viewModel.disconnect()
                            },
                        )
                    } else {
                        LightText(
                            text = "SimpleFIN",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    navigateTo(screenFactory = { SimpleFinConnectScreen(it) }) {
                                        // The connect screen already goes back on success, but
                                        // reload defensively so the section flips to "connected"
                                        // even if onScreenShow's own reload raced it.
                                        viewModel.reload()
                                    }
                                }
                                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedSimpleFinSection(
    accountNames: List<String>,
    statusText: String?,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LightText(
        text = "SimpleFIN",
        variant = LightTextVariant.Detail,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )

    accountNames.forEach { name ->
        LightText(
            text = name,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        )
    }

    statusText?.let {
        LightText(
            text = it,
            variant = LightTextVariant.Detail,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
        )
    }

    LightText(
        text = "Sync now",
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onSyncNow)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    )

    LightText(
        text = "Disconnect & forget",
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onDisconnect)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    )
}
