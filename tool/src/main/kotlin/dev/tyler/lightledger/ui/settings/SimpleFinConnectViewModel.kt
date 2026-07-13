package dev.tyler.lightledger.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import dev.tyler.lightledger.data.AccessUrlCipher
import dev.tyler.lightledger.data.LedgerPreferences
import dev.tyler.lightledger.simplefin.SimpleFinApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ConnectStatus { Idle, Connecting, Connected, Error }

data class ConnectUiState(
    val status: ConnectStatus = ConnectStatus.Idle,
    val message: String? = null,
)

/**
 * Paste-to-connect flow (CLAUDE-light-ledger.md §6.1, §7): claims a pasted SimpleFIN setup
 * token into an Access URL via [api], encrypts it at rest via [cipher], and stores the
 * resulting blob in [dataStore] under [LedgerPreferences.ACCESS_BLOB]. QR-scan connect is
 * deferred to M3b — this is the paste-only M3a flow.
 *
 * The setup token and the claimed Access URL are both bearer-equivalent credentials
 * (§6.1) — neither is ever logged here.
 */
class SimpleFinConnectViewModel(
    private val dataStore: DataStore<Preferences>,
    private val cipher: AccessUrlCipher,
    private val api: SimpleFinApi,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    /** Claims [setupToken] and, on success, stores the encrypted Access URL. A blank
     * paste is ignored (no state change, no API call) so an accidental empty submit
     * doesn't flash an error; a submit while already [ConnectStatus.Connecting] is ignored
     * so the two triggers `LightTextInputEditor` exposes (keyboard return + bottom-bar
     * button) can't double-claim a single-use token in one frame.
     *
     * The whole claim → encrypt → persist sequence runs inside one try/catch: a failure at
     * any stage — a `claim` network/HTTP error, an AndroidKeyStore encrypt failure (e.g. a
     * `KeyPermanentlyInvalidatedException` after a lock-screen change), or a DataStore write
     * error — routes to [ConnectStatus.Error] rather than crashing `viewModelScope` or
     * stranding the UI on "Connecting…". `CancellationException` is rethrown so structured
     * concurrency (VM clear / screen leave) is preserved. The error copy is a fixed calm
     * string — the underlying throwable is never interpolated, so the token/Access URL can
     * never leak into a message. */
    fun submit(setupToken: String) {
        val trimmed = setupToken.trim()
        if (trimmed.isEmpty()) return
        if (_uiState.value.status == ConnectStatus.Connecting) return

        _uiState.value = ConnectUiState(status = ConnectStatus.Connecting)
        viewModelScope.launch {
            try {
                val accessUrl = api.claim(trimmed).getOrThrow()
                val blob = cipher.encryptToBase64(accessUrl)
                dataStore.edit { it[LedgerPreferences.ACCESS_BLOB] = blob }
                _uiState.value = ConnectUiState(status = ConnectStatus.Connected)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _uiState.value = ConnectUiState(
                    status = ConnectStatus.Error,
                    message = "Couldn't connect. Check the token and try again.",
                )
            }
        }
    }
}
