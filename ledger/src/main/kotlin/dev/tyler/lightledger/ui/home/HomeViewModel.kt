package dev.tyler.lightledger.ui.home

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.lightledger.data.CategoryMonthTotal
import dev.tyler.lightledger.data.LedgerRepository
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val month: YearMonth = YearMonth.now(),
    val categoryTotals: List<CategoryMonthTotal> = emptyList(),
    val needsReviewCount: Int = 0,
    val loading: Boolean = true,
)

class HomeViewModel(private val repository: LedgerRepository) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
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
}

fun totalSpentMinor(totals: List<CategoryMonthTotal>): Long =
    totals.filter { it.totalMinor < 0L }.sumOf { -it.totalMinor }
