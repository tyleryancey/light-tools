package dev.tyler.lightledger.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.lightledger.data.LedgerPreferences
import dev.tyler.lightledger.data.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val DEFAULT_BACKGROUND_SYNC_ENABLED = false
private const val DEFAULT_BACKGROUND_SYNC_HOURS = 12

data class SettingsUiState(
    val connected: Boolean = false,
    val accountNames: List<String> = emptyList(),
    val loading: Boolean = true,
    val bridgeError: String? = null,
    val backgroundSyncEnabled: Boolean = DEFAULT_BACKGROUND_SYNC_ENABLED,
    val backgroundSyncHours: Int = DEFAULT_BACKGROUND_SYNC_HOURS,
)

/**
 * Settings-screen SimpleFIN section logic. "Connected" is derived from the presence of the
 * encrypted Access URL blob in [dataStore] (never the blob's contents — this VM never
 * decrypts it); account names come from [repository]. Deliberately Android/LightWork-free so
 * it's JVM-unit-testable; the Screen owns enqueue/observe/cancel of the sync job itself via
 * `lightContext`.
 *
 * [disconnect] updates [uiState] itself once the clear-and-delete work completes, rather than
 * taking a completion callback — the Screen only ever needs to observe [uiState], so a second
 * signaling path would be redundant.
 */
class SettingsViewModel(
    private val repository: LedgerRepository,
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val prefs = dataStore.data.first()
            val connected = prefs[LedgerPreferences.ACCESS_BLOB] != null
            val accountNames = repository.listSimpleFinAccounts()
            val bridgeError = prefs[LedgerPreferences.LAST_ERROR]
            val backgroundSyncEnabled =
                prefs[LedgerPreferences.BACKGROUND_SYNC_ENABLED] ?: DEFAULT_BACKGROUND_SYNC_ENABLED
            val backgroundSyncHours =
                (prefs[LedgerPreferences.BACKGROUND_SYNC_HOURS] ?: DEFAULT_BACKGROUND_SYNC_HOURS.toLong()).toInt()
            _uiState.value = SettingsUiState(
                connected = connected,
                accountNames = accountNames,
                loading = false,
                bridgeError = bridgeError,
                backgroundSyncEnabled = backgroundSyncEnabled,
                backgroundSyncHours = backgroundSyncHours,
            )
        }
    }

    /** Persists the background-sync toggle + interval and reflects them in [uiState]. The
     * Screen is responsible for calling `LightWork.enqueuePeriodic`/`cancel` against
     * [dev.tyler.lightledger.data.SIMPLEFIN_PERIODIC_JOB_KEY] in response (Task 4) — this VM only
     * owns the DataStore-backed preference, staying Android/LightWork-free. */
    fun setBackgroundSync(enabled: Boolean, hours: Int) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[LedgerPreferences.BACKGROUND_SYNC_ENABLED] = enabled
                prefs[LedgerPreferences.BACKGROUND_SYNC_HOURS] = hours.toLong()
            }
            _uiState.value = _uiState.value.copy(backgroundSyncEnabled = enabled, backgroundSyncHours = hours)
        }
    }

    /** Clears the encrypted blob + sync watermarks/error and deletes all SIMPLEFIN accounts/
     * transactions, then reflects the not-connected state in [uiState]. */
    fun disconnect() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs.remove(LedgerPreferences.ACCESS_BLOB)
                prefs.remove(LedgerPreferences.LAST_SYNC_EPOCH_MS)
                prefs.remove(LedgerPreferences.SYNC_START_EPOCH_S)
                prefs.remove(LedgerPreferences.LAST_ERROR)
                prefs.remove(LedgerPreferences.BACKGROUND_SYNC_ENABLED)
                prefs.remove(LedgerPreferences.BACKGROUND_SYNC_HOURS)
            }
            repository.deleteSimpleFinData()
            _uiState.value = SettingsUiState(
                connected = false,
                accountNames = emptyList(),
                loading = false,
                bridgeError = null,
                backgroundSyncEnabled = DEFAULT_BACKGROUND_SYNC_ENABLED,
                backgroundSyncHours = DEFAULT_BACKGROUND_SYNC_HOURS,
            )
        }
    }
}
