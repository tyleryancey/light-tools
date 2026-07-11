package dev.tyler.lightledger.ui.categories

import dev.tyler.lightledger.data.FakeLedgerRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeLedgerRepository

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeLedgerRepository()
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadsSeededCategoriesOnInit() = runTest {
        repository.ensureSeeded()
        val vm = CategoriesViewModel(repository)
        advanceUntilIdle()
        assertEquals(8, vm.categories.value.size)
    }

    @Test fun addCategoryAppendsAndReloads() = runTest {
        repository.ensureSeeded()
        val vm = CategoriesViewModel(repository)
        advanceUntilIdle()
        vm.addCategory("Travel")
        advanceUntilIdle()
        assertTrue(vm.categories.value.any { it.name == "Travel" })
    }

    @Test fun blankNameIsIgnored() = runTest {
        repository.ensureSeeded()
        val vm = CategoriesViewModel(repository)
        advanceUntilIdle()
        vm.addCategory("   ")
        advanceUntilIdle()
        assertEquals(8, vm.categories.value.size)
    }

    @Test fun archiveCategoryRemovesFromList() = runTest {
        repository.ensureSeeded()
        val vm = CategoriesViewModel(repository)
        advanceUntilIdle()
        val target = vm.categories.value.first()
        vm.archiveCategory(target.id)
        advanceUntilIdle()
        assertTrue(vm.categories.value.none { it.id == target.id })
    }
}
