# Light Ledger M3b-features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Each `### Task N` is one implement → review → fix unit. Hardening/JVM-testable tasks first, then the on-device features, then on-device verification. Product spec of record: `/Users/tyleryancey/Documents/light-phone-3-lightos-dev/CLAUDE-light-ledger.md` (§6.1 QR, §6.3 scheduling, §3/§7 currency). Design spec: `docs/superpowers/specs/2026-07-16-light-ledger-m3b-features-design.md`.

**Goal:** Complete SimpleFIN to spec — QR-scan connect, a background-periodic sync toggle, non-USD display on History/Review via a shared currency-aware formatter, and dead-code cleanup — the additive/hardening follow-on to M3b-core.

**Architecture:** One shared `LedgerFormat` util replaces three per-screen copies and makes History/Review currency-aware (account currency threaded onto the `Transaction`/`ReviewItem` models); a periodic sync toggle scheduled under a distinct WorkManager tag; a QR scanner screen that re-routes the scanned token into the existing paste `submit()` path. No new claim/sync logic; no schema migration.

**Tech Stack:** Kotlin, Room (no migration), Jetpack Compose (sdk:ui), Light SDK (`LightQrCodeScanner`, `LightWork.enqueuePeriodic`, `LightScreen`/`LightViewModel`), kotlin.time, JUnit/kotlin.test, JDK 17.

## Global Constraints

- **Working dir / branch:** worktree `/Users/tyleryancey/Documents/lightphone/light-tools-ledger`, branch `ledger`. Module `:tool` (NOT `:ledger`). Package `dev.tyler.lightledger`. Baseline HEAD `d9d76f3` (M3b-core tip); 168 unit tests green.
- **Java:** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew`. Test: `./gradlew :tool:testDebugUnitTest`. Build/scan: `./gradlew :tool:assembleDebug`. Full DoD build: run `:tool:clean` as a SEPARATE gradle invocation, then `:tool:assembleDebug :tool:testDebugUnitTest`.
- **Money:** `Long` minor units, spend negative. Never sum across currencies. `amountMinor / 10^exponent` for display, exponent = `CurrencyExponent.of(code)`.
- **NO SCHEMA MIGRATION.** Currency threading adds a `JOIN accounts` to existing queries (a projection), NOT a column. DB stays `@Database(version=1)`. Do NOT add a column/index/Migration.
- **Plugin blocked patterns (regex over raw source incl. strings/comments — build fails):** `getSystemService(`, `startActivity(`/`startService(`/etc., `contentResolver`, `android.app.*`, `android.content.Context/Intent`, casts `as …Activity`/`as Context…`, ALL reflection (`.javaClass`, `.java.<word>`, `Class.forName(`, `.getMethod(`, `MethodHandles`). `::class.java` only where the SDK requires it (`viewModelClass`). `com.thelightphone.sdk.*` and `androidx.datastore.*` allowed. Keep `ui/shared/LedgerFormat.kt` Android-import-free (java.text/java.time only) so it's JVM-testable.
- **CAMERA is allowed:** `LightToolPolicy.ALLOWED_PERMISSIONS` already contains `android.permission.CAMERA` and `PERMISSION_IMPLIED_FEATURES` auto-emits `<uses-feature android.hardware.camera required=false>`. No hand-written AndroidManifest (the plugin generates it; a user manifest fails the build).
- **Dependency allow-list** already covers everything (`io.ktor`, `androidx.work`, `androidx.datastore`, `androidx.room`, `kotlinx-*`); no new deps. serialization + ksp plugins already applied.
- **SDK/code facts (verified this session):**
  - **`LightQrCodeScanner` (USE THE CLIENT WRAPPER):** `com.thelightphone.sdk.LightQrCodeScanner` (in `sdk/client/.../LightClientUiUtils.kt`): `@Composable fun LightQrCodeScanner(onScanned: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, title: String = "Scan QR Code")`. Internally wires the CAMERA permission flow; fires `onScanned` exactly once; host app MUST declare `android.permission.CAMERA`; DEFER navigation to a `LaunchedEffect` (pop the scanner, then act). Mirror example: `examples/authenticator/src/main/kotlin/com/thelightphone/authenticator/AuthenticatorQrScannerScreen.kt`.
  - **`LightWork.enqueuePeriodic(lightContext: SealedLightContext, jobKey: String, repeatInterval: kotlin.time.Duration, inputData: Map<String,String> = emptyMap(), tag: String? = null): Boolean`** (`sdk/client/.../LightWork.kt`) — 15-min WorkManager floor; uniqueness slot = `tag ?: jobKey` with `ExistingPeriodicWorkPolicy.UPDATE`. `cancel(lightContext, jobKeyOrTag)`, `observe(...)`, `getState(...)`. `import kotlin.time.Duration.Companion.hours`. The Screen has `protected val lightContext` (a `SealedLightContext`).
  - **`CurrencyExponent.of(code: String): Int`** (`domain/CurrencyExponent.kt`, from M3b-core): 0 for JPY/KRW/…, 3 for BHD/KWD/…, else 2.
  - **Home's exponent-aware amount formatter to EXTRACT** (`ui/home/HomeScreen.kt`): `private fun formatAmount(amountMinor, currencyCode): String { val format = NumberFormat.getCurrencyInstance(Locale.US); try { format.currency = Currency.getInstance(currencyCode) } catch (IllegalArgumentException) {}; val major = BigDecimal.valueOf(amountMinor).movePointLeft(CurrencyExponent.of(currencyCode)); return format.format(major) }`.
  - **Models:** `data class Transaction(id, accountId, postedEpochDay, amountMinor, payee, memo, categoryId)` and `data class ReviewItem(id, postedEpochDay, amountMinor, payee, accountName, categoryId)` in `data/LedgerModels.kt` — neither carries currency yet.
  - **DAO queries to make currency-aware** (`data/TransactionDao.kt`): `listConfirmedInRange(start, end): List<TransactionEntity>` (backs History's `listTransactions`), `getById(id): TransactionEntity?` (backs `getTransaction`), `listNeedsReviewWithAccount(): List<ReviewItem>` (already `INNER JOIN accounts a ON t.accountId=a.id WHERE t.status='NEEDS_REVIEW' ORDER BY t.postedEpochDay DESC, t.id DESC`, backs the review inbox). Precedent projection: `listConfirmedAmountsInRange` returns `CategoryAmountRow(categoryId, amountMinor, currency)` via a JOIN.
  - **Settings:** `SettingsViewModel(repository, dataStore) : LightViewModel<Unit>`; `SettingsUiState(connected, accountNames, loading, bridgeError)`; `reload()` reads `prefs[ACCESS_BLOB] != null` + `LAST_ERROR` + `listSimpleFinAccounts()`; `disconnect()` removes `ACCESS_BLOB`/`LAST_SYNC_EPOCH_MS`/`SYNC_START_EPOCH_S`/`LAST_ERROR` then `deleteSimpleFinData()`. `SettingsScreen.ConnectedSimpleFinSection(accountNames, statusText, bridgeError, onSyncNow, onDisconnect)`; the connected branch calls `LightWork.enqueue/observe(lightContext, SIMPLEFIN_SYNC_JOB_KEY)`.
  - **Connect flow:** `SimpleFinConnectScreen(sealedActivity) : LightScreen<Unit, SimpleFinConnectViewModel>`; `createViewModel() = SimpleFinConnectViewModel(lightContext.dataStore, AndroidAccessUrlCipher(), SimpleFinClient())`; `LightTextInputEditor(... onSubmit = { viewModel.submit(it.toString()) } ...)`. Reached from `SettingsScreen` not-connected row via `navigateTo(screenFactory = { SimpleFinConnectScreen(it) }) { viewModel.reload() }`.
  - **Constants:** `data/LedgerConstants.kt` has `const val SIMPLEFIN_SYNC_JOB_KEY = "simplefin-sync"`. `data/LedgerPreferences.kt` has `ACCESS_BLOB`, `LAST_SYNC_EPOCH_MS`, `SYNC_START_EPOCH_S`, `LAST_ERROR`.
- **FakeLedgerRepository fidelity is load-bearing:** every query change must be mirrored in the Fake with identical filter/join/order semantics (contract tests run against the Fake only).
- **Commit** after every task; trailer `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. Push `ledger` after each task. Never force-push (rebase local onto origin if the scheduled sync-upstream diverged it).

---

### Task 1: Shared `LedgerFormat` util + Home rewire

**Files:**
- Create: `tool/src/main/kotlin/dev/tyler/lightledger/ui/shared/LedgerFormat.kt`
- Create: `tool/src/test/kotlin/dev/tyler/lightledger/ui/shared/LedgerFormatTest.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeScreen.kt` (use `LedgerFormat.amount`, delete the private `formatAmount`)

**Interfaces:**
- Produces: `object LedgerFormat { fun amount(amountMinor: Long, currencyCode: String): String; fun date(epochDay: Long): String }`.
- Consumes: `dev.tyler.lightledger.domain.CurrencyExponent`.

- `LedgerFormat.amount` = Home's current exponent-aware body verbatim (see Global Constraints): `NumberFormat.getCurrencyInstance(Locale.US)`, set `currency = Currency.getInstance(code)` in a try/catch (unknown code → USD fallback), scale via `BigDecimal.valueOf(amountMinor).movePointLeft(CurrencyExponent.of(code))`, `format.format(major)`.
- `LedgerFormat.date(epochDay)` = the epochDay→date rendering History and Review currently use. READ `HistoryScreen`/`ReviewScreen` for the exact current format and reproduce it EXACTLY (e.g. `LocalDate.ofEpochDay(epochDay)` → `"JUL 14, 2026"`, month abbreviation uppercased). If they format inline rather than via a helper, match that output byte-for-byte.
- **Android-free** — only `java.text`, `java.math`, `java.time`, `java.util`, and `dev.tyler.lightledger.domain.CurrencyExponent` imports. This keeps it JVM-testable.
- `HomeScreen`: replace calls to the private `formatAmount(x, cur)` with `LedgerFormat.amount(x, cur)` and DELETE the private `formatAmount`. (`primaryTotalText` stays; it now calls `LedgerFormat.amount`. `monthTitle` is unrelated — leave it.)
- **`LedgerFormatTest`** (kotlin.test): `amount(450, "USD") == "$4.50"`; `amount(1200, "JPY") == "¥1,200"` (exponent 0); `amount(1234, "BHD")` renders 1.234 with 3 fraction digits (assert it contains `"1.234"`); `amount(500, "XYZ")` (unknown) falls back to USD 2-dp (`"$5.00"`); `amount(-450, "USD") == "-$4.50"`; `date(<a known epochDay>)` equals the expected string (compute the expected via the same `LocalDate.ofEpochDay` chain, zone-independent).

**Verify:** `./gradlew :tool:testDebugUnitTest` green (168 + new `LedgerFormatTest`); `./gradlew :tool:assembleDebug` clean.

---

### Task 2: Thread currency onto History/Review (I-2) + dead-code cleanup

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/LedgerModels.kt` (`Transaction`, `ReviewItem` gain `currency`)
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/TransactionDao.kt` (currency-aware projections)
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/RoomLedgerRepository.kt` (map the new projections)
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/data/FakeLedgerRepository.kt` (mirror the joins)
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/ui/history/HistoryScreen.kt`, `ui/review/ReviewScreen.kt` (use `LedgerFormat` with row currency; delete their private `formatAmount`/date copies)
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/data/LedgerRepositoryContractTest.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/LedgerRepository.kt`, `RoomLedgerRepository.kt`, `data/TransactionDao.kt`, `FakeLedgerRepository.kt` (remove dead `findDedupCandidates`/`findByDedupHash`)

**Interfaces:**
- Produces: `Transaction(..., val currency: String)`, `ReviewItem(..., val currency: String)`.
- Consumes: `LedgerFormat` (Task 1).

- **Models:** add `val currency: String` to `Transaction` and `ReviewItem` (last field).
- **DAO — Review inbox:** change `listNeedsReviewWithAccount` to project `a.currency` into `ReviewItem` (it already `INNER JOIN accounts a`). Room maps `t.<fields>` + `a.name AS accountName` + `a.currency AS currency` directly into `ReviewItem` (add `currency` to the SELECT list; keep the `WHERE status='NEEDS_REVIEW' ORDER BY t.postedEpochDay DESC, t.id DESC`).
- **DAO — History + single:** `listConfirmedInRange` and `getById` return `TransactionEntity` (no currency). Add currency-aware projections that JOIN accounts, e.g. a `TransactionWithCurrencyRow` projection (`SELECT t.*, a.currency AS currency FROM transactions t JOIN accounts a ON t.accountId=a.id WHERE …`), OR a projection with the exact `Transaction` fields + `currency`. Then `RoomLedgerRepository.listTransactions(month)` and `getTransaction(id)` build `Transaction(..., currency = row.currency)`. Keep History's ordering (`ORDER BY postedEpochDay DESC, id DESC`) and `getTransaction`'s single-row semantics.
- **Fake:** mirror — `listReviewInbox`/`listTransactions`/`getTransaction` attach the row's account currency (look up `accounts.first { it.id == txn.accountId }?.currency`; drop rows whose account is gone, matching INNER JOIN). Use the same ordering.
- **History/Review screens:** replace their private `formatAmount(amountMinor)` (which did `/100.0`+`Locale.US`) and any inline date formatting with `LedgerFormat.amount(txn.amountMinor, txn.currency)` and `LedgerFormat.date(txn.postedEpochDay)`. DELETE the now-dead private `formatAmount`/date helpers on those screens.
- **Dead code:** remove `LedgerRepository.findDedupCandidates`, its `RoomLedgerRepository` impl, its `FakeLedgerRepository` impl, and `TransactionDao.findByDedupHash` — confirmed unused after M3b-core's runner rewire (grep to confirm no remaining caller before deleting). If a test referenced `findDedupCandidates`, update/remove that assertion.
- **Contract test:** seed a SIMPLEFIN account with `currency="JPY"` + a MANUAL account `currency="USD"`, insert a confirmed txn in each; assert `listTransactions(month)` returns each `Transaction` with its account's currency, `getTransaction(id)` returns the right currency, and a NEEDS_REVIEW SIMPLEFIN txn's `ReviewItem.currency` is "JPY".

**Verify:** `./gradlew :tool:testDebugUnitTest` green (Room KSP accepts the projections; contract cases pass); `./gradlew :tool:assembleDebug` clean. On-device I-2 confirmation is in Task 6.

---

### Task 3: Periodic-sync data layer (prefs + tag + VM state/setter)

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/LedgerPreferences.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/LedgerConstants.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/ui/settings/SettingsViewModel.kt`
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Produces: `LedgerPreferences.BACKGROUND_SYNC_ENABLED` (booleanPreferencesKey `"background_sync_enabled"`), `LedgerPreferences.BACKGROUND_SYNC_HOURS` (longPreferencesKey `"background_sync_hours"`); `LedgerConstants.SIMPLEFIN_PERIODIC_TAG` (`const val = "simplefin-sync-periodic"`); `SettingsUiState.backgroundSyncEnabled: Boolean`, `SettingsUiState.backgroundSyncHours: Int`; `SettingsViewModel.setBackgroundSync(enabled: Boolean, hours: Int)`.
- Consumes: existing `SettingsViewModel`/`SettingsUiState`.

- `LedgerPreferences`: add `BACKGROUND_SYNC_ENABLED` (`booleanPreferencesKey`) and `BACKGROUND_SYNC_HOURS` (`longPreferencesKey`). (Import `booleanPreferencesKey`.)
- `LedgerConstants`: add `const val SIMPLEFIN_PERIODIC_TAG = "simplefin-sync-periodic"` (distinct from `SIMPLEFIN_SYNC_JOB_KEY` so the periodic schedule and the one-shot "Sync now" occupy separate WorkManager unique-work slots).
- `SettingsUiState`: add `val backgroundSyncEnabled: Boolean = false`, `val backgroundSyncHours: Int = 12`.
- `SettingsViewModel.reload()`: read `prefs[BACKGROUND_SYNC_ENABLED] ?: false` and `(prefs[BACKGROUND_SYNC_HOURS] ?: 12L).toInt()` into the new state fields (alongside the existing connected/accountNames/bridgeError reads).
- Add `fun setBackgroundSync(enabled: Boolean, hours: Int)`: `viewModelScope.launch { dataStore.edit { it[BACKGROUND_SYNC_ENABLED] = enabled; it[BACKGROUND_SYNC_HOURS] = hours.toLong() }; _uiState.value = _uiState.value.copy(backgroundSyncEnabled = enabled, backgroundSyncHours = hours) }`.
- `disconnect()`: ALSO `prefs.remove(BACKGROUND_SYNC_ENABLED); prefs.remove(BACKGROUND_SYNC_HOURS)` and set them back to defaults (false/12) in the not-connected state (the Screen cancels the periodic tag — Task 4).
- **`SettingsViewModelTest`:** (a) with keys seeded (enabled=true, hours=6) → `reload()` → state reflects them; (b) `setBackgroundSync(true, 24)` persists to the DataStore AND updates state; (c) `disconnect()` clears both keys (assert gone + state back to false/12). Use the existing temp-file DataStore setup.

**Verify:** `./gradlew :tool:testDebugUnitTest` green; `./gradlew :tool:assembleDebug` clean.

---

### Task 4: Periodic-sync Settings UI (toggle + interval + enqueue/cancel)

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `SettingsUiState.backgroundSyncEnabled`/`backgroundSyncHours`, `SettingsViewModel.setBackgroundSync` (Task 3); `LightWork.enqueuePeriodic`/`cancel`; `LedgerConstants.SIMPLEFIN_PERIODIC_TAG`, `SIMPLEFIN_SYNC_JOB_KEY`.

- Add a **"Background refresh"** control to `ConnectedSimpleFinSection` (pass the new state + callbacks in). Minimal, calm, sdk:ui only:
  - A tappable row showing the current state, e.g. `LightText("Background refresh: " + (if (enabled) "every ${hours}h" else "Off"))` that toggles enabled and, when enabled, cycles the interval 6→12→24→6 h on a second affordance — OR two rows: an on/off toggle row and (when enabled) an interval row that cycles 6/12/24. Pick the simpler idiom consistent with the screen; keep copy calm. Include a one-line note that LightOS batches background work (may run late on battery/Doze), per §6.3.
  - On enable (or interval change while enabled): `viewModel.setBackgroundSync(true, hours)` THEN in the Screen `LightWork.enqueuePeriodic(lightContext, SIMPLEFIN_SYNC_JOB_KEY, hours.hours, tag = SIMPLEFIN_PERIODIC_TAG)` (`import kotlin.time.Duration.Companion.hours`). On disable: `viewModel.setBackgroundSync(false, hours)` THEN `LightWork.cancel(lightContext, SIMPLEFIN_PERIODIC_TAG)`.
  - The `onDisconnect` lambda (already cancels the one-shot? it calls `viewModel.disconnect()`): ALSO `LightWork.cancel(lightContext, SIMPLEFIN_PERIODIC_TAG)` before/with `viewModel.disconnect()`, so forgetting a connection stops the periodic schedule.
- Do NOT let the periodic schedule use the default (untagged) slot — it MUST pass `tag = SIMPLEFIN_PERIODIC_TAG`, or "Sync now" (`enqueue` under `SIMPLEFIN_SYNC_JOB_KEY`, REPLACE) would cancel it.
- No new unit test (Compose + LightWork; verified on-device in Task 6). Keep `SettingsScreen` sdk:ui-only; scanner-free.

**Verify:** `./gradlew :tool:testDebugUnitTest` green (unchanged count); `./gradlew :tool:assembleDebug` clean (no scanner violations).

---

### Task 5: QR-scan connect (CAMERA + scanner screen + wire from connect)

**Files:**
- Modify: `tool/lighttool.toml` (add CAMERA)
- Create: `tool/src/main/kotlin/dev/tyler/lightledger/ui/settings/SimpleFinQrScannerScreen.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/ui/settings/SimpleFinConnectScreen.kt` (add "Scan QR" affordance)

**Interfaces:**
- Consumes: `com.thelightphone.sdk.LightQrCodeScanner`; `SimpleFinConnectViewModel.submit(token)`.
- Produces: a QR entry that funnels into `submit()` — no new claim/sync logic.

- `tool/lighttool.toml`: change `permissions` to include `"android.permission.CAMERA"` alongside the existing `INTERNET`/`ACCESS_NETWORK_STATE`.
- `SimpleFinQrScannerScreen` — mirror `examples/authenticator/.../AuthenticatorQrScannerScreen.kt` (READ it). A `SimpleLightScreen<String?>(sealedActivity)` (returns the scanned token, or null on back) whose `Content()`:
  ```
  val themeColors by LightThemeController.colors.collectAsState()
  var pendingScan by remember { mutableStateOf<String?>(null) }
  LightTheme(colors = themeColors) {
      LightQrCodeScanner(
          title = "Scan SimpleFIN Token",
          onScanned = { pendingScan = it },
          onBack = { goBack(null) },
          modifier = Modifier.background(LightThemeTokens.colors.background),
      )
  }
  LaunchedEffect(pendingScan) {
      val value = pendingScan ?: return@LaunchedEffect
      goBack(value)   // pop the scanner, return the token to the caller
  }
  ```
  (Import `com.thelightphone.sdk.LightQrCodeScanner`. Navigation deferred to the `LaunchedEffect` per the SDK's once-only-onScanned + pop-before-push contract.)
- `SimpleFinConnectScreen`: add a "Scan QR instead" affordance (a `LightText`/bar button under the paste editor's flow — keep it calm, sdk:ui). On tap: `navigateTo(screenFactory = { SimpleFinQrScannerScreen(it) }) { token -> if (token != null) viewModel.submit(token) }`. The returned token flows into the SAME `submit()` as paste (paste-first, QR-second). Trim/guard is already handled inside `submit`.
- No JVM test (camera/Compose). On-device: Task 6 (permission + preview).

**Verify:** `./gradlew :tool:assembleDebug` — BUILD SUCCESSFUL, CAMERA permission accepted by the plugin validator, `<uses-feature>` auto-emitted, no scanner violations. `./gradlew :tool:testDebugUnitTest` green (unchanged).

---

### Task 6: On-device verification (Light_Phone AVD)

Verification only (fix-forward commits if bugs surface). Emulator runs the LightOS system app (uid=1000); the connect field takes on-screen key taps only (see memory `lightos-ondevice-testing`); SimpleFIN tokens are single-use.

- **Build gate:** `:tool:clean` (separate), then `:tool:assembleDebug :tool:testDebugUnitTest` green; `:tool:installDebug`; launch `dev.tyler.lightledger/com.thelightphone.sdk.LightActivity`.
- **QR (permission + preview, real scan deferred to hardware):** Settings → SimpleFIN (not-connected) → connect → tap "Scan QR instead" → confirm the LightOS **CAMERA permission-grant** dialog appears and, on accept, the `LightQrCodeScanner` **live preview renders** (viewfinder). Back out. (A real token scan is deferred to physical LP3.)
- **Background-refresh toggle:** connect via paste (ask the user for a fresh single-use token) + Sync now; in the connected section, enable **Background refresh** → confirm a periodic job is scheduled under the tag (`adb shell dumpsys jobscheduler | grep -i simplefin` or WorkManager state via `LightWork.observe`/logs); change interval; disable → confirm cancelled; Disconnect & forget → confirm the periodic tag is cancelled too.
- **I-2 / currency + shared formatter regression:** with USD data, confirm Home / History / Review still format `$` correctly (the shared `LedgerFormat`). (Non-USD 100×-correct display is unit-verified via `LedgerFormatTest` + the contract test; a non-USD account isn't available live.)
- **Regression + robustness:** connect→sync→review→disconnect loop still works; hardware-back exits each screen one level; no crash; no overflow at 1080×1240.
- If a bug surfaces, fix forward with a separate commit; re-run the build gate + the relevant check.

**Verify / DoD:** JVM suite green, `installDebug` runs, no plugin/lint violations, QR permission+preview confirmed, periodic toggle enqueues/cancels under its tag, currency/regression unbroken.

---

## Self-review (author checklist — done)

- **Spec coverage:** shared formatter + I-2 → T1+T2; periodic toggle → T3+T4; QR connect → T5; dead-code cleanup → T2; on-device (incl. QR permission+preview per the user's choice) → T6. All four areas mapped.
- **Type consistency:** `LedgerFormat.amount/date` defined T1, used T1(Home)+T2(History/Review); `Transaction.currency`/`ReviewItem.currency` added T2, used by History/Review T2; `SIMPLEFIN_PERIODIC_TAG` + `BACKGROUND_SYNC_ENABLED/HOURS` + `setBackgroundSync`/`backgroundSyncEnabled/Hours` defined T3, consumed by the Screen T4; `SimpleFinQrScannerScreen` returns `String?`, consumed by the connect screen's `navigateTo` callback T5.
- **No migration:** currency threading is a JOIN projection, not a column — DB stays version 1.
- **Placeholder scan:** none — exact queries, signatures, the QR mirror body, and the periodic-tag rationale are all concrete.
