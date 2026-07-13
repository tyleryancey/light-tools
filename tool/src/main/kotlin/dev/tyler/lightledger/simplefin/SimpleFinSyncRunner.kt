package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.TxnRef
import dev.tyler.lightledger.domain.DedupHash

/**
 * Orchestrates one SimpleFIN sync pass (CLAUDE-light-ledger.md §6.2 steps 3-5) against an
 * already-fetched [SimpleFinApi] and an injected [LedgerRepository] — pure orchestration,
 * no Android/SealedLightContext, so it's directly unit-testable with fakes. The `@LightJob`
 * handler in LedgerJobs.kt owns everything this class doesn't: decrypting the Access URL,
 * computing the watermark, and persisting `simplefin_last_sync_epoch_ms` /
 * `simplefin_sync_start_epoch_s` after a successful run.
 *
 * Deliberately excludes §6.2.6 pending-settle churn — that's M3b scope.
 */
class SimpleFinSyncRunner(
    private val repo: LedgerRepository,
    private val api: SimpleFinApi,
) {
    /**
     * Fetches [accessUrl] starting from [startEpochS], applies the resulting [SyncPlan] for
     * every account, and returns the number of brand-new transactions inserted. A fetch
     * failure is returned unchanged (preserving [SimpleFinHttpException]/IO/Cancellation) so
     * the caller can classify it (Retry vs. Error).
     */
    suspend fun sync(accessUrl: String, startEpochS: Long): Result<Int> {
        val accountSet = api.fetch(accessUrl, startEpochS).getOrElse { return Result.failure(it) }

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

            val byDedupHash: Map<String, List<TxnRef>> = mapped.associate { txn ->
                val hash = DedupHash.compute(dbId, txn.postedEpochDay, txn.amountMinor, txn.payee)
                hash to repo.findDedupCandidates(hash)
            }

            val rules = repo.listRules()

            val plan = SyncEngine.plan(
                accountId = dbId,
                incoming = mapped,
                externalLookup = { externalById[it] },
                dedupLookup = { byDedupHash[it] ?: emptyList() },
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
        }

        // accountSet.errors (subscription lapses etc., §6.1.4) are surfaced to Settings by a
        // later task, not here — non-fatal per §6.2, so a non-empty list never fails the sync.
        return Result.success(insertedCount)
    }
}
