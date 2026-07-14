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
}
