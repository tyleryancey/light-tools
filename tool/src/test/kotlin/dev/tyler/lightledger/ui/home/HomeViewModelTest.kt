package dev.tyler.lightledger.ui.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.tyler.lightledger.data.CategoryMonthTotal
import dev.tyler.lightledger.data.FakeLedgerRepository
import dev.tyler.lightledger.data.LedgerPreferences
import dev.tyler.lightledger.data.TransactionStatus
import dev.tyler.lightledger.domain.LedgerMath
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [DataStore] is created on the same [StandardTestDispatcher] installed as [Dispatchers.Main]
 * so its internal write-actor coroutine shares `runTest`'s scheduler — `advanceUntilIdle()`
 * drains both the ViewModel's `viewModelScope` work and the DataStore write in one pass.
 * Mirrors [dev.tyler.lightledger.ui.settings.SettingsViewModelTest]'s setup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dataStoreScopeJob = Job()
    private lateinit var tempDir: File
    private lateinit var dataStore: DataStore<Preferences>

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("home-vm-test").toFile()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + dataStoreScopeJob),
        ) { File(tempDir, "home_test.preferences_pb") }
    }

    @AfterTest
    fun tearDown() {
        dataStoreScopeJob.cancel()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test fun initSeedsRepositoryAndClearsLoading() = runTest {
        val repository = FakeLedgerRepository()
        val vm = HomeViewModel(repository, dataStore)
        advanceUntilIdle()

        assertEquals(true, repository.seeded)
        assertFalse(vm.uiState.value.loading)
    }

    @Test fun reloadReflectsNewTransactions() = runTest {
        val repository = FakeLedgerRepository()
        val vm = HomeViewModel(repository, dataStore)
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
            CategoryMonthTotal(1L, "Groceries", -1200L, "USD"),
            CategoryMonthTotal(2L, "Income", 5000L, "USD"),
        )
        assertEquals(1200L, totalSpentMinor(totals))
    }

    @Test fun mixedCurrencyMonthSummaryExposesOnlyPrimaryCurrencySpend() = runTest {
        val repository = FakeLedgerRepository()
        val vm = HomeViewModel(repository, dataStore)
        advanceUntilIdle()

        val groceries = repository.listCategories().first { it.name == "Groceries" }
        // USD spend via the seeded manual (Cash) account.
        repository.addManualTransaction(amountMinor = -5000L, payee = "Market", categoryId = groceries.id)
        // A second, non-USD account whose spend must never be summed into the USD total.
        val jpyAccountId = repository.upsertSimpleFinAccount(externalId = "jpy-acct", name = "JPY Account", currency = "JPY")
        repository.insertExternalTransaction(
            accountId = jpyAccountId,
            postedEpochDay = LocalDate.now().toEpochDay(),
            amountMinor = -9000L,
            payee = "Tokyo Store",
            memo = "",
            categoryId = groceries.id,
            status = TransactionStatus.CONFIRMED,
            externalId = "jpy-txn-1",
            pendingExternal = false,
            dedupHash = "jpy-dedup-1",
        )
        vm.reload()
        advanceUntilIdle()

        val totals = vm.uiState.value.categoryTotals
        val primary = LedgerMath.primaryCurrencyTotal(totals)
        // JPY's 9000 minor-unit spend outweighs USD's 5000, so JPY is the primary currency and
        // the displayed total must equal only its spend — never a cross-currency sum (14000).
        assertEquals("JPY" to 9000L, primary)
    }

    @Test fun opportunisticSyncNotDueWithoutBlob() = runTest {
        val repository = FakeLedgerRepository()
        val vm = HomeViewModel(repository, dataStore)
        advanceUntilIdle()

        assertFalse(vm.isOpportunisticSyncDue(nowMs = 1_000_000L))
    }

    @Test fun opportunisticSyncDueWhenBlobPresentAndNeverSynced() = runTest {
        val repository = FakeLedgerRepository()
        dataStore.edit { it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob" }
        val vm = HomeViewModel(repository, dataStore)
        advanceUntilIdle()

        assertTrue(vm.isOpportunisticSyncDue(nowMs = 1_000_000L))
    }

    @Test fun opportunisticSyncDueWhenLastSyncOlderThanSixHours() = runTest {
        val repository = FakeLedgerRepository()
        val nowMs = 1_000_000_000L
        val sevenHoursMs = 7L * 60 * 60 * 1000
        dataStore.edit {
            it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob"
            it[LedgerPreferences.LAST_SYNC_EPOCH_MS] = nowMs - sevenHoursMs
        }
        val vm = HomeViewModel(repository, dataStore)
        advanceUntilIdle()

        assertTrue(vm.isOpportunisticSyncDue(nowMs))
    }

    @Test fun opportunisticSyncNotDueWhenLastSyncWithinSixHours() = runTest {
        val repository = FakeLedgerRepository()
        val nowMs = 1_000_000_000L
        val oneHourMs = 60 * 60 * 1000
        dataStore.edit {
            it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob"
            it[LedgerPreferences.LAST_SYNC_EPOCH_MS] = nowMs - oneHourMs
        }
        val vm = HomeViewModel(repository, dataStore)
        advanceUntilIdle()

        assertFalse(vm.isOpportunisticSyncDue(nowMs))
    }

    @Test fun opportunisticSyncNotDueWhenLastSyncExactlySixHoursAgo() = runTest {
        val repository = FakeLedgerRepository()
        val nowMs = 1_000_000_000L
        val sixHoursMs = 6L * 60 * 60 * 1000
        dataStore.edit {
            it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob"
            it[LedgerPreferences.LAST_SYNC_EPOCH_MS] = nowMs - sixHoursMs
        }
        val vm = HomeViewModel(repository, dataStore)
        advanceUntilIdle()

        // Boundary check: the gate is strict `>`, so exactly the interval old is not yet due.
        assertFalse(vm.isOpportunisticSyncDue(nowMs))
    }
}
