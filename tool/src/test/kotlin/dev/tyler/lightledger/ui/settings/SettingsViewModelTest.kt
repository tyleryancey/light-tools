package dev.tyler.lightledger.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.tyler.lightledger.data.FakeLedgerRepository
import dev.tyler.lightledger.data.LedgerPreferences
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [DataStore] is created on the same [StandardTestDispatcher] installed as [Dispatchers.Main]
 * so its internal write-actor coroutine shares `runTest`'s scheduler — `advanceUntilIdle()`
 * drains both the ViewModel's `viewModelScope` work and the DataStore write in one pass.
 * Mirrors [dev.tyler.lightledger.ui.settings.SimpleFinConnectViewModelTest]'s setup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dataStoreScopeJob = Job()
    private lateinit var tempDir: File
    private lateinit var dataStore: DataStore<Preferences>

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("settings-vm-test").toFile()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + dataStoreScopeJob),
        ) { File(tempDir, "settings_test.preferences_pb") }
    }

    @AfterTest
    fun tearDown() {
        dataStoreScopeJob.cancel()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun notConnectedWhenNoBlobStored() = runTest {
        val repository = FakeLedgerRepository()
        val vm = SettingsViewModel(repository, dataStore)

        advanceUntilIdle()

        assertFalse(vm.uiState.value.connected)
        assertTrue(vm.uiState.value.accountNames.isEmpty())
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun connectedWhenBlobPresentAndListsSimpleFinAccountNames() = runTest {
        val repository = FakeLedgerRepository()
        repository.upsertSimpleFinAccount("acc-2", "Savings", "USD")
        repository.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        dataStore.edit { it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob" }

        val vm = SettingsViewModel(repository, dataStore)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.connected)
        assertEquals(listOf("Checking", "Savings"), vm.uiState.value.accountNames)
    }

    @Test
    fun disconnectClearsBlobAndDeletesSimpleFinData() = runTest {
        val repository = FakeLedgerRepository()
        repository.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        dataStore.edit {
            it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob"
            it[LedgerPreferences.LAST_SYNC_EPOCH_MS] = 12345L
            it[LedgerPreferences.SYNC_START_EPOCH_S] = 6789L
        }
        val vm = SettingsViewModel(repository, dataStore)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.connected)

        vm.disconnect()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.connected)
        assertTrue(vm.uiState.value.accountNames.isEmpty())
        assertTrue(repository.listSimpleFinAccounts().isEmpty())
        val prefs = dataStore.data.first()
        assertNull(prefs[LedgerPreferences.ACCESS_BLOB])
        assertNull(prefs[LedgerPreferences.LAST_SYNC_EPOCH_MS])
        assertNull(prefs[LedgerPreferences.SYNC_START_EPOCH_S])
    }

    @Test
    fun reloadSurfacesSeededBridgeError() = runTest {
        val repository = FakeLedgerRepository()
        dataStore.edit {
            it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob"
            it[LedgerPreferences.LAST_ERROR] = "Bridge says: reauthenticate at bank."
        }

        val vm = SettingsViewModel(repository, dataStore)
        advanceUntilIdle()

        assertEquals("Bridge says: reauthenticate at bank.", vm.uiState.value.bridgeError)
    }

    @Test
    fun noBridgeErrorKeyLeavesBridgeErrorNull() = runTest {
        val repository = FakeLedgerRepository()
        val vm = SettingsViewModel(repository, dataStore)

        advanceUntilIdle()

        assertNull(vm.uiState.value.bridgeError)
    }

    @Test
    fun disconnectClearsBridgeError() = runTest {
        val repository = FakeLedgerRepository()
        dataStore.edit {
            it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob"
            it[LedgerPreferences.LAST_ERROR] = "Connection expired — reconnect."
        }
        val vm = SettingsViewModel(repository, dataStore)
        advanceUntilIdle()
        assertEquals("Connection expired — reconnect.", vm.uiState.value.bridgeError)

        vm.disconnect()
        advanceUntilIdle()

        assertNull(vm.uiState.value.bridgeError)
        val prefs = dataStore.data.first()
        assertNull(prefs[LedgerPreferences.LAST_ERROR])
    }

    @Test
    fun reloadSurfacesSeededBackgroundSyncPrefs() = runTest {
        val repository = FakeLedgerRepository()
        dataStore.edit {
            it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob"
            it[LedgerPreferences.BACKGROUND_SYNC_ENABLED] = true
            it[LedgerPreferences.BACKGROUND_SYNC_HOURS] = 6L
        }

        val vm = SettingsViewModel(repository, dataStore)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.backgroundSyncEnabled)
        assertEquals(6, vm.uiState.value.backgroundSyncHours)
    }

    @Test
    fun noBackgroundSyncKeysLeaveDefaults() = runTest {
        val repository = FakeLedgerRepository()
        val vm = SettingsViewModel(repository, dataStore)

        advanceUntilIdle()

        assertFalse(vm.uiState.value.backgroundSyncEnabled)
        assertEquals(12, vm.uiState.value.backgroundSyncHours)
        // loading starts true (SettingsUiState() default) and only reload() flips it false —
        // proves this state came from reload() actually running, not just the class defaults.
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun setBackgroundSyncPersistsToDataStoreAndUpdatesState() = runTest {
        val repository = FakeLedgerRepository()
        repository.upsertSimpleFinAccount("acc-1", "Checking", "USD")
        dataStore.edit { it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob" }
        val vm = SettingsViewModel(repository, dataStore)
        advanceUntilIdle()
        // Seed connected=true/accountNames via reload() first so the assertions below can prove
        // setBackgroundSync's `.copy(...)` preserves them rather than blanking them.
        assertTrue(vm.uiState.value.connected)
        assertEquals(listOf("Checking"), vm.uiState.value.accountNames)

        vm.setBackgroundSync(true, 24)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.backgroundSyncEnabled)
        assertEquals(24, vm.uiState.value.backgroundSyncHours)
        val prefs = dataStore.data.first()
        assertEquals(true, prefs[LedgerPreferences.BACKGROUND_SYNC_ENABLED])
        assertEquals(24L, prefs[LedgerPreferences.BACKGROUND_SYNC_HOURS])
        // .copy(...) must preserve the other fields — a refactor to SettingsUiState(...) here
        // would silently blank connected/accountNames.
        assertTrue(vm.uiState.value.connected)
        assertEquals(listOf("Checking"), vm.uiState.value.accountNames)
    }

    @Test
    fun disconnectClearsBackgroundSyncPrefsAndResetsToDefaults() = runTest {
        val repository = FakeLedgerRepository()
        dataStore.edit {
            it[LedgerPreferences.ACCESS_BLOB] = "encrypted-blob"
            it[LedgerPreferences.BACKGROUND_SYNC_ENABLED] = true
            it[LedgerPreferences.BACKGROUND_SYNC_HOURS] = 6L
        }
        val vm = SettingsViewModel(repository, dataStore)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.backgroundSyncEnabled)

        vm.disconnect()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.backgroundSyncEnabled)
        assertEquals(12, vm.uiState.value.backgroundSyncHours)
        val prefs = dataStore.data.first()
        assertNull(prefs[LedgerPreferences.BACKGROUND_SYNC_ENABLED])
        assertNull(prefs[LedgerPreferences.BACKGROUND_SYNC_HOURS])
    }
}
