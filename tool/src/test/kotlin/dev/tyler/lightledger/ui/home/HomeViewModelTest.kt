package dev.tyler.lightledger.ui.home

import dev.tyler.lightledger.data.CategoryMonthTotal
import dev.tyler.lightledger.data.FakeLedgerRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun initSeedsRepositoryAndClearsLoading() = runTest {
        val repository = FakeLedgerRepository()
        val vm = HomeViewModel(repository)
        advanceUntilIdle()

        assertEquals(true, repository.seeded)
        assertFalse(vm.uiState.value.loading)
    }

    @Test fun reloadReflectsNewTransactions() = runTest {
        val repository = FakeLedgerRepository()
        val vm = HomeViewModel(repository)
        advanceUntilIdle()

        val groceries = repository.listCategories().first { it.name == "Groceries" }
        repository.addManualTransaction(amountMinor = -1200L, payee = "Market", categoryId = groceries.id)
        vm.reload()
        advanceUntilIdle()

        val total = vm.uiState.value.categoryTotals.first { it.categoryId == groceries.id }
        assertEquals(-1200L, total.totalMinor)
    }

    @Test fun totalSpentMinorSumsOnlyNegativeAmounts() {
        val totals = listOf(
            CategoryMonthTotal(1L, "Groceries", -1200L),
            CategoryMonthTotal(2L, "Income", 5000L),
        )
        assertEquals(1200L, totalSpentMinor(totals))
    }
}
