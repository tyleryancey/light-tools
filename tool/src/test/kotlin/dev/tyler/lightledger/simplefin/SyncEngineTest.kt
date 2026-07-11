package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.data.TransactionSource
import dev.tyler.lightledger.data.TransactionStatus
import dev.tyler.lightledger.data.TxnRef
import dev.tyler.lightledger.domain.CategoryRule
import dev.tyler.lightledger.domain.DedupHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val ACCOUNT_ID = 1L

class SyncEngineTest {
    private val noExternal: (String) -> TxnRef? = { null }
    private val noDedup: (String) -> List<TxnRef> = { emptyList() }
    private val noRules = emptyList<CategoryRule>()

    private fun mapped(
        externalId: String = "TRN-1",
        postedEpochDay: Long = 19000L,
        amountMinor: Long = -450L,
        payee: String = "Coffee Shop",
        memo: String = "",
        pending: Boolean = false,
    ) = MappedExternalTxn(externalId, postedEpochDay, amountMinor, payee, memo, pending)

    @Test fun newTransactionWithNoRuleMatchInsertsNeedsReview() {
        val txn = mapped()
        val plan = SyncEngine.plan(ACCOUNT_ID, listOf(txn), noExternal, noDedup, noRules)

        assertEquals(1, plan.ops.size)
        val op = assertIs<SyncEngine.SyncOp.Insert>(plan.ops.single())
        assertEquals(txn.externalId, op.externalId)
        assertEquals(txn.postedEpochDay, op.postedEpochDay)
        assertEquals(txn.amountMinor, op.amountMinor)
        assertEquals(txn.payee, op.payee)
        assertEquals(txn.memo, op.memo)
        assertEquals(null, op.categoryId)
        assertEquals(TransactionStatus.NEEDS_REVIEW, op.status)
        assertEquals(txn.pending, op.pendingExternal)
        assertEquals(
            DedupHash.compute(ACCOUNT_ID, txn.postedEpochDay, txn.amountMinor, txn.payee),
            op.dedupHash,
        )
    }

    @Test fun newTransactionWithRuleMatchInsertsConfirmedWithCategory() {
        val txn = mapped(payee = "Starbucks Coffee")
        val rules = listOf(
            CategoryRule(id = 1L, payeeContains = "starbucks", categoryId = 42L, enabled = true),
        )
        val plan = SyncEngine.plan(ACCOUNT_ID, listOf(txn), noExternal, noDedup, rules)

        val op = assertIs<SyncEngine.SyncOp.Insert>(plan.ops.single())
        assertEquals(42L, op.categoryId)
        assertEquals(TransactionStatus.CONFIRMED, op.status)
    }

    @Test fun existingExternalMatchUpdatesExternalFieldsOnly() {
        val txn = mapped(externalId = "TRN-9", amountMinor = -999L, pending = true)
        val existing = TxnRef(
            id = 77L,
            accountId = ACCOUNT_ID,
            source = TransactionSource.SIMPLEFIN,
            status = TransactionStatus.CONFIRMED,
            categoryId = 5L,
            externalId = "TRN-9",
            postedEpochDay = 18000L,
            amountMinor = -100L,
            payee = "Old Payee",
            pendingExternal = false,
        )
        val externalLookup: (String) -> TxnRef? = { id -> if (id == "TRN-9") existing else null }
        val dedupHash = DedupHash.compute(ACCOUNT_ID, txn.postedEpochDay, txn.amountMinor, txn.payee)
        // A row distinct from `existing` that WOULD independently qualify as a cross-source
        // LinkCrossSource candidate: different id, non-SimpleFIN source, and within the 1-day
        // window. This proves rule 1 (external match) is checked before rule 2 (cross-source
        // dedup) actually runs — not merely that rule 2's own source/window guards happen to
        // reject this particular fake. If the engine's branch order were swapped, this test
        // would now fail with a LinkCrossSource op instead of UpdateExternalFields.
        val crossSourceCandidate = TxnRef(
            id = 88L,
            accountId = ACCOUNT_ID,
            source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED,
            categoryId = null,
            externalId = null,
            postedEpochDay = txn.postedEpochDay,
            amountMinor = txn.amountMinor,
            payee = txn.payee,
            pendingExternal = false,
        )
        val dedupLookup: (String) -> List<TxnRef> =
            { hash -> if (hash == dedupHash) listOf(crossSourceCandidate) else emptyList() }
        val rules = listOf(
            CategoryRule(id = 1L, payeeContains = "coffee", categoryId = 42L, enabled = true),
        )

        val plan = SyncEngine.plan(ACCOUNT_ID, listOf(txn), externalLookup, dedupLookup, rules)

        val op = assertIs<SyncEngine.SyncOp.UpdateExternalFields>(plan.ops.single())
        assertEquals(77L, op.id)
        assertEquals(txn.amountMinor, op.amountMinor)
        assertEquals(txn.payee, op.payee)
        assertEquals(txn.postedEpochDay, op.postedEpochDay)
        assertEquals(txn.pending, op.pendingExternal)
        // UpdateExternalFields structurally has no categoryId/status parameter (compile-time
        // guarantee, not a runtime check): its constructor is (id, amountMinor, payee,
        // postedEpochDay, pendingExternal) only, so category/status can never be touched here.
    }

    @Test fun crossSourceDedupWithinOneDayLinksToExistingManualRow() {
        val txn = mapped(externalId = "TRN-5", postedEpochDay = 19000L, amountMinor = -450L, payee = "Coffee Shop")
        val dedupHash = DedupHash.compute(ACCOUNT_ID, txn.postedEpochDay, txn.amountMinor, txn.payee)
        val manualRow = TxnRef(
            id = 30L,
            accountId = ACCOUNT_ID,
            source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED,
            categoryId = 7L,
            externalId = null,
            postedEpochDay = 19001L, // 1 day apart, within window
            amountMinor = -450L,
            payee = "Coffee Shop",
            pendingExternal = false,
        )
        val dedupLookup: (String) -> List<TxnRef> = { hash -> if (hash == dedupHash) listOf(manualRow) else emptyList() }

        val plan = SyncEngine.plan(ACCOUNT_ID, listOf(txn), noExternal, dedupLookup, noRules)

        val op = assertIs<SyncEngine.SyncOp.LinkCrossSource>(plan.ops.single())
        assertEquals(30L, op.existingId)
        assertEquals("TRN-5", op.externalId)
    }

    @Test fun sameDedupHashButMoreThanOneDayApartInsertsInsteadOfLinking() {
        val txn = mapped(externalId = "TRN-6", postedEpochDay = 19000L, amountMinor = -450L, payee = "Coffee Shop")
        val dedupHash = DedupHash.compute(ACCOUNT_ID, txn.postedEpochDay, txn.amountMinor, txn.payee)
        val manualRow = TxnRef(
            id = 31L,
            accountId = ACCOUNT_ID,
            source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED,
            categoryId = 7L,
            externalId = null,
            postedEpochDay = 19002L, // 2 days apart, outside window
            amountMinor = -450L,
            payee = "Coffee Shop",
            pendingExternal = false,
        )
        val dedupLookup: (String) -> List<TxnRef> = { hash -> if (hash == dedupHash) listOf(manualRow) else emptyList() }

        val plan = SyncEngine.plan(ACCOUNT_ID, listOf(txn), noExternal, dedupLookup, noRules)

        assertIs<SyncEngine.SyncOp.Insert>(plan.ops.single())
    }

    @Test fun dedupCandidateFromSimpleFinSourceIsNotCrossSourceLinked() {
        val txn = mapped(externalId = "TRN-7", postedEpochDay = 19000L, amountMinor = -450L, payee = "Coffee Shop")
        val dedupHash = DedupHash.compute(ACCOUNT_ID, txn.postedEpochDay, txn.amountMinor, txn.payee)
        val simplefinRow = TxnRef(
            id = 32L,
            accountId = ACCOUNT_ID,
            source = TransactionSource.SIMPLEFIN,
            status = TransactionStatus.CONFIRMED,
            categoryId = 7L,
            externalId = "TRN-OTHER",
            postedEpochDay = 19000L,
            amountMinor = -450L,
            payee = "Coffee Shop",
            pendingExternal = false,
        )
        val dedupLookup: (String) -> List<TxnRef> = { hash -> if (hash == dedupHash) listOf(simplefinRow) else emptyList() }

        val plan = SyncEngine.plan(ACCOUNT_ID, listOf(txn), noExternal, dedupLookup, noRules)

        // Same-source dedup candidates never trigger a cross-source link; falls through to Insert.
        assertIs<SyncEngine.SyncOp.Insert>(plan.ops.single())
    }

    @Test fun multipleCrossSourceCandidatesPicksClosestByDateThenLowestId() {
        val txn = mapped(externalId = "TRN-8", postedEpochDay = 19000L, amountMinor = -450L, payee = "Coffee Shop")
        val dedupHash = DedupHash.compute(ACCOUNT_ID, txn.postedEpochDay, txn.amountMinor, txn.payee)
        // Both candidates are equally close (1 day away) but on opposite sides of the incoming
        // date, so the date-distance comparator alone ties them; only the id tie-break decides.
        val higherId = TxnRef(
            id = 50L,
            accountId = ACCOUNT_ID,
            source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED,
            categoryId = null,
            externalId = null,
            postedEpochDay = 18999L, // 1 day before
            amountMinor = -450L,
            payee = "Coffee Shop",
            pendingExternal = false,
        )
        val lowerId = TxnRef(
            id = 20L,
            accountId = ACCOUNT_ID,
            source = TransactionSource.CSV,
            status = TransactionStatus.CONFIRMED,
            categoryId = null,
            externalId = null,
            postedEpochDay = 19001L, // 1 day after
            amountMinor = -450L,
            payee = "Coffee Shop",
            pendingExternal = false,
        )
        // Listed with the higher id first so a naive "first match wins" implementation would fail.
        val dedupLookup: (String) -> List<TxnRef> =
            { hash -> if (hash == dedupHash) listOf(higherId, lowerId) else emptyList() }

        val plan = SyncEngine.plan(ACCOUNT_ID, listOf(txn), noExternal, dedupLookup, noRules)

        val op = assertIs<SyncEngine.SyncOp.LinkCrossSource>(plan.ops.single())
        assertEquals(20L, op.existingId)
    }
}
