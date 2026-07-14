# Light Ledger M3b-core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Each `### Task N` is one implement → review → fix unit. JVM-testable tasks come first, then wiring, then on-device verification. Product spec of record: `/Users/tyleryancey/Documents/light-phone-3-lightos-dev/CLAUDE-light-ledger.md` (§6.2.6 pending-settle, §6.2 sync, §3/§7 currency). Design spec: `docs/superpowers/specs/2026-07-14-light-ledger-m3b-core-design.md`. This plan trusts that spec + the SDK/code facts in Global Constraints.

**Goal:** Make SimpleFIN sync *correct* under real-world messiness — pending-settle churn, cross-source dedup, bridge-error surfacing, and non-USD currency — as a heavily-JVM-tested, migration-free hardening slice.

**Architecture:** Pure decision logic (`CurrencyExponent`, `PendingSettle`) with exhaustive unit tests; account-agnostic dedup + a post-upsert pending-settle pass wired into `SimpleFinSyncRunner`; currency threaded through month totals with a never-sum-across guard; a durable DataStore error key surfaced as a Settings banner. No schema migration (existing columns + scan queries).

**Tech Stack:** Kotlin, Room (no migration), kotlinx-serialization, ktor/OkHttp, Jetpack Compose (sdk:ui), Light SDK (`@LightJob`/`LightWork`, `LightScreen`/`LightViewModel`), JUnit/kotlin.test, JDK 17.

## Global Constraints

- **Working dir / branch:** worktree `/Users/tyleryancey/Documents/lightphone/light-tools-ledger`, branch `ledger`. Gradle module is `:tool` (NOT `:ledger`). Package `dev.tyler.lightledger`.
- **Java:** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew`. Test: `./gradlew :tool:testDebugUnitTest`. Build/scan: `./gradlew :tool:assembleDebug`. Full DoD build: run `:tool:clean` as a SEPARATE gradle invocation, then `:tool:assembleDebug :tool:testDebugUnitTest` (a combined `clean assembleDebug` fails — the light-sdk plugin's generated manifest gets wiped by clean).
- **Baseline:** branch is at `ebc3853` (M3a tip + final-review response). 131 unit tests currently green.
- **Money:** `Long` minor units, spend negative; `BigDecimal` only at the string-parse boundary (inside `AmountParser`). Never sum across currencies.
- **NO SCHEMA MIGRATION.** All new queries use existing `TransactionEntity` columns (`pendingExternal`, `postedEpochDay`, `amountMinor`, `accountId`, `source`, `status`, `categoryId`, `externalId`). Age is by `postedEpochDay`, NOT `createdAtEpochMs`. DB stays `@Database(version = 1)`. Do NOT add a column, index, or `Migration` (none exist; adding one is out of scope and risky).
- **Plugin blocked patterns (regex over raw source incl. strings/comments — build fails):** `getSystemService(`, `startActivity(`/`startService(`/`bindService(`/`registerReceiver(`, `contentResolver`, `android.app.*`, `android.content.Context/Intent/...`, casts `as …Activity`/`as Context…`, and ALL reflection (`.javaClass`, `.java.<word>`, `Class.forName(`, `.getMethod(`/`.getDeclaredMethod(`/`.getField(`, `MethodHandles`). `::class.java` is allowed only where the SDK requires it (`viewModelClass`). `com.thelightphone.sdk.SealedLightContext` and `androidx.datastore.*` are allowed. Keep pure/domain code Android-import-free.
- **Dependency allow-list** already covers everything needed; no new deps. `kotlin.serialization` + `ksp` plugins already applied.
- **SDK/code facts (verified this session):**
  - `AmountParser.parseToMinorUnits(raw: String, exponent: Int = 2): Long` — already threads exponent (BigDecimal `setScale(exponent, HALF_UP).movePointRight(exponent).longValueExact()`).
  - `SimpleFinMapper.toMappedTransactions(account: SimpleFinAccount): List<MappedExternalTxn>` calls `AmountParser.parseToMinorUnits(txn.amount)` with the default exponent and ignores `account.currency`. `MappedExternalTxn(externalId: String, postedEpochDay: Long, amountMinor: Long, payee: String, memo: String, pending: Boolean)`.
  - `DedupHash.compute(accountId: Long, postedEpochDay: Long, amountMinor: Long, payee: String): String` (folds in accountId — root cause of cross-source inertness); `DedupHash.normalizePayee(payee: String): String` (lowercase/trim/collapse-space/strip-trailing-digits).
  - `TxnRef(id: Long, accountId: Long, source: String, status: String, categoryId: Long?, externalId: String?, postedEpochDay: Long, amountMinor: Long, payee: String, pendingExternal: Boolean)` (in `data/`).
  - `SyncEngine.plan(accountId: Long, incoming: List<MappedExternalTxn>, externalLookup: (String) -> TxnRef?, dedupLookup: (String) -> List<TxnRef>, rules: List<CategoryRule>): SyncPlan`; `SyncPlan(ops: List<SyncEngine.SyncOp>)`; `SyncOp = Insert(externalId, postedEpochDay, amountMinor, payee, memo, categoryId: Long?, status, pendingExternal, dedupHash) | UpdateExternalFields(id, amountMinor, payee, postedEpochDay, pendingExternal) | LinkCrossSource(existingId, externalId)`. Cross-source filter today: `source != TransactionSource.SIMPLEFIN` && `abs(ΔpostedEpochDay) <= CROSS_SOURCE_WINDOW_DAYS (=1)`, pick min by `(dateDistance, id)`.
  - `TransactionSource.{MANUAL, CSV, SIMPLEFIN}`, `TransactionStatus.{NEEDS_REVIEW, CONFIRMED}`, `AccountKind.SIMPLEFIN` (in `data/LedgerConstants.kt`, alongside `const val SIMPLEFIN_SYNC_JOB_KEY`).
  - Repo (interface `LedgerRepository` + `RoomLedgerRepository` + test `FakeLedgerRepository`) SimpleFIN methods: `upsertSimpleFinAccount(externalId, name, currency): Long`, `findTransactionByExternal(accountId, externalId): TxnRef?`, `findDedupCandidates(dedupHash): List<TxnRef>`, `insertExternalTransaction(accountId, postedEpochDay, amountMinor, payee, memo, categoryId, status, externalId, pendingExternal, dedupHash): Long`, `updateExternalTransactionFields(id, amountMinor, payee, postedEpochDay, pendingExternal)`, `adoptExternalId(id, externalId)`, `listReviewInbox(): List<ReviewItem>`, `confirmReview(id, categoryId)`, `pastConfirmations(): List<PastConfirmation>`, `insertRule/listRules`, `deleteSimpleFinData()`, `listSimpleFinAccounts(): List<String>`, `monthSummary(month): List<CategoryMonthTotal>`, `listTransactions(month)`, `getTransaction(id)`, `deleteTransaction(id)`, `needsReviewCount(): Int`.
  - `TransactionDao` existing queries include `findByExternalId(accountId, externalId)`, `findByDedupHash(dedupHash)`, `delete(id)`, `confirm(id, categoryId)`, `updateExternalFields(id, amountMinor, payee, postedEpochDay, pendingExternal)`, `setExternalId(id, externalId)`, `listConfirmedInRange(startEpochDay, endEpochDay)`, `insert`.
  - `LedgerMath.categoryTotals(...)` already groups by `(categoryId, currency)` with `TransactionAmount(categoryId, currency, amountMinor)` / `CategoryTotal(categoryId, currency, totalMinor)`. `RoomLedgerRepository.monthSummary` currently constructs `TransactionAmount` with `currency = DEFAULT_CURRENCY ("USD")` (a `TransactionEntity` has no currency; currency lives on `AccountEntity`). `CategoryMonthTotal(categoryId: Long, categoryName: String, totalMinor: Long)` — NO currency field. `HomeViewModel.totalSpentMinor(totals) = totals.filter { it.totalMinor < 0 }.sumOf { -it.totalMinor }`; `HomeScreen.formatAmount` uses `Locale.US` + `/100.0`.
  - `LedgerPreferences`: `ACCESS_BLOB` (string), `LAST_SYNC_EPOCH_MS` (long), `SYNC_START_EPOCH_S` (long).
  - `SimpleFinSyncRunner.sync(accessUrl, startEpochS): Result<Int>` → `applyAccounts(accountSet: AccountSet): Int`. `LedgerJobs`: `@LightJob(SIMPLEFIN_SYNC_JOB_KEY) val simpleFinSyncJob`; `result.fold(onSuccess = { newCount -> ctx.dataStore.edit { LAST_SYNC_EPOCH_MS=nowMs; SYNC_START_EPOCH_S=nowS }; Success(mapOf("new" to newCount.toString())) }, onFailure = { … CancellationException rethrow; 4xx SimpleFinHttpException → Error(mapOf("reason" to code)); else Retry })`; decrypt failure → `Error(mapOf("reason" to "decrypt"))`.
  - `SettingsViewModel(repository, dataStore) : LightViewModel<Unit>`; `SettingsUiState(connected, accountNames, loading)`; `reload()` reads `prefs[ACCESS_BLOB] != null` + `listSimpleFinAccounts()`; `disconnect()` removes `ACCESS_BLOB`/`LAST_SYNC_EPOCH_MS`/`SYNC_START_EPOCH_S` then `deleteSimpleFinData()`. `SettingsScreen.ConnectedSimpleFinSection(accountNames, statusText, onSyncNow, onDisconnect)`. Screens build VM via `XViewModel(repository, lightContext.dataStore)`; `LightWork` calls live in the Screen.
- **FakeLedgerRepository fidelity is load-bearing:** every new repo method must be mirrored in the Fake with the SAME filter/order semantics as the intended Room SQL — the contract test runs against the Fake only, so a divergence makes the suite lie.
- **Secrets:** never log the Access URL / setup token / blob / bridge error content beyond the calm banner text.
- **Commit** after every task; trailer `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. Push `ledger` after each task. Never force-push (shared branch; scheduled sync-upstream may push — rebase local onto origin if diverged).

---

### Task 1: Currency exponent table + mapper threading

**Files:**
- Create: `tool/src/main/kotlin/dev/tyler/lightledger/domain/CurrencyExponent.kt`
- Create: `tool/src/test/kotlin/dev/tyler/lightledger/domain/CurrencyExponentTest.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/simplefin/SimpleFinMapper.kt`
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/simplefin/SimpleFinDecodeTest.kt` (add a non-USD mapping case)

**Interfaces:**
- Produces: `object CurrencyExponent { fun of(code: String): Int }`.
- Consumes: `AmountParser.parseToMinorUnits(raw, exponent)`.

- `CurrencyExponent.of(code)`: uppercase-trim the input; return `0` for zero-decimal ISO-4217 codes (at minimum `JPY, KRW, VND, CLP, ISK, HUF, XOF, XAF, XPF`), `3` for three-decimal (`BHD, KWD, OMR, TND, IQD, JOD, LYD`), else `2` (covers USD and unknown codes). Pure Kotlin, no Android imports.
- `SimpleFinMapper`: change the amount line to `amountMinor = AmountParser.parseToMinorUnits(txn.amount, CurrencyExponent.of(account.currency))`; delete the M3a "USD/2-dp only" caveat comment (the assumption is now resolved).
- **Tests** — `CurrencyExponentTest`: USD→2, JPY→0, BHD→3, unknown "XYZ"→2, lowercase "jpy"→0, whitespace "  usd "→2. In `SimpleFinDecodeTest`, add one case: a JPY account with `amount = "-1200"` maps to `amountMinor = -1200` (exponent 0), and confirm the existing USD real-payload case still maps `"-2496.04" → -249604`.

**Verify:** `./gradlew :tool:testDebugUnitTest` green (incl. new cases); `./gradlew :tool:assembleDebug` clean.

---

### Task 2: Never-sum currency guard (month totals)

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/TransactionDao.kt` (new projected query)
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/LedgerModels.kt` (`CategoryMonthTotal` gains `currency`)
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/RoomLedgerRepository.kt` (`monthSummary`)
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/data/FakeLedgerRepository.kt` (mirror)
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/domain/LedgerMath.kt` (+ per-currency total helper) and `tool/src/test/kotlin/dev/tyler/lightledger/domain/LedgerMathTest.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/ui/home/HomeViewModel.kt` + `ui/home/HomeScreen.kt` + `tool/src/test/kotlin/dev/tyler/lightledger/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Produces: `CategoryMonthTotal(categoryId, categoryName, totalMinor, currency: String)`; `LedgerMath.primaryCurrencyTotal(totals: List<CategoryMonthTotal>): Pair<String, Long>?` (currency + summed spend of only that currency, or null when empty).
- Consumes: `LedgerMath.categoryTotals` (already currency-aware).

- **DAO:** add a projected query joining account currency so month totals carry real currency, e.g. `@Query("SELECT t.categoryId AS categoryId, t.amountMinor AS amountMinor, a.currency AS currency FROM transactions t JOIN accounts a ON t.accountId = a.id WHERE t.status = 'CONFIRMED' AND t.postedEpochDay BETWEEN :startEpochDay AND :endEpochDay")` returning a small projection row (`CategoryAmountRow(categoryId: Long?, amountMinor: Long, currency: String)`). `monthSummary` maps these through `LedgerMath.categoryTotals` (real currency, no `DEFAULT_CURRENCY`) and attaches `currency` to each `CategoryMonthTotal`.
- **`CategoryMonthTotal`:** add `val currency: String`. Update every construction site.
- **`LedgerMath.primaryCurrencyTotal`:** group `totals` by `currency`, sum each currency's spend (negative totals only, as `totalSpentMinor` does today), return the `(currency, spend)` with the largest absolute spend; `null` if no totals. This is the never-sum-across guard — the displayed number is always a single currency's total.
- **Home:** replace `totalSpentMinor(state.categoryTotals)` usage so Home shows `primaryCurrencyTotal(...)`'s amount, formatted for that currency code (use `NumberFormat.getCurrencyInstance` with `Currency.getInstance(code)`; fall back to the existing `Locale.US` formatting if the code is unknown). If more than one currency has spend, still show only the primary — per-currency lines are deferred (leave a `// M3b-features: per-currency lines` marker). Keep the category-list rows as-is but format each row's amount with its own `currency`.
- **Tests:** `LedgerMathTest` — `primaryCurrencyTotal` picks the larger-spend currency and never adds across currencies (e.g. USD −$50 + JPY −¥9000 → returns the larger by absolute, not a sum); empty → null. `HomeViewModelTest` — existing single-USD assertions still hold; add a mixed-currency state asserting the displayed total equals only the primary currency's spend.
- FakeLedgerRepository.monthSummary must mirror the JOIN (attach the account's currency per row), not hard-code USD.

**Verify:** `./gradlew :tool:testDebugUnitTest` green; `./gradlew :tool:assembleDebug` clean; Room KSP accepts the new projection query.

---

### Task 3: PendingSettle pure engine + PendingSettleTest (crown jewel)

**Files:**
- Create: `tool/src/main/kotlin/dev/tyler/lightledger/simplefin/PendingSettle.kt`
- Create: `tool/src/test/kotlin/dev/tyler/lightledger/simplefin/PendingSettleTest.kt`

**Interfaces:**
- Produces: `object PendingSettle { fun plan(pending: List<TxnRef>, settledCandidates: List<TxnRef>, fetchedExternalIds: Set<String>, fetchStartEpochDay: Long, syncEpochDay: Long, ageDays: Long = 5, windowDays: Long = 4): List<SettleOp> }`; `sealed interface SettleOp { data class MigrateThenDelete(val oldId: Long, val newId: Long, val categoryId: Long) : SettleOp; data class DeleteStale(val oldId: Long) : SettleOp }`.
- Consumes: `TxnRef` (see Global Constraints), `TransactionStatus.CONFIRMED`.

- **Pure Kotlin, zero Android imports.** The algorithm (§6.2.6):
  For each `p` in `pending` (each is a `pendingExternal = true` SIMPLEFIN row) considered in ascending `id` order, INCLUDE it only if ALL hold:
  1. aged: `p.postedEpochDay <= syncEpochDay - ageDays`;
  2. fetch covered it: `p.postedEpochDay >= fetchStartEpochDay`;
  3. vanished: `p.externalId != null && p.externalId !in fetchedExternalIds`.
  For an included `p`, search `settledCandidates` (non-pending, same account — the caller pre-filters account) for a match `s` with `s.amountMinor == p.amountMinor` and `abs(s.postedEpochDay - p.postedEpochDay) <= windowDays` and `s.id != p.id`, picking the closest by `(dateDistance, id)`. Then:
  - match found AND `p.status == CONFIRMED` AND `p.categoryId != null` → `MigrateThenDelete(oldId = p.id, newId = s.id, categoryId = p.categoryId!!)`;
  - match found AND (`p.status != CONFIRMED` OR `p.categoryId == null`) → `DeleteStale(p.id)`;
  - no match found → `DeleteStale(p.id)`.
  A `settledCandidate` may be consumed by at most one `MigrateThenDelete` (a matched settled row is removed from the candidate pool for subsequent pendings) to avoid two pendings migrating onto the same settled row. Rows failing any of 1–3 produce NO op.
- **Test matrix (`PendingSettleTest`)** — cover each row of §6.2.6, each an explicit assertion on the returned ops list:
  1. `settledWithNewId_categorized_migratesThenDeletes` — aged pending (CONFIRMED, category 7, extId "OLD" not in fetch) + settled candidate (extId "NEW", same amount, +1 day) → `[MigrateThenDelete(oldId, newId, 7)]`.
  2. `settledWithNewId_uncategorized_deletesStale` — same but pending is NEEDS_REVIEW/categoryId null → `[DeleteStale(oldId)]`.
  3. `settledSameId_noOp` — pending's extId IS in `fetchedExternalIds` (bank kept the id) → `[]` (it was updated in the normal pass, not churned).
  4. `amountRevised_noMatch_deletesStale_orExpiry` — settled candidate exists but different `amountMinor` (bank revised) → no amount match → `[DeleteStale(oldId)]` (still aged+vanished). Document this is the "revised amount = treat pending as gone" branch.
  5. `neverSettled_expiry_deletesStale` — aged pending, vanished, NO settled candidate → `[DeleteStale(oldId)]`.
  6. `notYetAged_untouched` — pending vanished but `postedEpochDay > syncEpochDay - ageDays` → `[]`.
  7. `outsideFetchWindow_untouched` — pending aged+vanished but `postedEpochDay < fetchStartEpochDay` (fetch didn't cover it, can't conclude it vanished) → `[]`.
  8. `multipleCandidates_picksClosestThenLowestId` — two settled candidates equidistant → picks lowest id.
  9. `twoPendings_doNotShareOneSettledRow` — two aged pendings, one settled candidate → exactly one `MigrateThenDelete`/`DeleteStale` consumes it; the other gets `DeleteStale` (no match left).
  10. `stillPresentAndAged_noOp` — pending aged but extId in fetch → `[]` (redundant guard with #3, keep for clarity).

**Verify:** `./gradlew :tool:testDebugUnitTest --tests '*PendingSettleTest'` green; full suite + `assembleDebug` clean. This task gets the most scrutinous review — a wrong `MigrateThenDelete` destroys user data.

---

### Task 4: Pending-settle data layer

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/TransactionDao.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/LedgerRepository.kt`, `data/RoomLedgerRepository.kt`
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/data/FakeLedgerRepository.kt`
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/data/LedgerRepositoryContractTest.kt`

**Interfaces:**
- Produces (repo): `suspend fun listStalePendingExternal(accountId: Long, olderThanEpochDay: Long): List<TxnRef>`; `suspend fun findSettledMatches(accountId: Long, amountMinor: Long, minEpochDay: Long, maxEpochDay: Long): List<TxnRef>`; `suspend fun deleteTransactionById(id: Long)` (thin wrapper over `TransactionDao.delete` if not already exposed; reuse existing `deleteTransaction` if present).
- Consumes: `TxnRef`, `TransactionDao`.

- **DAO queries:**
  - `@Query("SELECT * FROM transactions WHERE accountId = :accountId AND pendingExternal = 1 AND postedEpochDay <= :olderThanEpochDay")` → `listStalePendingExternalRows(accountId, olderThanEpochDay): List<TransactionEntity>`.
  - `@Query("SELECT * FROM transactions WHERE accountId = :accountId AND pendingExternal = 0 AND amountMinor = :amountMinor AND postedEpochDay BETWEEN :minEpochDay AND :maxEpochDay")` → `findSettledMatchRows(accountId, amountMinor, minEpochDay, maxEpochDay): List<TransactionEntity>`.
- Repo maps rows `.toTxnRef()`. For apply, ensure a `deleteTransactionById(id)` repo method delegates to `TransactionDao.delete(id)` (check whether `deleteTransaction(id)` already covers this and reuse it; do not duplicate).
- **Fake:** mirror both queries exactly — `listStalePendingExternal` = in-memory rows filtered `accountId == && pendingExternal && postedEpochDay <= olderThanEpochDay`; `findSettledMatches` = `accountId == && !pendingExternal && amountMinor == && postedEpochDay in min..max`. Same semantics as the SQL.
- **Contract test:** seed a Fake with a pending SIMPLEFIN row (extId "OLD", CONFIRMED, cat 7, day D−10) and a settled SIMPLEFIN row (extId "NEW", day D−9, same amount) in the same account, plus an unrelated MANUAL row; assert `listStalePendingExternal(acct, D−5)` returns only the pending row, `findSettledMatches(acct, amt, D−13, D−5)` returns only the settled row, and `deleteTransactionById` removes exactly that row.

**Verify:** `./gradlew :tool:testDebugUnitTest` green (incl. contract cases); `./gradlew :tool:assembleDebug` (Room KSP accepts the new queries).

---

### Task 5: Real cross-source dedup

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/TransactionDao.kt`, `data/LedgerRepository.kt`, `data/RoomLedgerRepository.kt`, `tool/src/test/kotlin/dev/tyler/lightledger/data/FakeLedgerRepository.kt`, `LedgerRepositoryContractTest.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/simplefin/SyncEngine.kt`
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/simplefin/SyncEngineTest.kt`

**Interfaces:**
- Produces (repo): `suspend fun findCrossSourceDedupCandidates(amountMinor: Long, minEpochDay: Long, maxEpochDay: Long): List<TxnRef>`.
- Changes: `SyncEngine.plan`'s `dedupLookup` parameter type becomes `dedupLookup: (MappedExternalTxn) -> List<TxnRef>` (given an incoming txn, return account-agnostic candidates), replacing the `(dedupHash: String) -> List<TxnRef>` form.

- **DAO:** `@Query("SELECT * FROM transactions WHERE source != 'SIMPLEFIN' AND amountMinor = :amountMinor AND postedEpochDay BETWEEN :minEpochDay AND :maxEpochDay")` → `findCrossSourceCandidateRows(amountMinor, minEpochDay, maxEpochDay)`. Repo maps `.toTxnRef()`. Fake mirrors: rows with `source != SIMPLEFIN && amountMinor == && postedEpochDay in min..max`.
- **SyncEngine:** change `dedupLookup` to take the incoming `MappedExternalTxn`. The `LinkCrossSource` filter now: candidates already come account-agnostic; keep `source != SIMPLEFIN`, keep `abs(candidate.postedEpochDay - incoming.postedEpochDay) <= CROSS_SOURCE_WINDOW_DAYS (1)`, and **ADD** `DedupHash.normalizePayee(candidate.payee) == DedupHash.normalizePayee(incoming.payee)` (previously implied by the hash match). Pick closest by `(dateDistance, id)` → `LinkCrossSource(existingId, incoming.externalId)`. Insert still computes `dedupHash = DedupHash.compute(accountId, …)` for the stored row (unchanged — the stored hash stays account-scoped; only the *lookup* is account-agnostic).
- **SyncEngineTest:** update the cross-source cases to drive `dedupLookup` with `MappedExternalTxn` and assert: matching normalized payee + amount + ≤1 day → `LinkCrossSource`; SAME amount+day but DIFFERENT normalized payee → `Insert` (new coverage the payee-equality check adds); `source == SIMPLEFIN` candidate → `Insert`; >1 day → `Insert`; external-match precedence still wins over an available cross-source candidate.
- **Contract test:** seed a MANUAL row (payee "Coffee Shop", amount −450, day D) and assert `findCrossSourceDedupCandidates(−450, D−1, D+1)` returns it and excludes a SIMPLEFIN row of the same amount/day.

**Verify:** `./gradlew :tool:testDebugUnitTest` green; `./gradlew :tool:assembleDebug` clean.

---

### Task 6: Runner integration (cross-source lookup + pending-settle pass + errors)

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/simplefin/SimpleFinSyncRunner.kt`
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/simplefin/SimpleFinSyncRunnerTest.kt`

**Interfaces:**
- Produces: `data class SyncOutcome(val newCount: Int, val errors: List<String>)`; `SimpleFinSyncRunner.sync(accessUrl, startEpochS): Result<SyncOutcome>` (was `Result<Int>`).
- Consumes: `SyncEngine.plan` (new `dedupLookup` shape), `PendingSettle.plan`, repo methods from Tasks 4–5, `confirmReview`, `deleteTransactionById`.

- **Cross-source wiring:** in `applyAccounts`, build the dedup lookup as `dedupLookup = { incoming -> repo.findCrossSourceDedupCandidates(incoming.amountMinor, incoming.postedEpochDay - 1, incoming.postedEpochDay + 1) }` and pass to `SyncEngine.plan`. Remove the old exact-hash `byDedupHash` map. `LinkCrossSource` op → `repo.adoptExternalId(existingId, externalId)` (unchanged).
- **Pending-settle pass:** after the per-account op loop (rows now persisted), for the same account run:
  - `val fetchedExternalIds = mapped.map { it.externalId }.toSet()`;
  - `val syncEpochDay = <inject> `— thread the sync date as an epoch-day parameter into `sync(...)`/`applyAccounts` (the job passes `LocalDate.now(ZoneId.systemDefault()).toEpochDay()`; keep the runner pure by taking it as a param, not calling `now()` internally);
  - `val fetchStartEpochDay = Instant.ofEpochSecond(startEpochS).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()`;
  - `val stale = repo.listStalePendingExternal(dbId, syncEpochDay - PENDING_AGE_DAYS)`;
  - for each stale build `settledCandidates = repo.findSettledMatches(dbId, p.amountMinor, p.postedEpochDay - PENDING_WINDOW_DAYS, p.postedEpochDay + PENDING_WINDOW_DAYS)` (or one combined candidate list); call `PendingSettle.plan(stale, candidates, fetchedExternalIds, fetchStartEpochDay, syncEpochDay)`;
  - apply: `MigrateThenDelete(old,new,cat)` → `repo.confirmReview(new, cat); repo.deleteTransactionById(old)`; `DeleteStale(old)` → `repo.deleteTransactionById(old)`.
  - Constants `PENDING_AGE_DAYS = 5L`, `PENDING_WINDOW_DAYS = 4L` (top-level or companion).
- **Errors:** `applyAccounts`/`sync` returns `SyncOutcome(insertedCount, accountSet.errors)`. The whole persist+settle phase stays inside the existing try/catch so a DB throw → `Result.failure` → Retry; `CancellationException` propagates.
- **`SimpleFinSyncRunnerTest`** (real `FakeLedgerRepository`, fake `SimpleFinApi`): (a) fetch-1 inserts a pending row (CONFIRMED via a rule, or manually confirm it), fetch-2 (later `startEpochS`, pending id absent, a new settled id present with same amount, day within window, sync date advanced past age) → assert the settled row is CONFIRMED with the migrated category and the old pending row is gone; (b) a MANUAL row present, then a SIMPLEFIN fetch with a matching amount/day/payee → assert a `LinkCrossSource` happened (existing row adopted the externalId; only one row for that purchase); (c) a fetch whose `AccountSet.errors = ["Bridge says reauth"]` → `sync` returns `SyncOutcome` with that errors list; (d) not-yet-aged pending is left intact across a re-fetch.

**Verify:** `./gradlew :tool:testDebugUnitTest` green; `./gradlew :tool:assembleDebug` clean.

---

### Task 7: Errors surfacing (durable key + Settings banner)

**Files:**
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/data/LedgerPreferences.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/simplefin/LedgerJobs.kt`
- Modify: `tool/src/main/kotlin/dev/tyler/lightledger/ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt`
- Modify: `tool/src/test/kotlin/dev/tyler/lightledger/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Produces: `LedgerPreferences.LAST_ERROR = stringPreferencesKey("simplefin_last_error")`; `SettingsUiState.bridgeError: String?`.
- Consumes: `SyncOutcome` (Task 6), `SettingsViewModel.disconnect` (clear the key).

- **`LedgerJobs`:** on `onSuccess(outcome)` — after writing the watermark, `if (outcome.errors.isNotEmpty()) prefs[LAST_ERROR] = "Bridge says: " + outcome.errors.joinToString("; ") else prefs.remove(LAST_ERROR)`. On `onFailure` for a 4xx `SimpleFinHttpException`, also persist a durable reconnect message: `ctx.dataStore.edit { it[LAST_ERROR] = if (code == 403) "Connection expired — reconnect." else "Sync error ($code) — reconnect." }` (in addition to returning `Error(reason)`). Do NOT persist an error for transient Retry cases. Keep credential values out of the message.
- **`SettingsViewModel`:** add `bridgeError: String?` to `SettingsUiState`; in `reload()` read `prefs[LedgerPreferences.LAST_ERROR]`; in `disconnect()` also `prefs.remove(LedgerPreferences.LAST_ERROR)`.
- **`SettingsScreen`:** in `ConnectedSimpleFinSection`, when `bridgeError != null` render a calm `LightText(bridgeError, variant = Detail)` banner above "Sync now" (fed from `state.bridgeError`; pass it into the composable).
- **`SettingsViewModelTest`:** (a) with `LAST_ERROR` seeded → `bridgeError` populated on reload; (b) `disconnect()` clears `LAST_ERROR` (assert gone + `bridgeError == null`); (c) no key → `bridgeError == null`.

**Verify:** `./gradlew :tool:testDebugUnitTest` green; `./gradlew :tool:assembleDebug` clean.

---

### Task 8: On-device M3b-core verification (Light_Phone AVD)

Verification only (fix-forward commits if bugs surface). Emulator already runs the LightOS system app (uid=1000); the field only accepts on-screen key taps (see saved memory `lightos-ondevice-testing`); SimpleFIN setup tokens are single-use.

- **Build gate:** `:tool:clean` (separate invocation), then `:tool:assembleDebug :tool:testDebugUnitTest` — BUILD SUCCESSFUL, all tests green. `:tool:installDebug`; launch `dev.tyler.lightledger/com.thelightphone.sdk.LightActivity`.
- **Regression + currency:** connect with a fresh single-use token (ask the user to generate one), Sync now → accounts + transactions import, month total is currency-correct (USD accounts format as `$`).
- **Cross-source link:** BEFORE syncing (or after disconnect+reconnect), add a MANUAL transaction via `+` matching an amount/payee/date you expect from the bank feed; then Sync now → confirm the bank feed did NOT create a duplicate for that purchase (the manual row adopted it / only one row appears in History).
- **Errors banner:** seed the durable key (e.g. via a 403 by connecting a revoked token, or accept this is best-effort) → confirm Settings shows the calm "…reconnect."/"Bridge says: …" banner; a clean Sync now clears it.
- **Pending-settle:** NOT live-observable in one session — rely on `PendingSettleTest` (Task 3) + the 2-fetch `SimpleFinSyncRunnerTest` (Task 6). Note this explicitly in the report.
- **Nav/robustness:** hardware-back exits each screen one level; no crash; no visual overflow at 1080×1240; Disconnect & forget still wipes SIMPLEFIN data + clears the error key.
- If a bug surfaces, fix forward with a separate commit, re-run the build gate + the relevant check.

**Verify / DoD:** JVM suite green, `installDebug` runs, no plugin/lint violations, cross-source-link + errors-banner + regression sync confirmed on-device, pending-settle covered by the unit matrix.

---

## Self-review (author checklist — done)

- **Spec coverage:** pending-settle → T3+T4+T6; cross-source dedup → T5+T6; errors surfacing → T6+T7; non-USD currency (exponent) → T1; never-sum guard → T2. All four IN-scope features mapped.
- **Type consistency:** `SyncOutcome(newCount, errors)` produced in T6, consumed in T7; `dedupLookup: (MappedExternalTxn) -> List<TxnRef>` changed in T5, used in T6; `CategoryMonthTotal.currency` added T2, used in Home T2; `SettleOp`/`PendingSettle.plan` defined T3, consumed T6; `LAST_ERROR` defined T7, written by the job T7. `deleteTransactionById` introduced T4, used T6.
- **No migration:** confirmed — every new query uses existing columns; DB stays version 1.
- **Placeholder scan:** none — exact queries, algorithm, and test matrices given.
