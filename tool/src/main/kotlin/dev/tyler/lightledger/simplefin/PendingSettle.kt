package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.data.TransactionStatus
import dev.tyler.lightledger.data.TxnRef
import kotlin.math.abs

/** One reconciliation decision produced by [PendingSettle.plan] for a single stale pending row. */
sealed interface SettleOp {
    /**
     * The bank re-posted [oldId]'s transaction under a new external id, persisted as [newId].
     * Carry the user's category forward onto the settled row, then delete the stale pending row.
     */
    data class MigrateThenDelete(val oldId: Long, val newId: Long, val categoryId: Long) : SettleOp

    /** [oldId] vanished from the feed with no reconcilable settled row (or the match was
     * uncategorized / not user-confirmed, or the only same-amount ±windowDays candidate was
     * already categorized and so is never a valid migrate target — see [PendingSettle.plan]'s
     * I-1 KDoc) — just delete the stale pending row. */
    data class DeleteStale(val oldId: Long) : SettleOp
}

/**
 * Pure, Android-free pending-settle reconciliation engine (CLAUDE-light-ledger.md §6.2.6).
 *
 * Banks commonly re-post a *pending* transaction under a brand-new external id once it settles.
 * Without reconciliation, the ledger keeps both the stale pending row and the new settled row —
 * a visible duplicate that also orphans whatever category the user had already applied to the
 * pending row. This function decides, per already-persisted pending row, whether to migrate its
 * category onto the matching settled row and delete the pending row (`MigrateThenDelete`), or to
 * simply delete the pending row because it can no longer be reconciled (`DeleteStale`).
 *
 * This is a SEPARATE pass over already-persisted rows — see [SyncEngine]'s KDoc, which forbids
 * doing this reconciliation inside the per-fetch decision. The caller (Task 6's sync runner) is
 * responsible for pre-filtering `pending` and `settledCandidates` to a single account and for
 * actually applying the returned ops.
 *
 * I-1 (final-review): a migrate target must be uncategorized. A genuine bank re-post always lands
 * uncategorized (freshly synced, `NEEDS_REVIEW`), so restricting migration to uncategorized
 * candidates costs nothing for the real case — but it protects a user's existing category on a
 * *coincidentally* same-amount, same-window settled row from being silently overwritten by
 * `confirmReview(newId, categoryId)` in the runner.
 */
object PendingSettle {
    fun plan(
        pending: List<TxnRef>,
        settledCandidates: List<TxnRef>,
        fetchedExternalIds: Set<String>,
        fetchStartEpochDay: Long,
        syncEpochDay: Long,
        ageDays: Long = 5,
        windowDays: Long = 4,
    ): List<SettleOp> {
        // Mutable pool: a settled candidate is removed once it's consumed by a MigrateThenDelete,
        // so a second pending row can never migrate onto the same settled row.
        val candidatePool = settledCandidates.toMutableList()
        val ops = mutableListOf<SettleOp>()

        for (p in pending.sortedBy { it.id }) {
            val aged = p.postedEpochDay <= syncEpochDay - ageDays
            val fetchCovered = p.postedEpochDay >= fetchStartEpochDay
            val vanished = p.externalId != null && p.externalId !in fetchedExternalIds
            if (!aged || !fetchCovered || !vanished) continue

            val match = candidatePool
                .filter { s ->
                    s.amountMinor == p.amountMinor &&
                        abs(s.postedEpochDay - p.postedEpochDay) <= windowDays &&
                        s.id != p.id &&
                        s.categoryId == null // I-1: never migrate onto (and thus overwrite) an already-categorized row
                }
                .minWithOrNull(compareBy({ s -> abs(s.postedEpochDay - p.postedEpochDay) }, { s -> s.id }))

            val categoryId = p.categoryId
            ops += if (match != null && p.status == TransactionStatus.CONFIRMED && categoryId != null) {
                candidatePool.remove(match)
                SettleOp.MigrateThenDelete(oldId = p.id, newId = match.id, categoryId = categoryId)
            } else {
                SettleOp.DeleteStale(oldId = p.id)
            }
        }

        return ops
    }
}
