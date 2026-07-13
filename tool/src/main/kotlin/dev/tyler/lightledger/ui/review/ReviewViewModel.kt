package dev.tyler.lightledger.ui.review

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import dev.tyler.lightledger.data.Category
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.ReviewItem
import dev.tyler.lightledger.domain.DedupHash
import dev.tyler.lightledger.domain.RuleEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReviewUiState(
    val item: ReviewItem? = null,
    val remaining: Int = 0,
    val categories: List<Category> = emptyList(),
    val done: Boolean = false,
)

/**
 * Review-inbox screen logic: walks NEEDS_REVIEW transactions one at a time, confirming or
 * skipping each. Confirming also runs the "3-strike" rule-learning check: once the same
 * normalized payee has been confirmed to the same category 3 times, a [dev.tyler.lightledger.domain.CategoryRule]
 * is inserted silently so future imports auto-categorize.
 */
class ReviewViewModel(private val repository: LedgerRepository) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    /** Local working copy of the inbox; items are removed as they're confirmed or skipped. */
    private var queue: List<ReviewItem> = emptyList()

    init {
        reload()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        reload()
    }

    override fun onBackPressed(): Boolean = false

    fun reload() {
        viewModelScope.launch {
            repository.ensureSeeded()
            val categories = repository.listCategories()
            queue = repository.listReviewInbox()
            _uiState.value = ReviewUiState(
                item = queue.firstOrNull(),
                remaining = queue.size,
                categories = categories,
                done = queue.isEmpty(),
            )
        }
    }

    fun confirm(categoryId: Long) {
        val item = _uiState.value.item ?: return
        viewModelScope.launch {
            repository.confirmReview(item.id, categoryId)
            maybeLearnRule(item, categoryId)
            advance(item.id)
        }
    }

    fun skip() {
        val item = _uiState.value.item ?: return
        advance(item.id)
    }

    private suspend fun maybeLearnRule(item: ReviewItem, categoryId: Long) {
        val normalized = DedupHash.normalizePayee(item.payee)
        // Guard: an all-digit payee (e.g. "1234") normalizes to "" — a rule with an empty
        // payeeContains would match every payee via String.contains(""), so never create one.
        if (normalized.isBlank()) return

        val past = repository.pastConfirmations()
        if (!RuleEngine.shouldCreateRule(past, normalized, categoryId)) return

        // Guard: don't insert a duplicate rule on the 4th+ confirm once one already covers this
        // normalized payee + category pair.
        val alreadyCovered = repository.listRules()
            .any { it.payeeContains == normalized && it.categoryId == categoryId }
        if (alreadyCovered) return

        repository.insertRule(normalized, categoryId)
    }

    private fun advance(processedId: Long) {
        queue = queue.filterNot { it.id == processedId }
        _uiState.value = _uiState.value.copy(
            item = queue.firstOrNull(),
            remaining = queue.size,
            done = queue.isEmpty(),
        )
    }
}
