package dev.tyler.lightledger.ui.addentry

import dev.tyler.lightledger.data.FakeLedgerRepository
import java.time.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AddEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeLedgerRepository

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeLedgerRepository()
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun digitsBuildAmountText() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        vm.onDigit("4")
        vm.onDecimal()
        vm.onDigit("5")
        vm.onDigit("0")
        assertEquals("4.50", vm.uiState.value.amountText)
    }

    @Test fun cannotContinueWithZeroAmount() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        vm.onDigit("0")
        assertFalse(vm.canContinueFromAmount())
    }

    @Test fun confirmAmountAdvancesToPayeeStep() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        vm.onDigit("4")
        vm.onDecimal()
        vm.onDigit("5")
        vm.confirmAmount()
        assertEquals(AddEntryStep.PAYEE, vm.uiState.value.step)
    }

    @Test fun selectCategorySavesNegativeAmountAndMarksSaved() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        advanceUntilIdle()
        vm.onDigit("4")
        vm.onDecimal()
        vm.onDigit("5")
        vm.confirmAmount()
        vm.confirmPayee("Coffee Shop")
        val category = repository.listCategories().first()
        vm.selectCategory(category)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saved)
        val stored = repository.listTransactions(YearMonth.now()).first()
        assertEquals(-450L, stored.amountMinor)
        assertEquals("Coffee Shop", stored.payee)
    }

    @Test fun backFromPayeeReturnsToAmountStep() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        vm.onDigit("4")
        vm.confirmAmount()
        assertTrue(vm.onBackPressed())
        assertEquals(AddEntryStep.AMOUNT, vm.uiState.value.step)
    }

    @Test fun backFromAmountStepIsNotConsumed() = runTest {
        repository.ensureSeeded()
        val vm = AddEntryViewModel(repository)
        assertFalse(vm.onBackPressed())
    }
}
