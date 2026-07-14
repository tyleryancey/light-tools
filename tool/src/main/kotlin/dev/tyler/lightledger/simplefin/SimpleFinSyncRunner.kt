package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.TxnRef
import dev.tyler.lightledger.domain.DedupHash
import kotlinx.coroutines.CancellationException

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
     * the caller can classify it (Retry vs. Error); a persist failure is likewise returned as
     * a failed [Result] rather than thrown, so a transient DB error becomes Retry, not a
     * permanent Error.
     */
    suspend fun sync(accessUrl: String, startEpochS: Long): Result<Int> {
        val accountSet = api.fetch(accessUrl, startEpochS).getOrElse { return Result.failure(it) }

        // The persist phase can throw on a transient DB error (SQLite locked / disk full).
        // Return it as a failed Result so the @LightJob classifies it as Retry rather than a
        // permanent Error; CancellationException must still propagate for structured concurrency.
        return try {
            Result.success(applyAccounts(accountSet))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Applies the per-account [SyncPlan]s and returns the count of brand-new inserts. */
    private suspend fun applyAccounts(accountSet: AccountSet): Int {
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

            // M3a caveat: candidates are looked up by *exact* hash, and DedupHash.compute folds
            // in this SIMPLEFIN account's local dbId — so a MANUAL/CSV row (hashed under its own
            // account id) can never collide here. That makes SyncEngine's LinkCrossSource branch
            // inert in production: M3a performs exact-day, same-account dedup only. True
            // cross-source dedup (account-agnostic / adjacent-day probing) is deferred to M3b;
            // SyncEngineTest exercises LinkCrossSource with a hand-injected candidate the real
            // repo would not yet return, so that decision logic is unit-covered ahead of M3b.
            //
            // M3b/T5 note: SyncEngine.plan's dedupLookup signature changed to take the incoming
            // MappedExternalTxn (account-agnostic lookup contract) so LedgerRepository can offer
            // findCrossSourceDedupCandidates. This runner is NOT yet wired to that new query —
            // that's M3b/T6 — so this lambda just recomputes the same account-scoped hash used to
            // build byDedupHash below, keeping this call site's behavior byte-identical to before.
            val byDedupHash: Map<String, List<TxnRef>> = mapped.associate { txn ->
                val hash = DedupHash.compute(dbId, txn.postedEpochDay, txn.amountMinor, txn.payee)
                hash to repo.findDedupCandidates(hash)
            }

            val rules = repo.listRules()

            val plan = SyncEngine.plan(
                accountId = dbId,
                incoming = mapped,
                externalLookup = { externalById[it] },
                dedupLookup = { txn ->
                    val hash = DedupHash.compute(dbId, txn.postedEpochDay, txn.amountMinor, txn.payee)
                    byDedupHash[hash] ?: emptyList()
                },
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

        // accountSet.errors (subscription lapses / "reauthenticate", §6.1.4) are non-fatal per
        // §6.2, so a non-empty list never fails the sync. Surfacing them to the user as a
        // reconnect prompt is deferred to M3b — today they are decoded but intentionally not
        // acted on, so a 200-with-errors syncs no new data without a visible warning.
        return insertedCount
    }
}
