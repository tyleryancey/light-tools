package dev.tyler.lightledger.ui.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.lightledger.data.CategoryMonthTotal
import dev.tyler.lightledger.data.LedgerPreferences
import dev.tyler.lightledger.data.LedgerRepository
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Opportunistic Home-open sync is only worth triggering if the last one is more than this
 * stale — keeps a rapid succession of Home visits from re-enqueuing the sync job every time. */
private const val OPPORTUNISTIC_SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000

data class HomeUiState(
    val month: YearMonth = YearMonth.now(),
    val categoryTotals: List<CategoryMonthTotal> = emptyList(),
    val needsReviewCount: Int = 0,
    val loading: Boolean = true,
    /** Bumped once per [HomeViewModel.onScreenShow] — Home isn't remounted on app-resume-while-
     *  on-screen, so the Screen keys its opportunistic-sync `LaunchedEffect` on this tick
     *  (instead of `Unit`) to re-run the sync-due check on every show, not just first mount. */
    val onShowTick: Int = 0,
)

/**
 * Deliberately Android/LightWork-free so it's JVM-unit-testable; the Screen owns
 * enqueue/observe of the sync job itself via `lightContext` (see [isOpportunisticSyncDue]).
 */
class HomeViewModel(
    private val repository: LedgerRepository,
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        _uiState.value = _uiState.value.copy(onShowTick = _uiState.value.onShowTick + 1)
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            repository.ensureSeeded()
            val month = _uiState.value.month
            _uiState.value = _uiState.value.copy(
                categoryTotals = repository.monthSummary(month),
                needsReviewCount = repository.needsReviewCount(),
                loading = false,
            )
        }
    }

    /** Opportunistic-sync gate: sync only if SimpleFIN is connected (blob present) and either
     *  we've never recorded a sync or the last one is older than [OPPORTUNISTIC_SYNC_INTERVAL_MS]. */
    suspend fun isOpportunisticSyncDue(nowMs: Long): Boolean {
        val prefs = dataStore.data.first()
        if (prefs[LedgerPreferences.ACCESS_BLOB] == null) return false
        val lastSync = prefs[LedgerPreferences.LAST_SYNC_EPOCH_MS] ?: return true
        return nowMs - lastSync > OPPORTUNISTIC_SYNC_INTERVAL_MS
    }
}
