package dev.tyler.lightledger.ui.review

import dev.tyler.lightledger.data.FakeLedgerRepository
import dev.tyler.lightledger.data.LedgerRepository
import dev.tyler.lightledger.data.TransactionStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    /** Seeds a NEEDS_REVIEW row on a SimpleFIN account; returns the new transaction id. */
    private suspend fun LedgerRepository.seedNeedsReview(
        payee: String,
        postedEpochDay: Long,
        accountId: Long,
        externalId: String,
    ): Long = insertExternalTransaction(
        accountId = accountId,
        postedEpochDay = postedEpochDay,
        amountMinor = -500L,
        payee = payee,
        memo = "",
        categoryId = null,
        status = TransactionStatus.NEEDS_REVIEW,
        externalId = externalId,
        pendingExternal = false,
        dedupHash = "hash-$externalId",
    )

    @Test fun loadsInboxOnInitAndShowsFirstItem() = runTest {
        val repository = FakeLedgerRepository()
        val accountId = repository.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        repository.seedNeedsReview("Old Store", postedEpochDay = 100L, accountId = accountId, externalId = "txn-old")
        repository.seedNeedsReview("New Store", postedEpochDay = 200L, accountId = accountId, externalId = "txn-new")

        val vm = ReviewViewModel(repository)
        advanceUntilIdle()

        assertEquals("New Store", vm.uiState.value.item?.payee)
        assertEquals(2, vm.uiState.value.remaining)
        assertTrue(vm.uiState.value.categories.isNotEmpty())
        assertFalse(vm.uiState.value.done)
    }

    @Test fun emptyInboxSetsDone() = runTest {
        val repository = FakeLedgerRepository()

        val vm = ReviewViewModel(repository)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.done)
        assertNull(vm.uiState.value.item)
        assertEquals(0, vm.uiState.value.remaining)
    }

    @Test fun confirmSetsConfirmedAndAdvancesToNextItem() = runTest {
        val repository = FakeLedgerRepository()
        val accountId = repository.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        repository.seedNeedsReview("Old Store", postedEpochDay = 100L, accountId = accountId, externalId = "txn-old")
        repository.seedNeedsReview("New Store", postedEpochDay = 200L, accountId = accountId, externalId = "txn-new")
        val vm = ReviewViewModel(repository)
        advanceUntilIdle()
        val category = repository.listCategories().first()
        val confirmedId = vm.uiState.value.item!!.id

        vm.confirm(category.id)
        advanceUntilIdle()

        assertEquals("Old Store", vm.uiState.value.item?.payee)
        assertEquals(1, vm.uiState.value.remaining)
        assertFalse(vm.uiState.value.done)
        assertTrue(repository.listReviewInbox().none { it.id == confirmedId })
    }

    @Test fun confirmOfLastItemSetsDone() = runTest {
        val repository = FakeLedgerRepository()
        val accountId = repository.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        repository.seedNeedsReview("Only Store", postedEpochDay = 100L, accountId = accountId, externalId = "txn-only")
        val vm = ReviewViewModel(repository)
        advanceUntilIdle()
        val category = repository.listCategories().first()

        vm.confirm(category.id)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.done)
        assertNull(vm.uiState.value.item)
        assertEquals(0, vm.uiState.value.remaining)
    }

    @Test fun skipAdvancesToNextItemLeavingCurrentInInbox() = runTest {
        val repository = FakeLedgerRepository()
        val accountId = repository.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        repository.seedNeedsReview("Old Store", postedEpochDay = 100L, accountId = accountId, externalId = "txn-old")
        repository.seedNeedsReview("New Store", postedEpochDay = 200L, accountId = accountId, externalId = "txn-new")
        val vm = ReviewViewModel(repository)
        advanceUntilIdle()
        val skippedId = vm.uiState.value.item!!.id

        vm.skip()
        advanceUntilIdle()

        assertEquals("Old Store", vm.uiState.value.item?.payee)
        assertEquals(1, vm.uiState.value.remaining)
        assertTrue(repository.listReviewInbox().any { it.id == skippedId })
        assertEquals(TransactionStatus.NEEDS_REVIEW, repository.findTransactionByExternal(accountId, "txn-new")?.status)
    }

    @Test fun onBackPressedDoesNotConsumeEvent() {
        val vm = ReviewViewModel(FakeLedgerRepository())
        assertFalse(vm.onBackPressed())
    }

    @Test fun threeSameCategoryConfirmsCreateRuleAndFourthDoesNotDuplicate() = runTest {
        val repository = FakeLedgerRepository()
        val accountId = repository.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        repository.seedNeedsReview("Starbucks", postedEpochDay = 100L, accountId = accountId, externalId = "txn-1")
        repository.seedNeedsReview("Starbucks", postedEpochDay = 101L, accountId = accountId, externalId = "txn-2")
        repository.seedNeedsReview("Starbucks", postedEpochDay = 102L, accountId = accountId, externalId = "txn-3")
        repository.seedNeedsReview("Starbucks", postedEpochDay = 103L, accountId = accountId, externalId = "txn-4")
        val vm = ReviewViewModel(repository)
        advanceUntilIdle()
        val category = repository.listCategories().first()

        confirmCurrent(vm, category.id)
        assertTrue(repository.listRules().isEmpty())
        confirmCurrent(vm, category.id)
        assertTrue(repository.listRules().isEmpty())
        confirmCurrent(vm, category.id)

        val rulesAfterThird = repository.listRules()
        assertEquals(1, rulesAfterThird.size)
        assertEquals("starbucks", rulesAfterThird.first().payeeContains)
        assertEquals(category.id, rulesAfterThird.first().categoryId)

        confirmCurrent(vm, category.id)

        assertEquals(1, repository.listRules().size)
    }

    @Test fun blankNormalizedPayeeNeverCreatesRule() = runTest {
        val repository = FakeLedgerRepository()
        val accountId = repository.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        // "1234" is all-digit; DedupHash.normalizePayee strips it entirely to "".
        repository.seedNeedsReview("1234", postedEpochDay = 100L, accountId = accountId, externalId = "txn-1")
        repository.seedNeedsReview("1234", postedEpochDay = 101L, accountId = accountId, externalId = "txn-2")
        repository.seedNeedsReview("1234", postedEpochDay = 102L, accountId = accountId, externalId = "txn-3")
        val vm = ReviewViewModel(repository)
        advanceUntilIdle()
        val category = repository.listCategories().first()

        confirmCurrent(vm, category.id)
        confirmCurrent(vm, category.id)
        confirmCurrent(vm, category.id)

        assertTrue(repository.listRules().isEmpty())
    }

    private fun TestScope.confirmCurrent(vm: ReviewViewModel, categoryId: Long) {
        vm.confirm(categoryId)
        advanceUntilIdle()
    }
}
