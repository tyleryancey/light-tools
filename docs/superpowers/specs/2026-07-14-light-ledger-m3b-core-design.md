# Light Ledger — M3b-core design spec

**Date:** 2026-07-14 · **Branch:** `ledger` · **Module:** `:tool` · **Package:** `dev.tyler.lightledger`
**Spec of record:** `/Users/tyleryancey/Documents/light-phone-3-lightos-dev/CLAUDE-light-ledger.md` (§6.2.6, §6.2, §3, §7)

## Why this change

M3a shipped the paste-to-connect SimpleFIN slice, verified live on-device. It deliberately deferred the harder, longer-tail work and — per the M3a final review — left two known gaps: **cross-source dedup is inert** (`SyncEngine.LinkCrossSource` is unreachable in production) and **`accountSet.errors` are decoded but never surfaced**. M3b-core is the **correctness-first slice** (user-chosen): the highest-value, heavily-JVM-testable hardening. It makes bank sync *correct* under the messy realities SimpleFIN exposes — pending transactions that re-post under new ids, duplicate manual/bank entries, bridge error messages, and non-USD currencies.

**Deferred to a separate M3b-features plan:** QR-scan connect (CAMERA + `LightQrCodeScanner`) and the background-periodic toggle (`enqueuePeriodic`). **Deferred further:** polished per-currency display lines (until a non-USD account exists).

## The four features

### 1. Pending-settle churn (§6.2.6) — the risk center

Banks often issue a **new transaction id** when a *pending* transaction posts (settles). Without reconciliation, the ledger keeps the stale pending row *and* the new settled row — a duplicate, and worse, the user's categorization on the pending row is orphaned.

**Algorithm** (pure Kotlin, a separate pass over persisted rows — the `SyncEngine` KDoc forbids doing this inside the per-fetch `plan()`):
After the normal upsert pass, for each SIMPLEFIN account, consider each still-`pendingExternal` row that is **aged** (`postedEpochDay ≤ syncEpochDay − 5`), **within the fetched window** (`postedEpochDay ≥ fetchStartEpochDay`, so we know the fetch would have returned it), and whose **externalId was not in this fetch** (`∉ fetchedExternalIds` — it vanished). For such a row, look for a **non-pending, same-account** row with the **same `amountMinor`** and `postedEpochDay` **within ±4 days**:
- **found + old row CONFIRMED-with-category** → migrate category+status onto the settled row, delete the stale pending row (`MigrateThenDelete`);
- **found + old row NEEDS_REVIEW** → just delete the stale pending row (`DeleteStale`) — the settled row already stands as the replacement;
- **none found** → delete the stale pending row (`DeleteStale`) — an expired/canceled authorization.

A pending row still present in the fetch, or not yet aged, or outside the fetched window is **left untouched**.

This is the tool's most bug-prone code: a wrong `MigrateThenDelete` destroys user-categorized data. It gets the most exhaustive test coverage (`PendingSettleTest` matrix) and the most scrutinous review.

### 2. Real cross-source dedup

Today `DedupHash.compute` folds in `accountId`, so a SIMPLEFIN import's hash can never collide with a MANUAL/CSV row's hash — the `LinkCrossSource` branch is dead. Fix by making candidate lookup **account-agnostic**: query non-SIMPLEFIN rows by `(amountMinor, postedEpochDay ± 1 day)`, and add a **`normalizePayee` equality** check to `SyncEngine`'s cross-source filter (which previously relied on the hash having encoded the payee). A SIMPLEFIN insert that matches an existing manual/CSV entry then **links** (adopts the externalId onto the existing row, keeping its category/status) instead of creating a duplicate.

### 3. `accountSet.errors` + sync-failure surfacing

SimpleFIN returns a 200 with a populated `errors[]` for non-fatal conditions (subscription lapse, "reauthenticate"), and 4xx for revoked tokens. Neither is durably surfaced today (`LightJobResult.Error(outputData)` is ephemeral WorkManager output). Add a durable DataStore key `simplefin_last_error` that the `@LightJob` writes: a "Bridge says: …" message when `errors` is non-empty on success, a reconnect reason on 4xx, **cleared on a clean errorless success and on disconnect**. Settings renders it as a calm banner in the connected section.

### 4. Non-USD currency — exponent + never-sum guard

`AmountParser.parseToMinorUnits` already accepts an `exponent`; `SimpleFinMapper` just needs to pass `CurrencyExponent.of(account.currency)` (new table: default 2; 0-dp for JPY/KRW/…; 3-dp for BHD/KWD/…). Separately, `LedgerMath` already groups by `(categoryId, currency)`, but `RoomLedgerRepository.monthSummary` hard-labels every row `"USD"` and `CategoryMonthTotal` carries no currency — so a non-USD account would be summed into the USD total as garbage. Thread the real account currency through month totals and have Home display the **primary currency's** total (never summing across currencies). The single-total display is preserved; polished per-currency lines are deferred.

## Architecture (units + boundaries)

- **`domain/CurrencyExponent`** (pure) — `fun of(code: String): Int`. Depends on nothing; JVM-tested.
- **`simplefin/PendingSettle`** (pure) — `plan(pending, settledCandidates, fetchedExternalIds, fetchStartEpochDay, syncEpochDay, ageDays=5, windowDays=4): List<SettleOp>`; `SettleOp = MigrateThenDelete(oldId, newId, categoryId) | DeleteStale(oldId)`. Depends only on `TxnRef`. The decision logic lives here; the runner only feeds it persisted rows and applies its ops. JVM-tested exhaustively.
- **Data layer** — new read queries (`listStalePendingExternal`, `findSettledMatches`, `findCrossSourceDedupCandidates`) + a `delete(id)` repo method; each mirrored faithfully in `FakeLedgerRepository`. Apply ops reuse existing `confirmReview(id, categoryId)` + `delete(id)`.
- **`simplefin/SyncEngine`** — `dedupLookup` becomes account-agnostic (keyed by `(amountMinor, postedEpochDay)`); `LinkCrossSource` filter gains normalized-payee equality. Decision logic otherwise unchanged.
- **`simplefin/SimpleFinSyncRunner`** — orchestration only: feeds the account-agnostic dedup candidates into `SyncEngine.plan`, and after the upsert pass runs the pending-settle pass and applies its ops; `sync()` also returns `errors`.
- **`simplefin/LedgerJobs`** — persists/clears `simplefin_last_error`.
- **Settings VM/Screen** — context-free `SettingsViewModel` exposes `bridgeError`; the Screen renders the banner. (`LightWork` stays in the Screen; the VM stays JVM-testable — the established M3a pattern.)

## Data flow

`@LightJob` → decrypt Access URL → `SimpleFinClient.fetch` → `SimpleFinSyncRunner.sync`:
1. per account: upsert account → map txns (currency-aware exponent) → build external + **account-agnostic cross-source** lookups → `SyncEngine.plan` → apply Insert/Update/**LinkCrossSource**;
2. per account: gather stale pendings + settled candidates + this-fetch externalIds → `PendingSettle.plan` → apply Migrate/Delete;
3. return `SyncOutcome(newCount, errors)`.
Job persists watermark + `simplefin_last_error`. Settings reads the error key; Home month summary reads currency-correct, never-summed totals.

## Error handling

- Fetch IO/5xx → `Retry`; 4xx → `Error` + persist reason. Persist path (DB) errors → `Result.failure` → `Retry` (from the M3a runner fix). `CancellationException` always propagates.
- Pending-settle applies deletes/migrations transactionally per op; a persist throw mid-pass surfaces as `Retry` (the pass is idempotent — a re-run re-derives the same ops from remaining rows).
- 200-with-`errors`: sync still succeeds (non-fatal), watermark advances, banner set.

## Testing

- **`CurrencyExponentTest`** — table lookups (USD=2, JPY=0, BHD=3, unknown→2, case-insensitivity).
- **`PendingSettleTest`** — the full matrix: settled-with-new-id (migrate + delete), settled-same-id (no-op, still in fetch), amount-revised, never-settled-expiry (delete), categorized vs not-categorized, multiple settled candidates (closest-by-day tie-break), not-yet-aged (untouched), outside-fetch-window (untouched).
- **Data-layer contract tests** — the three new queries mirrored Fake↔intended-Room semantics (filters, ranges, source exclusion).
- **`SyncEngineTest`** — updated cross-source cases now driven by account-agnostic candidates + payee equality.
- **`SimpleFinSyncRunnerTest`** — multi-fetch sequences: pending→settled-new-id migrate/delete; manual entry + sync → link (no duplicate); 200-with-errors surfaces `errors`.
- **`LedgerMathTest` / Home VM** — per-currency separation; never sum across currencies.
- **`SettingsViewModelTest`** — `bridgeError` populated/cleared.

No schema migration (existing columns + scan queries; DB stays `version=1`). No Robolectric/androidTest infra — the Fake is the JVM proxy; on-device sync is the backstop. Pending-settle's multi-day churn is **not** live-observable in one session; it is verified by the unit matrix + the 2-fetch runner integration test.

## Out of scope / deferred

QR-scan connect; background-periodic toggle (note: a periodic job under `SIMPLEFIN_SYNC_JOB_KEY` with no tag collides with the one-shot's unique-work slot — it will need its own tag); polished per-currency display; a perf index for the pending/dedup scans (unindexed is fine at ledger scale).
