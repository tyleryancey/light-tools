package dev.tyler.lightledger.data

import dev.tyler.lightledger.domain.CategoryRule
import dev.tyler.lightledger.domain.PastConfirmation
import java.time.YearMonth

interface LedgerRepository {
    suspend fun ensureSeeded()
    suspend fun listCategories(): List<Category>
    suspend fun addCategory(name: String): Category
    suspend fun renameCategory(id: Long, name: String)
    suspend fun archiveCategory(id: Long)
    suspend fun addManualTransaction(amountMinor: Long, payee: String, categoryId: Long, memo: String = ""): Long
    suspend fun monthSummary(month: YearMonth): List<CategoryMonthTotal>
    suspend fun listTransactions(month: YearMonth): List<Transaction>
    suspend fun getTransaction(id: Long): Transaction?
    suspend fun updateTransactionCategory(id: Long, categoryId: Long)
    suspend fun updateTransactionMemo(id: Long, memo: String)
    suspend fun deleteTransaction(id: Long)
    suspend fun needsReviewCount(): Int

    // --- SimpleFIN sync + review inbox (M3a) ---

    /** Find-or-update-then-insert a SIMPLEFIN account by its external id; returns the account id. */
    suspend fun upsertSimpleFinAccount(externalId: String, name: String, currency: String): Long

    /** Look up an existing row already linked to this account+externalId (idempotent re-sync). */
    suspend fun findTransactionByExternal(accountId: Long, externalId: String): TxnRef?

    /** Insert a brand-new externally sourced (SIMPLEFIN) transaction; returns the new id. */
    suspend fun insertExternalTransaction(
        accountId: Long,
        postedEpochDay: Long,
        amountMinor: Long,
        payee: String,
        memo: String,
        categoryId: Long?,
        status: String,
        externalId: String,
        pendingExternal: Boolean,
        dedupHash: String,
    ): Long

    /** Refresh the mutable fields of an already-linked external row. Never touches categoryId/status. */
    suspend fun updateExternalTransactionFields(
        id: Long,
        amountMinor: Long,
        payee: String,
        postedEpochDay: Long,
        pendingExternal: Boolean,
    )

    /** Adopt an externalId onto an existing (e.g. manual/CSV) row for cross-source linking. */
    suspend fun adoptExternalId(id: Long, externalId: String)

    /** NEEDS_REVIEW rows, newest first, with their account's display name. */
    suspend fun listReviewInbox(): List<ReviewItem>

    /** Assign a category and mark a NEEDS_REVIEW row CONFIRMED. */
    suspend fun confirmReview(id: Long, categoryId: Long)

    /** All CONFIRMED payee/category pairs (payee normalized) for 3-strike rule learning. */
    suspend fun pastConfirmations(): List<PastConfirmation>

    /** Persist a learned auto-categorize rule; payeeContains is normalized before storage. */
    suspend fun insertRule(payeeContains: String, categoryId: Long)

    /** Enabled auto-categorize rules. */
    suspend fun listRules(): List<CategoryRule>

    /** Remove all SIMPLEFIN accounts and their transactions (Settings "Disconnect & forget"). */
    suspend fun deleteSimpleFinData()

    /** Display names of active (non-archived) SIMPLEFIN accounts, alphabetical (Settings). */
    suspend fun listSimpleFinAccounts(): List<String>

    // --- Pending-settle reconciliation (M3b) ---

    /** Pending-external rows for [accountId] posted on/before [olderThanEpochDay] — feeds [dev.tyler.lightledger.simplefin.PendingSettle.plan]'s `pending` input. */
    suspend fun listStalePendingExternal(accountId: Long, olderThanEpochDay: Long): List<TxnRef>

    /** Settled (non-pending) rows for [accountId] matching [amountMinor] within [minEpochDay]..[maxEpochDay] — feeds [dev.tyler.lightledger.simplefin.PendingSettle.plan]'s `settledCandidates` input. */
    suspend fun findSettledMatches(
        accountId: Long,
        amountMinor: Long,
        minEpochDay: Long,
        maxEpochDay: Long,
    ): List<TxnRef>

    /**
     * Account-agnostic cross-source dedup candidates: non-SIMPLEFIN rows (MANUAL/CSV) matching
     * [amountMinor] and posted within [minEpochDay]..[maxEpochDay], regardless of account. Feeds
     * [dev.tyler.lightledger.simplefin.SyncEngine.plan]'s `dedupLookup` input.
     */
    suspend fun findCrossSourceDedupCandidates(
        amountMinor: Long,
        minEpochDay: Long,
        maxEpochDay: Long,
    ): List<TxnRef>
}
