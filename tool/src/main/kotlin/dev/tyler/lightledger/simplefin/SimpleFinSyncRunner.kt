package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.TxnRef
import kotlinx.coroutines.CancellationException

/** Cross-source dedup candidates are probed within this many days of the incoming txn's
 * posted day (a bank's SIMPLEFIN post date and a manually-entered date rarely land on the
 * exact same day). */
private const val CROSS_SOURCE_WINDOW_DAYS = 1L

/** A pending row is eligible for pending-settle reconciliation once it's this many days old
 * relative to the current sync's epoch day (CLAUDE-light-ledger.md §6.2.6). */
private const val PENDING_AGE_DAYS = 5L

/** A stale pending row's settled replacement is searched for within this many days of the
 * pending row's own posted day. */
private const val PENDING_WINDOW_DAYS = 4L

/**
 * Outcome of one [SimpleFinSyncRunner.sync] pass: the number of brand-new transactions
 * inserted, plus any non-fatal `errors` SimpleFIN reported alongside a 200 (subscription
 * lapses / "reauthenticate", §6.1.4). Persisting/surfacing those errors to the user is T7 —
 * this type just carries them out of the runner so the caller can decide.
 */
data class SyncOutcome(val newCount: Int, val errors: List<String>)

/**
 * Orchestrates one SimpleFIN sync pass (CLAUDE-light-ledger.md §6.2 steps 3-6) against an
 * already-fetched [SimpleFinApi] and an injected [LedgerRepository] — pure orchestration,
 * no Android/SealedLightContext, so it's directly unit-testable with fakes. The `@LightJob`
 * handler in LedgerJobs.kt owns everything this class doesn't: decrypting the Access URL,
 * computing the watermark, and persisting `simplefin_last_sync_epoch_ms` /
 * `simplefin_sync_start_epoch_s` after a successful run.
 *
 * Implements both the per-fetch decision pass (§6.2 steps 4-5, via [SyncEngine]) and the
 * §6.2.6 pending-settle churn pass (via [PendingSettle]) as two separate passes per account,
 * the latter running only after the former's rows are persisted.
 */
class SimpleFinSyncRunner(
    private val repo: LedgerRepository,
    private val api: SimpleFinApi,
) {
    /**
     * Fetches [accessUrl] starting from [startEpochS], applies the resulting [SyncPlan] and
     * pending-settle reconciliation for every account, and returns a [SyncOutcome]. [syncEpochDay]
     * is the caller's notion of "today" (injected, not read from the system clock here, to keep
     * this class pure/testable) and drives pending-row staleness. A fetch failure is returned
     * unchanged (preserving [SimpleFinHttpException]/IO/Cancellation) so the caller can classify
     * it (Retry vs. Error); a persist failure is likewise returned as a failed [Result] rather
     * than thrown, so a transient DB error becomes Retry, not a permanent Error.
     */
    suspend fun sync(accessUrl: String, startEpochS: Long, syncEpochDay: Long): Result<SyncOutcome> {
        val accountSet = api.fetch(accessUrl, startEpochS).getOrElse { return Result.failure(it) }
        val fetchStartEpochDay = java.time.Instant.ofEpochSecond(startEpochS)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()

        // The persist phase can throw on a transient DB error (SQLite locked / disk full).
        // Return it as a failed Result so the @LightJob classifies it as Retry rather than a
        // permanent Error; CancellationException must still propagate for structured concurrency.
        return try {
            val insertedCount = applyAccounts(accountSet, fetchStartEpochDay, syncEpochDay)
            Result.success(SyncOutcome(insertedCount, accountSet.errors))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Applies the per-account [SyncPlan] and pending-settle pass; returns the count of
     * brand-new inserts across all accounts. */
    private suspend fun applyAccounts(accountSet: AccountSet, fetchStartEpochDay: Long, syncEpochDay: Long): Int {
        var insertedCount = 0
        for (account in accountSet.accounts) {
            // Same dbId feeds the mapper's dedup-hash inputs AND the lookups below — the
            // hash must be computed against the account's *local* row id, not its external
            // one, or dedup candidates found here would never match rows inserted earlier.
            val dbId = repo.upsertSimpleFinAccount(account.id, account.name, account.currency)
            val mapped = SimpleFinMapper.toMappedTransactions(account)

            val externalById: Map<String, TxnRef> = mapped.mapNotNull { txn ->
                repo.findTransactionByExternal(dbId, txn.externalId)?.let { txn.externalId to it }
            }.toMap()

            val rules = repo.listRules()

            // SyncEngine.plan's dedupLookup is a plain (non-suspend) function type, so the
            // suspend repo call must happen out here, before plan() runs, and be handed to the
            // lambda as a synchronous map lookup. Keyed by externalId (unique within one
            // account's fetch) rather than the old exact dedup hash — this is the
            // account-agnostic cross-source query: a MANUAL/CSV row for the same purchase,
            // entered under a different account, is still a valid candidate. SyncEngine itself
            // further filters by non-SIMPLEFIN source, day proximity, and normalized payee.
            val crossSourceCandidatesByExternalId: Map<String, List<TxnRef>> = mapped.associate { txn ->
                txn.externalId to repo.findCrossSourceDedupCandidates(
                    txn.amountMinor,
                    txn.postedEpochDay - CROSS_SOURCE_WINDOW_DAYS,
                    txn.postedEpochDay + CROSS_SOURCE_WINDOW_DAYS,
                )
            }

            val plan = SyncEngine.plan(
                accountId = dbId,
                incoming = mapped,
                externalLookup = { externalById[it] },
                dedupLookup = { txn -> crossSourceCandidatesByExternalId[txn.externalId] ?: emptyList() },
                rules = rules,
            )

            for (op in plan.ops) {
                when (op) {
                    is SyncEngine.SyncOp.Insert -> {
                        repo.insertExternalTransaction(
                            accountId = dbId,
                            postedEpochDay = op.postedEpochDay,
                            amountMinor = op.amountMinor,
                            payee = op.payee,
                            memo = op.memo,
                            categoryId = op.categoryId,
                            status = op.status,
                            externalId = op.externalId,
                            pendingExternal = op.pendingExternal,
                            dedupHash = op.dedupHash,
                        )
                        insertedCount++
                    }

                    is SyncEngine.SyncOp.UpdateExternalFields -> repo.updateExternalTransactionFields(
                        id = op.id,
                        amountMinor = op.amountMinor,
                        payee = op.payee,
                        postedEpochDay = op.postedEpochDay,
                        pendingExternal = op.pendingExternal,
                    )

                    is SyncEngine.SyncOp.LinkCrossSource -> repo.adoptExternalId(op.existingId, op.externalId)
                }
            }

            // Pending-settle pass (§6.2.6) — a SEPARATE pass over already-persisted rows, run
            // only after this account's fetch-decision ops above are applied. Banks commonly
            // re-post a pending transaction under a brand-new external id once it settles; this
            // reconciles the stale pending row against the new settled one so the ledger doesn't
            // keep a visible duplicate or orphan the user's category on the pending row.
            val fetchedExternalIds = mapped.map { it.externalId }.toSet()
            val stale = repo.listStalePendingExternal(dbId, syncEpochDay - PENDING_AGE_DAYS)
            val candidates = stale.flatMap { p ->
                repo.findSettledMatches(
                    dbId,
                    p.amountMinor,
                    p.postedEpochDay - PENDING_WINDOW_DAYS,
                    p.postedEpochDay + PENDING_WINDOW_DAYS,
                )
            }.distinctBy { it.id }
            val settleOps = PendingSettle.plan(stale, candidates, fetchedExternalIds, fetchStartEpochDay, syncEpochDay)

            for (op in settleOps) {
                when (op) {
                    is SettleOp.MigrateThenDelete -> {
                        repo.confirmReview(op.newId, op.categoryId)
                        repo.deleteTransaction(op.oldId)
                    }

                    is SettleOp.DeleteStale -> repo.deleteTransaction(op.oldId)
                }
            }
        }

        return insertedCount
    }
}
