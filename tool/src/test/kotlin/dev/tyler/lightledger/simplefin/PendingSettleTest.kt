package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.data.TransactionSource
import dev.tyler.lightledger.data.TransactionStatus
import dev.tyler.lightledger.data.TxnRef
import kotlin.test.Test
import kotlin.test.assertEquals

// Shared clock fixture: syncEpochDay - ageDays(5) == 19995, so postedEpochDay <= 19995 is "aged".
private const val SYNC_EPOCH_DAY = 20000L
private const val FETCH_START_EPOCH_DAY = 19980L
private const val AGED_POSTED_DAY = 19990L // <= 19995: aged. >= 19980: fetch-covered.

class PendingSettleTest {
    private fun txn(
        id: Long,
        postedEpochDay: Long = AGED_POSTED_DAY,
        amountMinor: Long = -500L,
        status: String = TransactionStatus.CONFIRMED,
        categoryId: Long? = 7L,
        externalId: String? = "EXT-$id",
    ) = TxnRef(
        id = id,
        accountId = 1L,
        source = TransactionSource.SIMPLEFIN,
        status = status,
        categoryId = categoryId,
        externalId = externalId,
        postedEpochDay = postedEpochDay,
        amountMinor = amountMinor,
        payee = "Merchant",
        pendingExternal = true,
    )

    private fun plan(
        pending: List<TxnRef>,
        settledCandidates: List<TxnRef> = emptyList(),
        fetchedExternalIds: Set<String> = emptySet(),
        fetchStartEpochDay: Long = FETCH_START_EPOCH_DAY,
        syncEpochDay: Long = SYNC_EPOCH_DAY,
    ) = PendingSettle.plan(pending, settledCandidates, fetchedExternalIds, fetchStartEpochDay, syncEpochDay)

    @Test fun settledWithNewId_categorized_migratesThenDeletes() {
        val p = txn(id = 1, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 7L)
        val s = txn(id = 2, postedEpochDay = AGED_POSTED_DAY + 1, externalId = "NEW")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(s))

        assertEquals(listOf(SettleOp.MigrateThenDelete(oldId = 1L, newId = 2L, categoryId = 7L)), ops)
    }

    @Test fun settledWithNewId_uncategorized_deletesStale() {
        val p = txn(id = 1, externalId = "OLD", status = TransactionStatus.NEEDS_REVIEW, categoryId = null)
        val s = txn(id = 2, postedEpochDay = AGED_POSTED_DAY + 1, externalId = "NEW")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(s))

        assertEquals(listOf(SettleOp.DeleteStale(oldId = 1L)), ops)
    }

    @Test fun settledSameId_noOp() {
        // Bank kept the pending row's external id, so it's still in the fetched set: the normal
        // SyncEngine pass already updated it. It must NOT be churned here even though a
        // would-be-matching settled candidate exists in the same call.
        val p = txn(id = 1, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 7L)
        val s = txn(id = 2, postedEpochDay = AGED_POSTED_DAY + 1, externalId = "NEW")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(s), fetchedExternalIds = setOf("OLD"))

        assertEquals(emptyList(), ops)
    }

    @Test fun amountRevised_noMatch_deletesStale() {
        // Settled candidate is within the date window but the bank revised the amount, so it
        // can't be reconciled. Pending is CONFIRMED + categorized to prove this branch deletes
        // rather than migrates even when the pending row WOULD otherwise qualify for migration.
        val p = txn(id = 1, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 7L, amountMinor = -500L)
        val s = txn(id = 2, postedEpochDay = AGED_POSTED_DAY + 1, externalId = "NEW", amountMinor = -600L)

        val ops = plan(pending = listOf(p), settledCandidates = listOf(s))

        assertEquals(listOf(SettleOp.DeleteStale(oldId = 1L)), ops)
    }

    @Test fun neverSettled_expiry_deletesStale() {
        val p = txn(id = 1, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 7L)

        val ops = plan(pending = listOf(p), settledCandidates = emptyList())

        assertEquals(listOf(SettleOp.DeleteStale(oldId = 1L)), ops)
    }

    @Test fun notYetAged_untouched() {
        // Fetch-covered and vanished both hold; only the age guard fails.
        val p = txn(id = 1, postedEpochDay = 19999L, externalId = "OLD")
        val s = txn(id = 2, postedEpochDay = 19999L, externalId = "NEW")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(s))

        assertEquals(emptyList(), ops)
    }

    @Test fun outsideFetchWindow_untouched() {
        // Aged and vanished both hold; only the fetch-coverage guard fails (fetch started after
        // this row's posted date, so the fetch can't have observed whether it vanished).
        val p = txn(id = 1, postedEpochDay = AGED_POSTED_DAY, externalId = "OLD")
        val s = txn(id = 2, postedEpochDay = AGED_POSTED_DAY, externalId = "NEW")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(s), fetchStartEpochDay = AGED_POSTED_DAY + 5)

        assertEquals(emptyList(), ops)
    }

    @Test fun multipleCandidates_picksClosestThenLowestId() {
        val p = txn(id = 1, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 9L)
        // Both candidates are exactly 2 days away (equidistant); lower id must win.
        val farId = txn(id = 10, postedEpochDay = AGED_POSTED_DAY + 2, externalId = "NEW-10")
        val nearId = txn(id = 5, postedEpochDay = AGED_POSTED_DAY - 2, externalId = "NEW-5")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(farId, nearId))

        assertEquals(listOf(SettleOp.MigrateThenDelete(oldId = 1L, newId = 5L, categoryId = 9L)), ops)
    }

    @Test fun twoPendings_doNotShareOneSettledRow() {
        // Passed out of ascending-id order to prove `plan` sorts by id itself: id 5 first in the
        // input list, id 2 second. Processing must still occur in id order (2 then 5), so the
        // single settled candidate is consumed by pending id 2, leaving pending id 5 unmatched.
        val higherIdPending = txn(id = 5, postedEpochDay = AGED_POSTED_DAY + 1, externalId = "P1X", categoryId = 4L, amountMinor = -700L)
        val lowerIdPending = txn(id = 2, postedEpochDay = AGED_POSTED_DAY, externalId = "P2X", categoryId = 3L, amountMinor = -700L)
        val settled = txn(id = 99, postedEpochDay = AGED_POSTED_DAY, externalId = "NEW", amountMinor = -700L)

        val ops = plan(pending = listOf(higherIdPending, lowerIdPending), settledCandidates = listOf(settled))

        assertEquals(
            listOf(
                SettleOp.MigrateThenDelete(oldId = 2L, newId = 99L, categoryId = 3L),
                SettleOp.DeleteStale(oldId = 5L),
            ),
            ops,
        )
    }

    @Test fun stillPresentAndAged_noOp() {
        // Redundant with settledSameId_noOp by design (kept separate per the spec's matrix for
        // clarity): an aged pending whose external id is still present in the fetch is untouched,
        // even with zero settled candidates to consider.
        val p = txn(id = 1, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 7L)

        val ops = plan(pending = listOf(p), settledCandidates = emptyList(), fetchedExternalIds = setOf("OLD"))

        assertEquals(emptyList(), ops)
    }

    @Test fun windowBoundary_atWindow_migrates_beyondWindow_deletesStale() {
        // windowDays defaults to 4: a settled row exactly 4 days out must still match (<=), but
        // one 5 days out must not (no match -> DeleteStale, not MigrateThenDelete).
        val pAtWindow = txn(id = 1, externalId = "OLD-A", status = TransactionStatus.CONFIRMED, categoryId = 7L)
        val sAtWindow = txn(id = 2, postedEpochDay = AGED_POSTED_DAY + 4, externalId = "NEW-A")
        val opsAtWindow = plan(pending = listOf(pAtWindow), settledCandidates = listOf(sAtWindow))
        assertEquals(listOf(SettleOp.MigrateThenDelete(oldId = 1L, newId = 2L, categoryId = 7L)), opsAtWindow)

        val pBeyondWindow = txn(id = 3, externalId = "OLD-B", status = TransactionStatus.CONFIRMED, categoryId = 7L)
        val sBeyondWindow = txn(id = 4, postedEpochDay = AGED_POSTED_DAY + 5, externalId = "NEW-B")
        val opsBeyondWindow = plan(pending = listOf(pBeyondWindow), settledCandidates = listOf(sBeyondWindow))
        assertEquals(listOf(SettleOp.DeleteStale(oldId = 3L)), opsBeyondWindow)
    }

    @Test fun matchTieBreak_prefersNearerDate_evenWhenHigherId() {
        // The nearer candidate carries the HIGHER id: the tie-break must sort by (distance, id),
        // not (id, distance), or this would wrongly pick the farther-but-lower-id candidate.
        val p = txn(id = 1, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 9L)
        val sNear = txn(id = 20, postedEpochDay = AGED_POSTED_DAY + 1, externalId = "NEW-NEAR")
        val sFar = txn(id = 5, postedEpochDay = AGED_POSTED_DAY + 3, externalId = "NEW-FAR")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(sFar, sNear))

        assertEquals(listOf(SettleOp.MigrateThenDelete(oldId = 1L, newId = 20L, categoryId = 9L)), ops)
    }

    @Test fun uncategorizedPendingDoesNotConsumeCandidateForLaterCategorizedPending() {
        // pA (lower id, processed first) is uncategorized/not-confirmed and only ever produces
        // DeleteStale. The one settled candidate must remain in the pool for pB (higher id,
        // processed second) to migrate onto -- a candidate may only be consumed by an actual
        // MigrateThenDelete, never by a DeleteStale that merely happened to find a match.
        val pA = txn(id = 2, externalId = "OLD-A", status = TransactionStatus.NEEDS_REVIEW, categoryId = null, amountMinor = -700L)
        val pB = txn(id = 5, externalId = "OLD-B", status = TransactionStatus.CONFIRMED, categoryId = 4L, amountMinor = -700L)
        val s = txn(id = 99, externalId = "NEW", amountMinor = -700L)

        val ops = plan(pending = listOf(pA, pB), settledCandidates = listOf(s))

        assertEquals(
            listOf(
                SettleOp.DeleteStale(oldId = 2L),
                SettleOp.MigrateThenDelete(oldId = 5L, newId = 99L, categoryId = 4L),
            ),
            ops,
        )
    }

    @Test fun agedBoundary_exactlyAtThreshold_isIncluded() {
        // postedEpochDay == syncEpochDay - ageDays must be included: aged uses <=, not <.
        val thresholdDay = SYNC_EPOCH_DAY - 5L // 19995
        val p = txn(id = 1, postedEpochDay = thresholdDay, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 7L)
        val s = txn(id = 2, postedEpochDay = thresholdDay, externalId = "NEW")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(s))

        assertEquals(listOf(SettleOp.MigrateThenDelete(oldId = 1L, newId = 2L, categoryId = 7L)), ops)
    }

    @Test fun fetchCoveredBoundary_exactlyAtStart_isIncluded() {
        // postedEpochDay == fetchStartEpochDay must be included: fetch-covered uses >=, not >.
        val p = txn(id = 1, postedEpochDay = FETCH_START_EPOCH_DAY, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 7L)
        val s = txn(id = 2, postedEpochDay = FETCH_START_EPOCH_DAY, externalId = "NEW")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(s))

        assertEquals(listOf(SettleOp.MigrateThenDelete(oldId = 1L, newId = 2L, categoryId = 7L)), ops)
    }

    @Test fun pendingOwnIdInCandidatePool_isNotSelfMatched() {
        // A candidate sharing the pending row's own id must be excluded by `s.id != p.id`, even
        // though its amount/date otherwise match exactly, and even with no other candidate around.
        val p = txn(id = 1, externalId = "OLD", status = TransactionStatus.CONFIRMED, categoryId = 7L)
        val self = txn(id = 1, externalId = "SELF-ECHO")

        val ops = plan(pending = listOf(p), settledCandidates = listOf(self))

        assertEquals(listOf(SettleOp.DeleteStale(oldId = 1L)), ops)
    }
}
