package dev.tyler.lightledger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import dev.tyler.lightledger.data.AndroidAccessUrlCipher
import dev.tyler.lightledger.simplefin.SimpleFinClient

private const val DEFAULT_TITLE = "SimpleFIN Setup Token"

/**
 * Paste-to-connect screen (CLAUDE-light-ledger.md §6.1, §7). QR-scan connect is deferred to
 * M3b — this is the paste-only M3a flow. Mirrors the M2 "editor" screens (AddEntry's payee
 * step, Categories' add flow): a single [LightTextInputEditor] wired to
 * [SimpleFinConnectViewModel.submit], swapping its title to the calm error copy on failure so
 * the user can edit and retry in place without a new UI idiom.
 */
class SimpleFinConnectScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SimpleFinConnectViewModel>(sealedActivity) {

    override val viewModelClass: Class<SimpleFinConnectViewModel>
        get() = SimpleFinConnectViewModel::class.java

    override fun createViewModel() = SimpleFinConnectViewModel(
        lightContext.dataStore,
        AndroidAccessUrlCipher(),
        SimpleFinClient(),
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val tokenFieldState = rememberTextFieldState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state.status) {
            if (state.status == ConnectStatus.Connected) goBack(Unit)
        }

        LightTheme(colors = themeColors) {
            when (state.status) {
                ConnectStatus.Connecting, ConnectStatus.Connected -> ConnectingContent()
                ConnectStatus.Idle, ConnectStatus.Error -> LightTextInputEditor(
                    title = state.message ?: DEFAULT_TITLE,
                    state = tokenFieldState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = { viewModel.submit(it.toString()) },
                    onBack = { goBack(Unit) },
                    submitLabel = "CONNECT",
                    editorKey = "simplefin-setup-token",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ConnectingContent() {
    Box(
        modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "Connecting…",
            variant = LightTextVariant.Detail,
            align = TextAlign.Center,
        )
    }
}
