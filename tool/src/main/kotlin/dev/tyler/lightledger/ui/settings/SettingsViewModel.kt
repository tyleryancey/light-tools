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

data class SettingsUiState(
    val connected: Boolean = false,
    val accountNames: List<String> = emptyList(),
    val loading: Boolean = true,
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
            val connected = dataStore.data.first()[LedgerPreferences.ACCESS_BLOB] != null
            val accountNames = repository.listSimpleFinAccounts()
            _uiState.value = SettingsUiState(connected = connected, accountNames = accountNames, loading = false)
        }
    }

    /** Clears the encrypted blob + sync watermarks and deletes all SIMPLEFIN accounts/
     * transactions, then reflects the not-connected state in [uiState]. */
    fun disconnect() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs.remove(LedgerPreferences.ACCESS_BLOB)
                prefs.remove(LedgerPreferences.LAST_SYNC_EPOCH_MS)
                prefs.remove(LedgerPreferences.SYNC_START_EPOCH_S)
            }
            repository.deleteSimpleFinData()
            _uiState.value = SettingsUiState(connected = false, accountNames = emptyList(), loading = false)
        }
    }
}
