package dev.tyler.lightledger.ui.addentry

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import dev.tyler.lightledger.data.Category
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.domain.AmountParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AddEntryStep { AMOUNT, PAYEE, CATEGORY }

data class AddEntryUiState(
    val step: AddEntryStep = AddEntryStep.AMOUNT,
    val amountText: String = "",
    val payee: String = "",
    val categories: List<Category> = emptyList(),
    val saved: Boolean = false,
)

class AddEntryViewModel(private val repository: LedgerRepository) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(AddEntryUiState())
    val uiState: StateFlow<AddEntryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
            _uiState.value = _uiState.value.copy(categories = repository.listCategories())
        }
    }

    fun onDigit(digit: String) {
        val current = _uiState.value.amountText
        if (current.substringAfter('.', "").length >= 2) return
        _uiState.value = _uiState.value.copy(amountText = current + digit)
    }

    fun onDecimal() {
        val current = _uiState.value.amountText
        if (current.isEmpty() || current.contains('.')) return
        _uiState.value = _uiState.value.copy(amountText = "$current.")
    }

    fun onBackspace() {
        val current = _uiState.value.amountText
        if (current.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(amountText = current.dropLast(1))
        }
    }

    fun canContinueFromAmount(): Boolean =
        _uiState.value.amountText.isNotEmpty() && parsedAmountMinorOrNull() != null

    fun confirmAmount() {
        if (canContinueFromAmount()) {
            _uiState.value = _uiState.value.copy(step = AddEntryStep.PAYEE)
        }
    }

    fun confirmPayee(payee: String) {
        _uiState.value = _uiState.value.copy(payee = payee, step = AddEntryStep.CATEGORY)
    }

    fun selectCategory(category: Category) {
        val amountMinor = parsedAmountMinorOrNull() ?: return
        viewModelScope.launch {
            repository.addManualTransaction(
                amountMinor = -amountMinor,
                payee = _uiState.value.payee,
                categoryId = category.id,
            )
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }

    override fun onBackPressed(): Boolean {
        if (_uiState.value.saved) return false
        return when (_uiState.value.step) {
            AddEntryStep.PAYEE -> {
                _uiState.value = _uiState.value.copy(step = AddEntryStep.AMOUNT)
                true
            }
            AddEntryStep.CATEGORY -> {
                _uiState.value = _uiState.value.copy(step = AddEntryStep.PAYEE)
                true
            }
            AddEntryStep.AMOUNT -> false
        }
    }

    private fun parsedAmountMinorOrNull(): Long? =
        runCatching { AmountParser.parseToMinorUnits(_uiState.value.amountText) }
            .getOrNull()
            ?.takeIf { it > 0L }
}
