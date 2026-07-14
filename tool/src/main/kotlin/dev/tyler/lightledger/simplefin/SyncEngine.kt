package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.data.TransactionSource
import dev.tyler.lightledger.data.TransactionStatus
import dev.tyler.lightledger.data.TxnRef
import dev.tyler.lightledger.domain.CategoryRule
import dev.tyler.lightledger.domain.DedupHash
import dev.tyler.lightledger.domain.RuleEngine
import kotlin.math.abs

/** The result of planning a sync: an ordered list of operations to apply. */
data class SyncPlan(val ops: List<SyncEngine.SyncOp>)

/**
 * Pure, Android-free sync-decision engine (CLAUDE-light-ledger.md §6.2 steps 4-5 plus the
 * cross-source dedup rule). Given the mapped rows fetched for one account and injected lookups
 * for existing state, produces a deterministic [SyncPlan] of insert/update/link operations.
 *
 * Deliberately excludes the §6.2.6 pending-settle churn migration — that is M3b scope and must
 * run as a separate pass over already-persisted rows, not inside this per-fetch decision.
 */
object SyncEngine {
    private const val CROSS_SOURCE_WINDOW_DAYS = 1L

    sealed class SyncOp {
        data class Insert(
            val externalId: String,
            val postedEpochDay: Long,
            val amountMinor: Long,
            val payee: String,
            val memo: String,
            val categoryId: Long?,
            val status: String,
            val pendingExternal: Boolean,
            val dedupHash: String,
        ) : SyncOp()

        /** Bank revised a known transaction's mutable fields; never touches category/status. */
        data class UpdateExternalFields(
            val id: Long,
            val amountMinor: Long,
            val payee: String,
            val postedEpochDay: Long,
            val pendingExternal: Boolean,
        ) : SyncOp()

        /** Cross-source dedup: adopt externalId onto an existing MANUAL/CSV row. */
        data class LinkCrossSource(
            val existingId: Long,
            val externalId: String,
        ) : SyncOp()
    }

    fun plan(
        accountId: Long,
        incoming: List<MappedExternalTxn>,
        externalLookup: (externalId: String) -> TxnRef?,
        dedupLookup: (txn: MappedExternalTxn) -> List<TxnRef>,
        rules: List<CategoryRule>,
    ): SyncPlan {
        val ops = incoming.map { txn ->
            val existingByExternal = externalLookup(txn.externalId)
            if (existingByExternal != null) {
                return@map SyncOp.UpdateExternalFields(
                    id = existingByExternal.id,
                    amountMinor = txn.amountMinor,
                    payee = txn.payee,
                    postedEpochDay = txn.postedEpochDay,
                    pendingExternal = txn.pending,
                )
            }

            // Stored hash stays account-scoped (persisted on Insert below) — only the
            // *lookup* is account-agnostic, via dedupLookup(txn) below.
            val dedupHash = DedupHash.compute(accountId, txn.postedEpochDay, txn.amountMinor, txn.payee)

            val crossSourceMatch = dedupLookup(txn)
                .filter { it.source != TransactionSource.SIMPLEFIN }
                .filter { abs(it.postedEpochDay - txn.postedEpochDay) <= CROSS_SOURCE_WINDOW_DAYS }
                // The lookup is now amount+day only (account-agnostic), so a hash match no
                // longer implies identical normalized payee — re-check it explicitly.
                .filter { DedupHash.normalizePayee(it.payee) == DedupHash.normalizePayee(txn.payee) }
                .minWithOrNull(
                    compareBy(
                        { abs(it.postedEpochDay - txn.postedEpochDay) },
                        { it.id },
                    ),
                )

            if (crossSourceMatch != null) {
                return@map SyncOp.LinkCrossSource(
                    existingId = crossSourceMatch.id,
                    externalId = txn.externalId,
                )
            }

            val matchedCategoryId = RuleEngine.matchCategory(rules, txn.payee)
            SyncOp.Insert(
                externalId = txn.externalId,
                postedEpochDay = txn.postedEpochDay,
                amountMinor = txn.amountMinor,
                payee = txn.payee,
                memo = txn.memo,
                categoryId = matchedCategoryId,
                status = if (matchedCategoryId != null) TransactionStatus.CONFIRMED else TransactionStatus.NEEDS_REVIEW,
                pendingExternal = txn.pending,
                dedupHash = dedupHash,
            )
        }
        return SyncPlan(ops)
    }
}
