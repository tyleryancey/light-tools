package dev.tyler.lightledger.domain

data class CategoryRule(
    val id: Long,
    val payeeContains: String,
    val categoryId: Long,
    val enabled: Boolean,
)

data class PastConfirmation(val normalizedPayee: String, val categoryId: Long)

object RuleEngine {
    fun matchCategory(rules: List<CategoryRule>, payee: String): Long? {
        val normalized = payee.lowercase()
        return rules.firstOrNull { it.enabled && normalized.contains(it.payeeContains) }?.categoryId
    }

    fun shouldCreateRule(
        pastConfirmations: List<PastConfirmation>,
        normalizedPayee: String,
        categoryId: Long,
        threshold: Int = 3,
    ): Boolean {
        val matches = pastConfirmations.count {
            it.normalizedPayee == normalizedPayee && it.categoryId == categoryId
        }
        return matches >= threshold
    }
}
