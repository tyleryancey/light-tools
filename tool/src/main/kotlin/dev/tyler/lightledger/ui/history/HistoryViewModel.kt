package dev.tyler.lightledger.ui.history

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import dev.tyler.lightledger.data.Category
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.Transaction
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val month: YearMonth = YearMonth.now(),
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedTransactionId: Long? = null,
)

class HistoryViewModel(private val repository: LedgerRepository) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                transactions = repository.listTransactions(_uiState.value.month),
                categories = repository.listCategories(),
            )
        }
    }

    fun showPreviousMonth() {
        _uiState.value = _uiState.value.copy(month = _uiState.value.month.minusMonths(1))
        reload()
    }

    fun showNextMonth() {
        _uiState.value = _uiState.value.copy(month = _uiState.value.month.plusMonths(1))
        reload()
    }

    fun openDetail(id: Long) {
        _uiState.value = _uiState.value.copy(selectedTransactionId = id)
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(selectedTransactionId = null)
    }

    fun updateCategory(categoryId: Long) {
        val id = _uiState.value.selectedTransactionId ?: return
        viewModelScope.launch {
            repository.updateTransactionCategory(id, categoryId)
            reload()
        }
    }

    fun deleteSelected() {
        val id = _uiState.value.selectedTransactionId ?: return
        viewModelScope.launch {
            repository.deleteTransaction(id)
            _uiState.value = _uiState.value.copy(selectedTransactionId = null)
            reload()
        }
    }

    override fun onBackPressed(): Boolean {
        if (_uiState.value.selectedTransactionId != null) {
            closeDetail()
            return true
        }
        return false
    }
}
