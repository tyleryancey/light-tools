package dev.tyler.lightledger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * QR-scan connect (CLAUDE-light-ledger.md §6.1, §7 — M3b). Mirrors
 * `AuthenticatorQrScannerScreen`: a bare [SimpleLightScreen] hosting
 * [LightQrCodeScanner] that returns the scanned setup token (or null on back) to the
 * caller. No claim/sync logic lives here — the token is handed to
 * [SimpleFinConnectViewModel.submit] by whichever screen navigated here, keeping the
 * one claim path shared between paste and scan.
 *
 * Navigation is deferred out of `onScanned` into a `LaunchedEffect` per the SDK's
 * once-only-onScanned + pop-before-push contract.
 */
class SimpleFinQrScannerScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<String?>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var pendingScan by remember { mutableStateOf<String?>(null) }
        LightTheme(colors = themeColors) {
            LightQrCodeScanner(
                title = "Scan SimpleFIN Token",
                onScanned = { pendingScan = it },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }

        LaunchedEffect(pendingScan) {
            val value = pendingScan ?: return@LaunchedEffect
            goBack(value)
        }
    }
}
