package dev.tyler.lightledger.data

data class Category(val id: Long, val name: String, val sortOrder: Int)

data class Transaction(
    val id: Long,
    val accountId: Long,
    val postedEpochDay: Long,
    val amountMinor: Long,
    val payee: String,
    val memo: String,
    val categoryId: Long?,
)

data class CategoryMonthTotal(
    val categoryId: Long,
    val categoryName: String,
    val totalMinor: Long,
    val currency: String,
)

/** A single row in the review inbox (NEEDS_REVIEW transaction + its account's display name). */
data class ReviewItem(
    val id: Long,
    val postedEpochDay: Long,
    val amountMinor: Long,
    val payee: String,
    val accountName: String,
    val categoryId: Long?,
)

/**
 * Richer transaction row for sync-decision lookups (dedup / cross-source linking).
 * Android-free; deliberately not the lean [Transaction] domain model used by the UI.
 */
data class TxnRef(
    val id: Long,
    val accountId: Long,
    val source: String,
    val status: String,
    val categoryId: Long?,
    val externalId: String?,
    val postedEpochDay: Long,
    val amountMinor: Long,
    val payee: String,
    val pendingExternal: Boolean,
)
