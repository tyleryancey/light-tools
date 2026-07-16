# Light Ledger — M3b-features design spec

**Date:** 2026-07-16 · **Branch:** `ledger` · **Module:** `:tool` · **Package:** `dev.tyler.lightledger`
**Spec of record:** `/Users/tyleryancey/Documents/light-phone-3-lightos-dev/CLAUDE-light-ledger.md` (§6.1 QR, §6.3 scheduling, §3/§7 currency)

## Why this change

M3b-core shipped the SimpleFIN correctness slice (pending-settle churn, cross-source dedup, errors surfacing, non-USD *parsing*) and deferred the additive features + one display gap to this follow-on. M3b-features completes SimpleFIN to the spec's intent: **QR-scan connect** (§6.1), a **background-periodic sync toggle** (§6.3), the **non-USD display fix on History/Review** (M3b-core review finding I-2), a **shared currency-aware formatter** (retiring the recurring per-screen `formatAmount`/`formatDate` duplication that was the root of I-2), and **dead-code cleanup**.

The user chose: verify QR on-device via **permission-grant + camera preview only** (a real end-to-end scan is deferred to physical LP3 hardware — QR's only new logic is scan→submit, and the token→claim→sync path is identical to the already-verified paste flow).

## The four features

### 1. Shared currency-aware formatter + I-2 fix

Today `HomeScreen.formatAmount(amountMinor, currencyCode)` is exponent-aware (scales by `CurrencyExponent.of(code)` via `BigDecimal.movePointLeft`, then `NumberFormat` with the currency), but `HistoryScreen` and `ReviewScreen` each hard-code a *separate* `formatAmount(amountMinor)` that does `/100.0` + `Locale.US` — so a non-USD account renders 100×-wrong there (I-2). And date formatting is duplicated too.

Fix: extract ONE shared util — `formatAmount(amountMinor: Long, currencyCode: String): String` (Home's exponent-aware version) and `formatDate(epochDay: Long): String` — used by Home, History, and Review. Then thread the account's `currency` onto the `Transaction` and `ReviewItem` domain models (their backing DAO queries `JOIN accounts` for currency), so History and the Review inbox format each row with its real currency. This fixes I-2 and removes the 3× duplication in one move.

### 2. Background-periodic sync toggle (§6.3)

Default remains no periodic job (M3b-core's on-open opportunistic sync + "Sync now" stay). When the user enables **Background refresh**, schedule `LightWork.enqueuePeriodic(SIMPLEFIN_SYNC_JOB_KEY, hours)` and cancel on disable. Interval choices 6/12/24 h (default 12). Persist `background_sync_enabled` (default false) + `background_sync_hours` (default 12) in DataStore.

**Critical gotcha (from M3b-core's SDK exploration):** a periodic schedule and the one-shot "Sync now" both default their WorkManager uniqueness slot to `jobKey` — so scheduling one would replace/cancel the other. The periodic schedule therefore uses a **distinct tag** `SIMPLEFIN_PERIODIC_TAG`, and its `cancel`/state target that tag, leaving the one-shot slot untouched.

### 3. QR-scan connect (§6.1)

The SimpleFIN setup token can be QR-encoded (the Bridge shows text; the user QR-ifies it). Add `android.permission.CAMERA` (one `lighttool.toml` line — the plugin's allow-list + implied-feature already cover it). A new `SimpleFinQrScannerScreen` mirrors `examples/authenticator/…/AuthenticatorQrScannerScreen`: it hosts the SDK's `LightQrCodeScanner` wrapper (which auto-wires the LightOS CAMERA permission flow), captures the scanned string, and `goBack(token)`. The existing paste `SimpleFinConnectScreen` gains a "Scan QR instead" affordance that launches the scanner and feeds the returned token into the **same** `viewModel.submit(token)` path (paste-first, QR-second per spec). No new claim/sync logic — QR only re-routes token entry.

### 4. Dead-code cleanup

Remove the now-unused `LedgerRepository.findDedupCandidates` + `TransactionDao.findByDedupHash` (the M3b-core runner rewire replaced exact-hash dedup with `findCrossSourceDedupCandidates`).

## Architecture (units + boundaries)

- **`ui/shared/LedgerFormat.kt`** (new) — `object LedgerFormat { fun amount(amountMinor: Long, currencyCode: String): String; fun date(epochDay: Long): String }`. Depends only on `CurrencyExponent` + java.text/java.time. The single formatting authority; Home/History/Review call it. (Home's private `formatAmount`/`formatDate` and the History/Review copies are deleted.)
- **Models** — `Transaction` and `ReviewItem` each gain `currency: String`; their DAO queries `JOIN accounts a ON t.accountId = a.id` to project `a.currency` (mirroring the existing `CategoryAmountRow` pattern). `FakeLedgerRepository` mirrors the join.
- **`data/LedgerPreferences`** — `BACKGROUND_SYNC_ENABLED` (bool), `BACKGROUND_SYNC_HOURS` (long). **`data/LedgerConstants`** — `const val SIMPLEFIN_PERIODIC_TAG`.
- **`SettingsViewModel`** — `SettingsUiState` gains `backgroundSyncEnabled: Boolean` + `backgroundSyncHours: Int` (read in `reload()`); a `setBackgroundSync(enabled, hours)` writes the keys and updates state. Stays context-free/JVM-testable; the Screen owns `enqueuePeriodic`/`cancel`.
- **`SettingsScreen.ConnectedSimpleFinSection`** — a "Background refresh" toggle + interval selector; the Screen wires enable→`enqueuePeriodic(…, tag)`, disable→`cancel(tag)`; `disconnect` also cancels the tag + clears the keys.
- **`ui/settings/SimpleFinQrScannerScreen`** (new) + a "Scan QR" affordance in `SimpleFinConnectScreen`.
- **`tool/lighttool.toml`** — add `android.permission.CAMERA`.

## Error handling

- QR: the SDK `LightQrCodeScanner` handles permission-denied/error states internally (shows its own copy); a scanned-but-invalid token flows into `submit()` which already shows the calm "Couldn't connect" error. `LightQrCodeScanner` fires `onScanned` exactly once; navigation is deferred to a `LaunchedEffect` (pop scanner, then submit) per the example's warning.
- Periodic: `enqueuePeriodic` returns false if no `@LightJob` is registered (it is); WorkManager enforces the 15-min floor (our 6h+ intervals are fine). A disabled toggle cancels the tag; disconnect cancels + clears keys so a forgotten connection never keeps syncing.
- Currency: unknown/invalid ISO codes fall back to USD formatting (existing behavior in the extracted formatter).

## Testing

- **`LedgerFormatTest`** — `amount` for USD (`450→$4.50`), JPY (`1200→¥1,200`, exponent 0), BHD (`1234→BHD 1.234`, exponent 3), unknown code → USD fallback; `date` basic.
- **Repository contract tests** — `Transaction`/`ReviewItem` carry the account's real currency (seed a non-USD SIMPLEFIN account + a USD manual account; assert each row's `currency`).
- **`SettingsViewModelTest`** — `backgroundSyncEnabled`/`backgroundSyncHours` read from DataStore in `reload()`; `setBackgroundSync` persists them; `disconnect` clears them.
- No JVM test for the QR screen or the Screen's `enqueuePeriodic` wiring (camera/Compose/WorkManager) — verified on-device.
- **On-device:** QR screen renders + CAMERA permission-grant flow + live preview (real scan deferred to hardware); Background-refresh toggle enqueues/cancels the periodic tag (confirm via WorkManager state); currency/regression unbroken; disconnect cancels periodic + clears error/keys.

No schema migration (queries add a JOIN, not a column; DB stays `version=1`).

## Out of scope / deferred

Polished per-currency *breakdown lines* on Home (still shows the primary currency's total — the never-sum guard; per-currency rows remain deferred until genuinely needed). A real end-to-end QR scan on hardware. A perf index for the cross-source/pending scans.
