package dev.tyler.lightledger.ui.history

import dev.tyler.lightledger.data.FakeLedgerRepository
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeLedgerRepository

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeLedgerRepository()
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadsCurrentMonthTransactionsOnInit() = runTest {
        repository.ensureSeeded()
        val category = repository.listCategories().first()
        repository.addManualTransaction(-450L, "Coffee", category.id)
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.transactions.size)
    }

    @Test fun previousMonthNavigatesBack() = runTest {
        repository.ensureSeeded()
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        val currentMonth = vm.uiState.value.month
        vm.showPreviousMonth()
        advanceUntilIdle()
        assertEquals(currentMonth.minusMonths(1), vm.uiState.value.month)
    }

    @Test fun openDetailSetsSelection() = runTest {
        repository.ensureSeeded()
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        vm.openDetail(42L)
        assertEquals(42L, vm.uiState.value.selectedTransactionId)
    }

    @Test fun backPressedClosesDetailWhenOpen() = runTest {
        repository.ensureSeeded()
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        vm.openDetail(42L)
        assertTrue(vm.onBackPressed())
        assertNull(vm.uiState.value.selectedTransactionId)
    }

    @Test fun backPressedNotConsumedWhenNoDetailOpen() = runTest {
        repository.ensureSeeded()
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        assertFalse(vm.onBackPressed())
    }

    @Test fun deleteSelectedRemovesTransactionAndClosesDetail() = runTest {
        repository.ensureSeeded()
        val category = repository.listCategories().first()
        val id = repository.addManualTransaction(-450L, "Coffee", category.id)
        val vm = HistoryViewModel(repository)
        advanceUntilIdle()
        vm.openDetail(id)
        vm.deleteSelected()
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedTransactionId)
        assertTrue(vm.uiState.value.transactions.none { it.id == id })
    }
}
