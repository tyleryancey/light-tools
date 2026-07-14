package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.data.FakeLedgerRepository
import dev.tyler.lightledger.data.TransactionStatus
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val ACCESS_URL = "https://user:pass@bridge.simplefin.org/simplefin"
private const val START_EPOCH_S = 1_700_000_000L
private const val ACCOUNT_EXTERNAL_ID = "ACT-1"

/** The epoch day [START_EPOCH_S] falls on in the system default zone — used as `syncEpochDay`
 * for tests that don't exercise pending-settle staleness, so the pass is a guaranteed no-op. */
private val SYNC_EPOCH_DAY: Long = epochDayOf(START_EPOCH_S)

private fun epochDayOf(epochSeconds: Long): Long =
    java.time.Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** Noon (never a DST-transition hour) on [epochDay] in the system default zone, round-tripping
 * cleanly back through [SimpleFinMapper]'s Instant -> LocalDate conversion. */
private fun epochSecondsAtNoon(epochDay: Long): Long =
    LocalDate.ofEpochDay(epochDay).atTime(12, 0).atZone(ZoneId.systemDefault()).toEpochSecond()

/** A fake [SimpleFinApi] that returns a canned [Result] and never touches the network.
 * [claim] is unused by these sync-orchestration tests (Task 10's connect flow owns that
 * path) but must be implemented to satisfy the interface. */
private class FakeSimpleFinApi(private val result: Result<AccountSet>) : SimpleFinApi {
    override suspend fun claim(setupTokenBase64: String): Result<String> =
        Result.failure(UnsupportedOperationException("claim() is not exercised by SimpleFinSyncRunnerTest"))

    override suspend fun fetch(accessUrl: String, startEpochS: Long): Result<AccountSet> = result
}

/**
 * One account with three transactions exercising the three [SyncEngine.SyncOp] branches
 * reachable without a pre-existing cross-source row (that branch is [SyncEngine]'s own
 * responsibility and is already covered by SyncEngineTest):
 *  - TRN-NEW-NO-RULE: brand new, no rule match -> Insert NEEDS_REVIEW
 *  - TRN-NEW-RULE: brand new, payee matches a seeded rule -> Insert CONFIRMED
 *  - TRN-EXISTING: externalId already linked in the repo -> UpdateExternalFields
 */
private fun fixtureAccountSet(): AccountSet = AccountSet(
    errors = emptyList(),
    accounts = listOf(
        SimpleFinAccount(
            id = ACCOUNT_EXTERNAL_ID,
            name = "Checking",
            currency = "USD",
            transactions = listOf(
                SimpleFinTransaction(
                    id = "TRN-NEW-NO-RULE",
                    posted = 1_700_000_000L,
                    amount = "-4.50",
                    description = "Random Store",
                    payee = null,
                    memo = null,
                    pending = false,
                ),
                SimpleFinTransaction(
                    id = "TRN-NEW-RULE",
                    posted = 1_700_000_000L,
                    amount = "-3.25",
                    description = "STARBUCKS #123",
                    payee = "Starbucks Coffee",
                    memo = null,
                    pending = false,
                ),
                SimpleFinTransaction(
                    id = "TRN-EXISTING",
                    posted = 1_700_100_000L,
                    amount = "-999.00",
                    description = "Updated Desc",
                    payee = "Updated Payee",
                    memo = null,
                    pending = true,
                ),
            ),
        ),
    ),
)

class SimpleFinSyncRunnerTest {

    @Test
    fun newTransactionWithNoRuleMatchIsInsertedNeedsReview() = runTest {
        val repo = FakeLedgerRepository()
        val runner = SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(fixtureAccountSet())))

        runner.sync(ACCESS_URL, START_EPOCH_S, SYNC_EPOCH_DAY)

        val dbId = repo.upsertSimpleFinAccount(ACCOUNT_EXTERNAL_ID, "Checking", "USD")
        val txn = assertNotNull(repo.findTransactionByExternal(dbId, "TRN-NEW-NO-RULE"))
        assertEquals(TransactionStatus.NEEDS_REVIEW, txn.status)
        assertEquals(null, txn.categoryId)
    }

    @Test
    fun newTransactionMatchingRuleIsInsertedConfirmedWithCategory() = runTest {
        val repo = FakeLedgerRepository()
        repo.insertRule("starbucks", 42L)
        val runner = SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(fixtureAccountSet())))

        runner.sync(ACCESS_URL, START_EPOCH_S, SYNC_EPOCH_DAY)

        val dbId = repo.upsertSimpleFinAccount(ACCOUNT_EXTERNAL_ID, "Checking", "USD")
        val txn = assertNotNull(repo.findTransactionByExternal(dbId, "TRN-NEW-RULE"))
        assertEquals(TransactionStatus.CONFIRMED, txn.status)
        assertEquals(42L, txn.categoryId)
    }

    @Test
    fun existingExternalTransactionIsUpdatedNotReinsertedAndCategoryStatusArePreserved() = runTest {
        val repo = FakeLedgerRepository()
        val dbId = repo.upsertSimpleFinAccount(ACCOUNT_EXTERNAL_ID, "Checking", "USD")
        val existingId = repo.insertExternalTransaction(
            accountId = dbId,
            postedEpochDay = 10L,
            amountMinor = -100L,
            payee = "Old Payee",
            memo = "old memo",
            categoryId = 7L,
            status = TransactionStatus.CONFIRMED,
            externalId = "TRN-EXISTING",
            pendingExternal = false,
            dedupHash = "irrelevant-hash",
        )
        val runner = SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(fixtureAccountSet())))

        val outcome = runner.sync(ACCESS_URL, START_EPOCH_S, SYNC_EPOCH_DAY).getOrThrow()

        val updated = assertNotNull(repo.findTransactionByExternal(dbId, "TRN-EXISTING"))
        assertEquals(existingId, updated.id)
        // Never touched by an update — must survive the sync unchanged.
        assertEquals(TransactionStatus.CONFIRMED, updated.status)
        assertEquals(7L, updated.categoryId)
        // Mutable fields the bank revised — must reflect the new fetch.
        assertEquals(-99900L, updated.amountMinor)
        assertEquals("Updated Payee", updated.payee)
        assertTrue(updated.pendingExternal)
        // Only the two genuinely-new transactions count toward "new".
        assertEquals(2, outcome.newCount)
    }

    @Test
    fun accountIsUpsertedAsSimpleFinSoDeleteSimpleFinDataRemovesSyncedRows() = runTest {
        val repo = FakeLedgerRepository()
        val runner = SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(fixtureAccountSet())))

        runner.sync(ACCESS_URL, START_EPOCH_S, SYNC_EPOCH_DAY)
        val dbId = repo.upsertSimpleFinAccount(ACCOUNT_EXTERNAL_ID, "Checking", "USD")
        assertNotNull(repo.findTransactionByExternal(dbId, "TRN-NEW-NO-RULE"))

        repo.deleteSimpleFinData()

        assertNull(repo.findTransactionByExternal(dbId, "TRN-NEW-NO-RULE"))
    }

    @Test
    fun fetchFailureIsReturnedUnchangedForTheJobToClassify() = runTest {
        val repo = FakeLedgerRepository()
        val failure = SimpleFinHttpException(403, "SimpleFIN fetch HTTP 403")
        val runner = SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.failure(failure)))

        val result = runner.sync(ACCESS_URL, START_EPOCH_S, SYNC_EPOCH_DAY)

        assertTrue(result.isFailure)
        val exception = assertNotNull(result.exceptionOrNull())
        assertTrue(exception is SimpleFinHttpException)
        assertEquals(403, exception.statusCode)
    }

    @Test
    fun pendingRowMigratesCategoryOntoNewlySettledRowAndStalePendingRowIsDeleted() = runTest {
        val repo = FakeLedgerRepository()
        val oldDay = 19_700L
        val fetchStartS = epochSecondsAtNoon(oldDay - 30)

        // Fetch 1: a pending SIMPLEFIN transaction lands under external id "OLD".
        val fetch1 = AccountSet(
            errors = emptyList(),
            accounts = listOf(
                SimpleFinAccount(
                    id = ACCOUNT_EXTERNAL_ID,
                    name = "Checking",
                    currency = "USD",
                    transactions = listOf(
                        SimpleFinTransaction(
                            id = "OLD",
                            posted = epochSecondsAtNoon(oldDay),
                            amount = "-12.34",
                            description = "Grocery Store",
                            payee = null,
                            memo = null,
                            pending = true,
                        ),
                    ),
                ),
            ),
        )
        // syncEpochDay == oldDay: the just-inserted pending row is not aged yet, so fetch 1's
        // own pending-settle pass must be a no-op.
        SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(fetch1)))
            .sync(ACCESS_URL, fetchStartS, oldDay)
            .getOrThrow()

        val dbId = repo.upsertSimpleFinAccount(ACCOUNT_EXTERNAL_ID, "Checking", "USD")
        val oldId = assertNotNull(repo.findTransactionByExternal(dbId, "OLD")).id
        // User categorizes the pending row before it settles.
        repo.confirmReview(oldId, 55L)

        // Fetch 2: "OLD" is gone from the feed (the bank re-posted it under a new id, "NEW"),
        // settled (pending = false), same amount, posted within PENDING_WINDOW_DAYS of "OLD".
        val fetch2 = AccountSet(
            errors = emptyList(),
            accounts = listOf(
                SimpleFinAccount(
                    id = ACCOUNT_EXTERNAL_ID,
                    name = "Checking",
                    currency = "USD",
                    transactions = listOf(
                        SimpleFinTransaction(
                            id = "NEW",
                            posted = epochSecondsAtNoon(oldDay + 2),
                            amount = "-12.34",
                            description = "Grocery Store Settled",
                            payee = null,
                            memo = null,
                            pending = false,
                        ),
                    ),
                ),
            ),
        )
        // fetchStartEpochDay (oldDay - 30) covers "OLD"'s posted day, and syncEpochDay is
        // advanced 10 days past it (> PENDING_AGE_DAYS), so "OLD" is now aged + vanished.
        SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(fetch2)))
            .sync(ACCESS_URL, fetchStartS, oldDay + 10)
            .getOrThrow()

        val newTxn = assertNotNull(repo.findTransactionByExternal(dbId, "NEW"))
        assertEquals(TransactionStatus.CONFIRMED, newTxn.status)
        assertEquals(55L, newTxn.categoryId)
        assertNull(repo.getTransaction(oldId))
    }

    @Test
    fun crossSourceMatchAdoptsExternalIdOntoExistingManualRowInsteadOfInsertingADuplicate() = runTest {
        val repo = FakeLedgerRepository()
        repo.ensureSeeded()
        val categoryId = repo.listCategories().first().id
        val manualId = repo.addManualTransaction(amountMinor = -500L, payee = "Coffee Shop", categoryId = categoryId)
        val manualAccountId = assertNotNull(repo.getTransaction(manualId)).accountId
        val manualDay = assertNotNull(repo.getTransaction(manualId)).postedEpochDay

        val accountSet = AccountSet(
            errors = emptyList(),
            accounts = listOf(
                SimpleFinAccount(
                    id = ACCOUNT_EXTERNAL_ID,
                    name = "Checking",
                    currency = "USD",
                    transactions = listOf(
                        SimpleFinTransaction(
                            id = "SFX-1",
                            posted = epochSecondsAtNoon(manualDay),
                            amount = "-5.00",
                            description = "Coffee Shop",
                            payee = null,
                            memo = null,
                            pending = false,
                        ),
                    ),
                ),
            ),
        )
        val runner = SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(accountSet)))

        val outcome = runner.sync(ACCESS_URL, START_EPOCH_S, SYNC_EPOCH_DAY).getOrThrow()

        // No new row inserted — the existing MANUAL row adopted the externalId.
        assertEquals(0, outcome.newCount)
        val linked = assertNotNull(repo.findTransactionByExternal(manualAccountId, "SFX-1"))
        assertEquals(manualId, linked.id)
    }

    @Test
    fun accountSetErrorsAreReturnedInSyncOutcomeWithoutFailingTheSync() = runTest {
        val repo = FakeLedgerRepository()
        val accountSet = AccountSet(errors = listOf("Bridge says reauth"), accounts = emptyList())
        val runner = SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(accountSet)))

        val result = runner.sync(ACCESS_URL, START_EPOCH_S, SYNC_EPOCH_DAY)

        assertTrue(result.isSuccess)
        assertEquals(listOf("Bridge says reauth"), result.getOrNull()!!.errors)
    }

    @Test
    fun notYetAgedPendingRowSurvivesARefetchWhereItsExternalIdIsAbsent() = runTest {
        val repo = FakeLedgerRepository()
        val pendingDay = 19_700L
        val fetchStartS = epochSecondsAtNoon(pendingDay - 30)

        val fetch1 = AccountSet(
            errors = emptyList(),
            accounts = listOf(
                SimpleFinAccount(
                    id = ACCOUNT_EXTERNAL_ID,
                    name = "Checking",
                    currency = "USD",
                    transactions = listOf(
                        SimpleFinTransaction(
                            id = "STILL-PENDING",
                            posted = epochSecondsAtNoon(pendingDay),
                            amount = "-9.00",
                            description = "Pending Charge",
                            payee = null,
                            memo = null,
                            pending = true,
                        ),
                    ),
                ),
            ),
        )
        SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(fetch1)))
            .sync(ACCESS_URL, fetchStartS, pendingDay)
            .getOrThrow()

        val dbId = repo.upsertSimpleFinAccount(ACCOUNT_EXTERNAL_ID, "Checking", "USD")
        assertNotNull(repo.findTransactionByExternal(dbId, "STILL-PENDING"))

        // Re-fetch only 2 days later (< PENDING_AGE_DAYS) with the account now reporting no
        // transactions at all — "STILL-PENDING" vanished from the feed but hasn't aged yet.
        val fetch2 = AccountSet(
            errors = emptyList(),
            accounts = listOf(
                SimpleFinAccount(id = ACCOUNT_EXTERNAL_ID, name = "Checking", currency = "USD", transactions = emptyList()),
            ),
        )
        SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(fetch2)))
            .sync(ACCESS_URL, fetchStartS, pendingDay + 2)
            .getOrThrow()

        assertNotNull(repo.findTransactionByExternal(dbId, "STILL-PENDING"))
    }
}
