package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.data.FakeLedgerRepository
import dev.tyler.lightledger.data.TransactionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val ACCESS_URL = "https://user:pass@bridge.simplefin.org/simplefin"
private const val START_EPOCH_S = 1_700_000_000L
private const val ACCOUNT_EXTERNAL_ID = "ACT-1"

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

        runner.sync(ACCESS_URL, START_EPOCH_S)

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

        runner.sync(ACCESS_URL, START_EPOCH_S)

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

        val newCount = runner.sync(ACCESS_URL, START_EPOCH_S).getOrThrow()

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
        assertEquals(2, newCount)
    }

    @Test
    fun accountIsUpsertedAsSimpleFinSoDeleteSimpleFinDataRemovesSyncedRows() = runTest {
        val repo = FakeLedgerRepository()
        val runner = SimpleFinSyncRunner(repo, FakeSimpleFinApi(Result.success(fixtureAccountSet())))

        runner.sync(ACCESS_URL, START_EPOCH_S)
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

        val result = runner.sync(ACCESS_URL, START_EPOCH_S)

        assertTrue(result.isFailure)
        val exception = assertNotNull(result.exceptionOrNull())
        assertTrue(exception is SimpleFinHttpException)
        assertEquals(403, exception.statusCode)
    }
}
