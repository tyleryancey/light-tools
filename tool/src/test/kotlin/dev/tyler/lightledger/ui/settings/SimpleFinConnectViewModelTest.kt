package dev.tyler.lightledger.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.tyler.lightledger.data.AccessUrlCipher
import dev.tyler.lightledger.data.FakeAccessUrlCipher
import dev.tyler.lightledger.data.LedgerPreferences
import dev.tyler.lightledger.simplefin.AccountSet
import dev.tyler.lightledger.simplefin.SimpleFinApi
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

private const val SETUP_TOKEN = "dGVzdC1jbGFpbS11cmw=" // base64 for "test-claim-url"
private const val ACCESS_URL = "https://user:pass@bridge.simplefin.org/simplefin"

/** Configurable-result fake so tests can drive both the success and failure branches of
 * [SimpleFinConnectViewModel.submit] without touching the network. */
private class FakeSimpleFinApi(private val claimResult: Result<String>) : SimpleFinApi {
    var claimCallCount = 0
        private set

    override suspend fun claim(setupTokenBase64: String): Result<String> {
        claimCallCount++
        return claimResult
    }

    override suspend fun fetch(accessUrl: String, startEpochS: Long): Result<AccountSet> =
        Result.success(AccountSet())
}

/** Simulates an AndroidKeyStore encrypt failure (e.g. a key invalidated after a lock-screen
 * change) so the test can prove a post-claim throw routes to [ConnectStatus.Error] instead of
 * crashing `viewModelScope` or stranding the UI on [ConnectStatus.Connecting]. */
private class ThrowingCipher : AccessUrlCipher {
    override fun encryptToBase64(plaintext: String): String =
        throw java.security.GeneralSecurityException("keystore key invalidated")

    override fun decryptFromBase64(blob: String): String =
        throw UnsupportedOperationException("not used by this test")
}

/**
 * [DataStore] is created in [setUp] on the same [StandardTestDispatcher] installed as
 * [Dispatchers.Main] so its internal write-actor coroutine shares `runTest`'s scheduler —
 * `advanceUntilIdle()` then drains both the ViewModel's `viewModelScope` work and the
 * DataStore write in one pass. See tearDown for cleanup of both the temp file and the scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleFinConnectViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dataStoreScopeJob = Job()
    private lateinit var tempDir: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cipher: FakeAccessUrlCipher

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("simplefin-connect-test").toFile()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + dataStoreScopeJob),
        ) { File(tempDir, "connect_test.preferences_pb") }
        cipher = FakeAccessUrlCipher()
    }

    @AfterTest
    fun tearDown() {
        dataStoreScopeJob.cancel()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun successfulClaimStoresEncryptedBlobAndSetsConnected() = runTest {
        val api = FakeSimpleFinApi(Result.success(ACCESS_URL))
        val vm = SimpleFinConnectViewModel(dataStore, cipher, api)

        vm.submit(SETUP_TOKEN)
        advanceUntilIdle()

        assertEquals(ConnectStatus.Connected, vm.uiState.value.status)
        val storedBlob = assertNotNull(dataStore.data.first()[LedgerPreferences.ACCESS_BLOB])
        assertEquals(ACCESS_URL, cipher.decryptFromBase64(storedBlob))
    }

    @Test
    fun failedClaimSetsErrorAndStoresNoBlob() = runTest {
        val api = FakeSimpleFinApi(Result.failure(RuntimeException("SimpleFIN claim HTTP 403")))
        val vm = SimpleFinConnectViewModel(dataStore, cipher, api)

        vm.submit(SETUP_TOKEN)
        advanceUntilIdle()

        assertEquals(ConnectStatus.Error, vm.uiState.value.status)
        assertNotNull(vm.uiState.value.message)
        assertNull(dataStore.data.first()[LedgerPreferences.ACCESS_BLOB])
    }

    @Test
    fun postClaimEncryptFailureSetsErrorNotStuckOnConnecting() = runTest {
        val api = FakeSimpleFinApi(Result.success(ACCESS_URL))
        val vm = SimpleFinConnectViewModel(dataStore, ThrowingCipher(), api)

        vm.submit(SETUP_TOKEN)
        advanceUntilIdle()

        // A throw after a successful claim must be caught (no crash) and surfaced as Error,
        // never left on Connecting — and nothing partial is persisted.
        assertEquals(ConnectStatus.Error, vm.uiState.value.status)
        assertNotNull(vm.uiState.value.message)
        assertNull(dataStore.data.first()[LedgerPreferences.ACCESS_BLOB])
    }

    @Test
    fun blankTokenStaysIdleAndNeverCallsApi() = runTest {
        val api = FakeSimpleFinApi(Result.success(ACCESS_URL))
        val vm = SimpleFinConnectViewModel(dataStore, cipher, api)

        vm.submit("   ")
        advanceUntilIdle()

        assertEquals(ConnectStatus.Idle, vm.uiState.value.status)
        assertEquals(0, api.claimCallCount)
        assertNull(dataStore.data.first()[LedgerPreferences.ACCESS_BLOB])
    }
}
