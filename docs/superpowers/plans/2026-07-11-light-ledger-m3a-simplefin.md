# Light Ledger M3a — SimpleFIN bank sync (core)

> **For agentic workers:** execute with superpowers:subagent-driven-development. Each `### Task N` is one implement → review → fix unit. JVM-testable tasks come first, then UI/integration, then on-device verification. Product spec of record: `/Users/tyleryancey/Documents/light-phone-3-lightos-dev/CLAUDE-light-ledger.md` (§6 SimpleFIN, §7 tests). This plan trusts that spec + the SDK-source facts in Global Constraints below.

**Goal:** Add the core SimpleFIN bank-sync slice to Light Ledger: paste-to-connect, Keystore-encrypted Access URL, a ktor SimpleFIN client, JSON decode, a pure sync engine, the `JOB_SYNC` `@LightJob`, a Review inbox screen, a Settings SimpleFIN section, and Home wiring — end-to-end verifiable against the SimpleFIN demo token on the emulator. **Deferred to M3b** (do NOT build here): the §6.2.6 pending-settle churn migration, QR-scan connect (CAMERA + `LightQrCodeScanner`), and the background-periodic toggle.

**Milestone context:** M0–M2 (manual ledger) is complete on branch `ledger`. M3a wires up the M1-built-but-unused `DedupHash`/`RuleEngine` and the review-inbox fields already on `TransactionEntity`.

---

## Global Constraints

- **Working dir / branch:** worktree `/Users/tyleryancey/Documents/lightphone/light-tools-ledger`, branch `ledger`. Gradle module is `:tool` (NOT `:ledger`). Package `dev.tyler.lightledger`.
- **Java:** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew`. Test: `./gradlew :tool:testDebugUnitTest`. Build/scan: `./gradlew :tool:assembleDebug`. Full DoD build: run `:tool:clean` as a SEPARATE gradle invocation, then `:tool:assembleDebug :tool:testDebugUnitTest` (a combined `clean assembleDebug` fails — the light-sdk plugin's generated manifest gets wiped by clean and isn't a strict predecessor).
- **Money:** `Long` minor units, spend negative; `BigDecimal` only at the string-parse boundary. SimpleFIN `amount` is a decimal **string**; `posted` is unix **seconds** → device-local `LocalDate.toEpochDay()`.
- **Plugin blocked patterns (regex over raw source lines incl. string literals — build fails):** `getSystemService(`, `startActivity(`/`startService(`/`bindService(`/`registerReceiver(`, `contentResolver`, `android.app.*`, `android.content.Context/Intent/...`, casts `as …Activity`/`as Context…`, and ALL reflection: `.javaClass`, `.java.<word>`, `Class.forName(`, `.getMethod(`/`.getDeclaredMethod(`/`.getField(`/`.getDeclaredField(`, `MethodHandles`. **Do not emit these tokens anywhere, including strings/comments.** Consequence: **no `ConnectivityManager`/network-state check** — rely on HTTP failure → `LightJobResult.Retry`.
- **Dependency allow-list** already covers everything M3a needs (`io.ktor` any artifact, `androidx.work`, `androidx.datastore`, `androidx.room`, `kotlinx-serialization`, `kotlinx-coroutines`, `okhttp`). All aliases are already in `gradle/libs.versions.toml`; the `kotlin.serialization` + `ksp` Gradle plugins are already applied to `tool/build.gradle.kts`.
- **SDK facts (source-verified):**
  - **Background job:** top-level `@LightJob("simplefin-sync") val simpleFinSyncJob: LightJobHandler = { ctx, input -> … }` where `typealias LightJobHandler = suspend (SealedLightContext, Map<String,String>) -> LightJobResult`. Results: `LightJobResult.Success(map=emptyMap())`, `object Retry` (transient → auto-reschedule, no map), `Error(map=emptyMap())` (permanent). Handler is self-contained: build the DB with `ctx.buildDatabase(LedgerDatabase::class.java, "ledger.db")`, read prefs with `ctx.dataStore`. Enqueue one-shot from a screen: `LightWork.enqueue(lightContext, "simplefin-sync"): Boolean`. Observe: `LightWork.observe(lightContext, "simplefin-sync"): Flow<LightJobState>` (Enqueued/Running/Succeeded(map)/Failed(map)/Cancelled/NotScheduled).
  - **Keystore cipher (copy from `examples/authenticator`):** `TotpKeystore.kt` (AndroidKeyStore AES-256-GCM key gen) + `TotpSecretCipher.kt` (`encrypt(String): ByteArray` = `iv(12)||ciphertext`; `decrypt(ByteArray): String`). Imports `android.security.keystore.*`, `javax.crypto.*`, `java.security.KeyStore` — all allowed.
  - **ktor client (copy shape from `examples/weather/WeatherApi.kt`):** `HttpClient(OkHttp){ install(ContentNegotiation){ json(Json{ ignoreUnknownKeys = true }) } }`; `client.get(url){ header("Authorization", "Basic $b64") }`; typed `response.body()`; `response.status.isSuccess()`; `client.close()`.
  - **DataStore:** `lightContext.dataStore: DataStore<Preferences>` on every screen and inside the job (`ctx.dataStore`). Keys via `stringPreferencesKey`/`longPreferencesKey`; read `dataStore.data.first()[KEY]`; write `dataStore.edit { it[KEY] = … }`.
  - **Screens/VMs:** `LightScreen<Unit, VM>` / `SimpleLightScreen<Unit>`; `LightViewModel<Unit>`; `override val viewModelClass`, `override fun createViewModel()`, `@Composable override fun Content()`; `navigateTo(screenFactory){ result -> }`; `goBack(result)`; `onBackPressed(): Boolean` (return true to consume). `LightScreen.goBack()` routes through `viewModel.onBackPressed()` — only pops when it returns false (see M2's AddEntry fix). sdk:ui components only; LP3 panel ~411×472dp (weight/`gridUnitsAsDp()` sizing, scroll containers). Reuse existing `CategoryGrid`/`AmountKeypad` and the `RoomLedgerRepository.getInstance { lightContext.buildDatabase(...) }` pattern.
- **Existing domain to reuse (do not reimplement):** `DedupHash.compute(accountId, postedEpochDay, amountMinor, payee): String` and `.normalizePayee(String)`; `RuleEngine.matchCategory(rules: List<CategoryRule>, payee): Long?` and `.shouldCreateRule(past: List<PastConfirmation>, normalizedPayee, categoryId, threshold=3): Boolean` (its own `CategoryRule`/`PastConfirmation` types); `AmountParser.parseToMinorUnits(String)`; `LedgerMath`. Constants: `AccountKind.{MANUAL,CSV,SIMPLEFIN}`, `TransactionStatus.{NEEDS_REVIEW,CONFIRMED}`, `TransactionSource.{MANUAL,CSV,SIMPLEFIN}`.
- **Schema:** all 5 entities are already in `@Database(version=1)`. M3a adds NO new entity and NO index → **no version bump / no migration**. `TransactionEntity` already has `status`/`source`/`externalId`/`pendingExternal`/`dedupHash`/`categoryId?` + a `(accountId, externalId)` unique index. Account upsert-by-externalId is done via find-then-write in a `@Transaction` DAO method (no account index needed).
- **Secrets:** never log the Access URL or its blob. (`local.properties` commits a `gpr.key` — out of scope; flagged for rotation.)
- **Commit** after every task; trailer `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. Push `ledger` after each task.

---

### Task 1: Build config, permissions, DataStore keys

**Files:** modify `tool/build.gradle.kts`, `tool/lighttool.toml`; create `tool/src/main/kotlin/dev/tyler/lightledger/data/LedgerPreferences.kt`.

- Add to `tool/build.gradle.kts` `dependencies {}` (aliases already cataloged): `implementation(libs.ktor.client.core)`, `implementation(libs.ktor.client.okhttp)`, `implementation(libs.ktor.client.content.negotiation)`, `implementation(libs.ktor.serialization.json)`, `implementation(libs.kotlinx.serialization.json)`, `implementation(libs.androidx.datastore.preferences)`, `implementation(libs.androidx.work.runtime)`. (serialization + ksp plugins already applied — no plugin change.)
- `tool/lighttool.toml`: set `permissions = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"]`. (CAMERA deferred to M3b.)
- `LedgerPreferences.kt`: an `object LedgerPreferences` with `stringPreferencesKey("simplefin_access_blob")`, `longPreferencesKey("simplefin_last_sync_epoch_ms")`, `longPreferencesKey("simplefin_sync_start_epoch_s")`.

**Verify:** `:tool:assembleDebug` BUILD SUCCESSFUL, no plugin-scan violations. (No test — config task.)

---

### Task 2: SimpleFIN models + mapper + decode test (JVM)

**Files:** create `simplefin/SimpleFinModels.kt`, `simplefin/SimpleFinMapper.kt`, `tool/src/test/resources/simplefin/*.json` fixtures, `tool/src/test/kotlin/dev/tyler/lightledger/simplefin/SimpleFinDecodeTest.kt`.

- **Models** (`@Serializable`, package `dev.tyler.lightledger.simplefin`): `AccountSet(errors: List<String> = emptyList(), accounts: List<SimpleFinAccount> = emptyList())`; `SimpleFinAccount(id, name, currency, @SerialName("org") org: SimpleFinOrg? = null, transactions: List<SimpleFinTransaction> = emptyList())` (+ balance fields optional/ignored); `SimpleFinTransaction(id, posted: Long, amount: String, description: String = "", payee: String? = null, memo: String? = null, pending: Boolean = false)`. Decode with `Json { ignoreUnknownKeys = true }`.
- **Mapper** (pure Kotlin, Android-free): `object SimpleFinMapper { fun toMappedTransactions(account: SimpleFinAccount, currencyExponent: Int = 2): List<MappedExternalTxn> }` where `MappedExternalTxn(externalId, postedEpochDay, amountMinor, payee, memo, pending)`. Rules: `amount` string → `AmountParser.parseToMinorUnits` (reuse) or BigDecimal→minor; `posted` (unix s) → `Instant.ofEpochSecond(posted).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()`; payee = `payee ?: description` (fall back), memo = `memo ?: ""`.
- **Fixtures + `SimpleFinDecodeTest`** (kotlin.test), covering spec §6.1.4 + §7 `SimpleFinDecodeTest`: unknown-key tolerance, string-amount → minor (incl. negative "-4.50" and positive), unix→epochDay, missing-`payee` → `description` fallback, `errors[]` surfaced, multiple accounts/transactions, empty accounts. Load fixtures from `src/test/resources`.

**Verify:** `:tool:testDebugUnitTest --tests '*SimpleFinDecodeTest'` green.

---

### Task 3: Data-layer additions (repo + DAO + Fake)

**Files:** create `data/RuleDao.kt`; modify `data/LedgerDatabase.kt`, `data/AccountDao.kt`, `data/TransactionDao.kt`, `data/LedgerRepository.kt`, `data/RoomLedgerRepository.kt`, `data/LedgerModels.kt` (+ `ReviewItem`); modify test `data/FakeLedgerRepository.kt`; add `data/LedgerRepositoryContractTest.kt` cases.

- `RuleDao` (internal): `insert(RuleEntity): Long`, `listEnabled(): List<RuleEntity>` (`WHERE enabled = 1`). Add `abstract fun ruleDao(): RuleDao` to `LedgerDatabase` (rules table already exists — no version bump).
- `AccountDao`: add `findByExternalId(externalId: String): AccountEntity?`, `insert` already exists; add an `@Transaction` upsert helper in the repo (find→insert/update) — or DAO `updateAccount`. `TransactionDao`: add `findByExternalId(accountId, externalId): TransactionEntity?`, `findByDedupHash(dedupHash): List<TransactionEntity>`, `listNeedsReview(): List<TransactionEntity>` (`WHERE status='NEEDS_REVIEW' ORDER BY postedEpochDay DESC, id DESC`), `confirm(id, categoryId)` (`UPDATE … SET categoryId=:categoryId, status='CONFIRMED' WHERE id=:id`), and an update for external mutable fields (`UPDATE … SET amountMinor=…, payee=…, postedEpochDay=…, pendingExternal=… WHERE id=:id` — must NOT touch categoryId/status), plus `adoptExternalId(id, externalId)` for cross-source linking.
- `ReviewItem` domain model in `LedgerModels.kt`: `data class ReviewItem(id: Long, postedEpochDay: Long, amountMinor: Long, payee: String, accountName: String, categoryId: Long?)`.
- `LedgerRepository` interface + `RoomLedgerRepository` + `FakeLedgerRepository`: add `suspend fun upsertSimpleFinAccount(externalId, name, currency): Long`; `suspend fun findTransactionByExternal(accountId, externalId): Transaction?`; `suspend fun insertExternalTransaction(row): Long`; `suspend fun updateExternalTransactionFields(id, amountMinor, payee, postedEpochDay, pending)`; `suspend fun findByDedupHash(dedupHash): List<Transaction>` (needs Transaction extended or a lightweight row — extend the domain `Transaction` with `status/source/externalId/pendingExternal/dedupHash` OR add a `TxnRow` internal model; pick the smaller change and keep FakeLedgerRepository faithful); `suspend fun listReviewInbox(): List<ReviewItem>`; `suspend fun confirmReview(id, categoryId)`; `suspend fun skipReview(id)` (no-op leaving NEEDS_REVIEW, or a marker — spec: skip just leaves it, so `skipReview` may be a client-side advance only — implement as repo-less advance in the ViewModel, OR a no-op); `suspend fun pastConfirmationsFor(normalizedPayee): List<PastConfirmation>` and `suspend fun insertRule(payeeContains, categoryId)` + `suspend fun listRules(): List<CategoryRule>` (map RuleEntity ↔ RuleEngine.CategoryRule) for the 3-strike learning + auto-categorize.
- Keep `FakeLedgerRepository` a faithful in-memory mirror (same ordering/filtering) so ViewModel tests predict on-device behavior. Extend `LedgerRepositoryContractTest` for: upsert account by externalId (insert then update-same), insert external txn + find-by-external, confirmReview sets CONFIRMED+category, listReviewInbox returns only NEEDS_REVIEW newest-first, findByDedupHash, insertRule/listRules.

**Verify:** `:tool:testDebugUnitTest` green (incl. new contract cases); `:tool:assembleDebug` (Room KSP accepts new queries).

---

### Task 4: SyncEngine (pure) + test (JVM)

**Files:** create `simplefin/SyncEngine.kt`, `tool/src/test/kotlin/dev/tyler/lightledger/simplefin/SyncEngineTest.kt`.

- Pure decision function, Android-free: given the fetched mapped rows for an account plus lookups (existing-by-external, existing-by-dedupHash, enabled rules), produce a `SyncPlan` of operations: `Insert(row, status, categoryId?)`, `UpdateExternalFields(id, …)`, `LinkCrossSource(existingId, externalId)`. Logic:
  - For each mapped txn compute `dedupHash = DedupHash.compute(accountId, postedEpochDay, amountMinor, payee)`.
  - If an existing row has this `(accountId, externalId)` → `UpdateExternalFields` (amount/payee/posted/pending) — never category/status.
  - Else if a non-SIMPLEFIN existing row matches `dedupHash` within ±1 day → `LinkCrossSource` (adopt externalId onto it; keep its category/status) — cross-source dedup.
  - Else → `Insert`; category/status from `RuleEngine.matchCategory(rules, payee)`: match → `CONFIRMED` + categoryId; no match → `NEEDS_REVIEW`, categoryId null. `pendingExternal` from payload.
- `SyncEngineTest`: new→NEEDS_REVIEW; rule-match→CONFIRMED+category; existing-external→UpdateExternalFields (category/status untouched); cross-source dedup within ±1 day→Link; outside window→Insert. (Pending-settle churn is M3b — do NOT implement here.)

**Verify:** `:tool:testDebugUnitTest --tests '*SyncEngineTest'` green.

---

### Task 5: Access-URL cipher (Keystore)

**Files:** create `data/AccessUrlKeystore.kt`, `data/AccessUrlCipher.kt` (interface + Android impl + Base64 helpers).

- Copy `TotpKeystore.kt`/`TotpSecretCipher.kt` verbatim, rename to `AccessUrlKeystore`/`AndroidAccessUrlCipher`, `KEY_ALIAS = "simplefin_access_key"`. Define `interface AccessUrlCipher { fun encryptToBase64(plaintext: String): String; fun decryptFromBase64(blob: String): String }`; the Android impl wraps the GCM `ByteArray` with `android.util.Base64` (or `java.util.Base64`). Keep a tiny `FakeAccessUrlCipher` (reversible non-crypto) in test sources for connect/VM tests.

**Verify:** `:tool:assembleDebug` (compiles, no blocked tokens — watch for reflection/`Context`). Crypto correctness verified on-device (Task 13); the interface is exercised via the fake in Tasks 6/10.

---

### Task 6: SimpleFinClient (ktor)

**Files:** create `simplefin/SimpleFinClient.kt`, `tool/src/test/kotlin/dev/tyler/lightledger/simplefin/SimpleFinClientTest.kt`.

- `class SimpleFinClient(private val httpClientFactory: () -> HttpClient = { defaultClient() })` with:
  - `suspend fun claim(setupTokenBase64: String): Result<String>` — Base64-decode → claim URL; `POST` empty body; return response body (the Access URL) trimmed. Errors → `Result.failure`.
  - `suspend fun fetch(accessUrl: String, startEpochS: Long): Result<AccountSet>` — parse `https://user:pass@host/path`, strip creds from the URL, set `header("Authorization", "Basic " + base64("user:pass"))`, `GET .../accounts?start-date=$startEpochS&pending=1`, decode body → `AccountSet`. Non-2xx → failure with status.
- Extract the URL-cred parsing into a pure helper `parseAccessUrl(accessUrl): Pair<UrlWithoutCreds, BasicAuthHeader>` and unit-test it in `SimpleFinClientTest` (no network — test only the pure parse/auth-encoding; the HTTP path is live-tested on-device).

**Verify:** `:tool:testDebugUnitTest --tests '*SimpleFinClientTest'` green; `:tool:assembleDebug`.

---

### Task 7: JOB_SYNC @LightJob handler

**Files:** create `simplefin/LedgerJobs.kt`.

- Top-level `@LightJob("simplefin-sync") val simpleFinSyncJob: LightJobHandler = { ctx, _ -> … }`. Self-contained: build repo via `RoomLedgerRepository.getInstance { ctx.buildDatabase(LedgerDatabase::class.java, RoomLedgerRepository.DATABASE_NAME) }`; read `ctx.dataStore` for the encrypted blob + watermark; `AndroidAccessUrlCipher().decryptFromBase64(blob)` (absent → `LightJobResult.Success()` — not configured is not an error); compute `start-date` (first sync = now−60d in epoch-s; else `sync_start_epoch_s`−7d overlap); `SimpleFinClient().fetch(accessUrl, startDate)` — on `IOException`/5xx → `Retry`, on 4xx → `Error(mapOf("reason" to code))`; for each account upsert (`upsertSimpleFinAccount`), run `SyncEngine.plan(...)`, apply the plan via repo; write `simplefin_last_sync_epoch_ms` + advance `simplefin_sync_start_epoch_s` to fetch time; return `Success(mapOf("new" to count))`. **No `getSystemService`/`Context` tokens.** Never log the URL.

**Verify:** `:tool:assembleDebug` (compiles; `@LightJob` registered). Behavior verified on-device (Task 13). Optionally factor the non-IO orchestration to keep it thin.

---

### Task 8: ReviewViewModel + test (JVM)

**Files:** create `ui/review/ReviewViewModel.kt`, `tool/src/test/kotlin/dev/tyler/lightledger/ui/review/ReviewViewModelTest.kt`.

- `class ReviewViewModel(repository) : LightViewModel<Unit>()` exposing `uiState: StateFlow<ReviewUiState>` where `ReviewUiState(item: ReviewItem? = null, remaining: Int = 0, categories: List<Category> = emptyList(), done: Boolean = false)`. On init/`onScreenShow`: `ensureSeeded()`, load `listReviewInbox()` + `listCategories()`, show first item (or `done=true` if empty). `confirm(categoryId)`: `repository.confirmReview(item.id, categoryId)`; then 3-strike learning — `pastConfirmationsFor(normalizedPayee)` + `RuleEngine.shouldCreateRule(...)` → `insertRule(normalizedPayee, categoryId)`; advance to next item. `skip()`: advance to next item without changing it. `onBackPressed()`: false (let it pop).
- `ReviewViewModelTest` (StandardTestDispatcher boilerplate like `HomeViewModelTest`): loads inbox on init; confirm sets CONFIRMED+category and advances; empty inbox → done; skip advances leaving the item; after 3 same-payee-same-category confirms a rule is inserted (seed 3 NEEDS_REVIEW rows same normalized payee).

**Verify:** `:tool:testDebugUnitTest --tests '*ReviewViewModelTest'` green.

---

### Task 9: ReviewScreen (UI)

**Files:** create `ui/review/ReviewScreen.kt`.

- `class ReviewScreen(sealedActivity, repository) : LightScreen<Unit, ReviewViewModel>`. Content: `LightTopBar` (BACK), the current `ReviewItem` (date, payee, large amount via `LightTextVariant.Heading`, account name small), a `CategoryGrid` (reuse Task-10 M2 component) whose `onSelect` calls `viewModel.confirm(it.id)`, a Skip affordance (bottom bar). Empty/done state: centered "Nothing to review." then `goBack(Unit)` affordance. sdk:ui only; LP3 sizing (weight/gridUnitsAsDp; scroll if needed).

**Verify:** `:tool:assembleDebug` clean. On-device in Task 13.

---

### Task 10: SimpleFIN connect flow (paste)

**Files:** create `ui/settings/SimpleFinConnectViewModel.kt`, `ui/settings/SimpleFinConnectScreen.kt`, `tool/src/test/kotlin/dev/tyler/lightledger/ui/settings/SimpleFinConnectViewModelTest.kt`.

- `class SimpleFinConnectViewModel(dataStore, cipher: AccessUrlCipher, client: SimpleFinClient) : LightViewModel<Unit>()` with a paste field state machine: `submit(setupToken)` → `client.claim` → on success `cipher.encryptToBase64(accessUrl)` → `dataStore.edit { it[ACCESS_BLOB] = blob }` → `connected=true`; on failure show an error message (no crash, calm copy). Never log the token/URL.
- `SimpleFinConnectScreen`: a `LightTextInputEditor` paste field (reuse the M2 AddEntry payee pattern), submit label "CONNECT".
- `SimpleFinConnectViewModelTest`: with `FakeAccessUrlCipher` + a fake `SimpleFinClient` (inject via a claim lambda) — successful claim stores a blob and sets connected; failed claim sets an error and stores nothing.

**Verify:** `:tool:testDebugUnitTest --tests '*SimpleFinConnectViewModelTest'` green; `:tool:assembleDebug`.

---

### Task 11: Settings SimpleFIN section

**Files:** modify `ui/settings/SettingsScreen.kt` (+ a `SettingsViewModel` if needed for connected-state).

- Add a "SimpleFIN" row/section to the existing Settings shell. Not connected → navigate to `SimpleFinConnectScreen`. Connected (blob present) → show synced account names (`list SIMPLEFIN accounts`), **Sync now** (`LightWork.enqueue(lightContext, "simplefin-sync")` + observe → "syncing…"/"synced"), **Disconnect & forget** (`dataStore.edit { it.remove(ACCESS_BLOB); it.remove(watermark keys) }` + delete SIMPLEFIN accounts/their txns via repo + `LightWork.cancel`). Calm copy; About/privacy paragraph optional (M5). sdk:ui only.

**Verify:** `:tool:assembleDebug` clean. On-device in Task 13.

---

### Task 12: Home wiring (inbox + opportunistic sync)

**Files:** modify `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`.

- Wire the existing "N to review →" affordance (already conditional on `needsReviewCount`) to `navigateTo { ReviewScreen(it, repository) }`.
- On `onScreenShow`: if a SimpleFIN blob exists in `dataStore` AND `now − simplefin_last_sync_epoch_ms > 6h` → `LightWork.enqueue(lightContext, "simplefin-sync")`; surface a small "syncing…" text via `LightWork.observe` collected in the VM (never a blocking spinner); reload the summary + review count when the job reaches `Succeeded`.

**Verify:** `:tool:assembleDebug` clean; `:tool:testDebugUnitTest` (HomeViewModelTest still green; extend if the VM gains sync-state logic). On-device in Task 13.

---

### Task 13: On-device M3a verification (Light_Phone AVD)

Verification only (fix-forward commits if bugs surface). Full clean build+test (separate `clean` invocation), `:tool:installDebug`, launch. Then, using the reusable demo setup token `aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9zaW1wbGVmaW4vY2xhaW0vZGVtbw==`:
1. Settings → SimpleFIN → Connect → paste the demo token → CONNECT → confirm "connected".
2. Sync now (or reopen Home to trigger opportunistic sync) → confirm "syncing…" then imported transactions appear in the review count.
3. Home "N to review →" → ReviewScreen → categorize several; confirm a rule is learned after the same payee gets the same category 3× (re-sync or seed to exercise), and confirmed items leave the inbox and appear in History.
4. Settings → Disconnect & forget → confirm SIMPLEFIN accounts/txns gone and blob cleared.
5. Hardware-back exits each screen one level; no crash; no visual overflow at 1080×1240.

If bridge.simplefin.org/demo is unavailable, fall back to injecting a fixture `AccountSet` through the sync path to exercise decode→sync→review on-device.

**M3a DoD:** JVM suite green, `installDebug` runs, no plugin/lint violations, every new screen reachable + exit-able, the demo-token connect→sync→review→disconnect loop works.
