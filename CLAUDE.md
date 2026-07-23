# CLAUDE.md — Chess (Light Phone 3 tool: solo v1 · correspondence v2 + tiny relay)

Chess for the LP3, in two phases. **v1 is a complete offline game against a computer opponent** — finite, quiet, zero-network, Sudoku's shelf-mate — and targets the Aug–Sep vetting window. **v2 adds correspondence play**: one move per day against a human, social without a feed, contemplative by design, finite by rule — arguably the most philosophically Light multiplayer experience possible. The engine could generalize to any turn-based game later.

**Status: v1 unblocked and buildable now; v2 gated.** The server-ops commitment gate (00-ASSESSMENT.md) applies only to the v2 relay — do not start `relay/` until that commitment is explicit. Nothing in v1 touches a server, so the gate no longer blocks shipping chess. This supersedes CLAUDE-correspondence-chess.md, where the whole project sat behind the gate.

**Scope rule, revised (was "no AI, ever"):**

- A **local computer opponent is in scope** — honest, labeled, adjustable. It is alpha-beta search over a hand-written eval. Never call it "AI" in UI or listing copy; it's "the computer" / "computer opponent."
- **The relay never generates moves.** Correspondence opponents are always human — no bots impersonating people, and no engine assistance surfaced inside correspondence games (see Sharp edges). This is the philosophical core the old rule protected; it survives intact.

## Why the phase flip (recorded so it isn't relitigated)

- Correspondence-only requires both players to own LP3s **and** the relay to be live — near-zero addressable users at Tool Library launch. Solo chess is useful to every owner on day one.
- Single-player-first frames the tool as a **game** (Sudoku precedent) rather than messaging-adjacent — the safer vetting category. Correspondence becomes a feature, not the identity.
- v1 ships with **`permissions = []`**. An empty permission list reads well in review.
- **Open question for the Developer Program, ask before v1 submission:** do post-approval feature updates get re-vetted? The answer decides whether v2 is a quiet update or a resubmission.

## Product rules

**Shared:** games end by checkmate/stalemate/draw-by-rule/resign; finished games are a finite archive. Board is a monochrome Canvas; pieces are **original drawn glyphs or vector outlines** (do not lift piece bitmaps from any chess set without a verified-compatible license); light/dark squares by texture/weight in grayscale; board flips to the human's color.

**Solo (v1):**

- New game: choose White / Black / random, choose level. That is the entire flow — color and level live in game creation, not settings. Settings stay default-off and near-empty (v1 may ship with none).
- **Three levels, calm names** (working: Gentle / Fair / Strong; final naming at M4). No ELO claims anywhere, ever.
- **No pacing in solo.** The 8 h rule exists to make human correspondence sustainable, not as an end in itself; solo is Sudoku-class practice. (A "daily move vs the machine" ritual variant is on-brand and zero-server — parked, not built; do not grow settings for it.)
- Computer thinks ≤ ~2 s on `Dispatchers.Default` behind a quiet "thinking…" state. Kill/relaunch mid-think must not corrupt the game — resume replays the move list and the bot recomputes if it's to move.

**Correspondence (v2, unchanged):**

- Two players, one game per pair at a time; join by 6-char code exchanged out-of-band (or QR — the LP3 scans; the code is 6 chars, typing is fine; QR optional polish).
- **No chat. No presence. No timestamps-since-seen. Ever.** Moves are the entire vocabulary. This is both the product and the vetting firewall against the messaging/social category.
- Move pacing: the relay rejects a move submitted **< 8 h after your previous own move** (tunable constant, server-side, returned as a clean "come back tomorrow" error). Turn order is absolute regardless. Client-side, moves inside the window are simply disabled — the 429 is the backstop, not the UX. Rejection renders in the product's voice: "Your move is in. Come back tomorrow."

## Chess engine (`engine/` — pure JVM, the first rock; role unchanged: legality only)

The bot lives in its own module; `engine/` stays rules-and-legality. Board as `IntArray(64)` piece codes + castling rights, en-passant square, half/full-move counters. Full legal move generation (castling, en passant, promotion), check/checkmate/stalemate, draw detection (50-move, threefold via position-hash history, insufficient material), FEN serialize/parse, UCI-string moves (`e2e4`, `e7e8q`) as the wire format, SAN rendering for the move list display.

**Two API requirements added at M1, because retrofitting hurts:**

- **make/unmake with an undo stack.** Search cannot afford board copies per node; `makeMove`/`unmakeMove` must be in the Board API from day one.
- **Zobrist hashing as a first-class export.** Already required for threefold — the key must include side-to-move, castling rights, and the EP square — and the bot reuses the same hash as its transposition-table key.

**Perft gate (non-negotiable before anything else):** startpos depths 1–4 = 20 / 400 / 8 902 / 197 281 (d5 = 4 865 609 optional, tag `@Slow`); Kiwipete (`r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -`) depths 1–3 = 48 / 2 039 / 97 862; plus FEN round-trip and a scripted full game to checkmate. These counts are canonical — if they don't match, the engine is wrong, not the test.

## Computer opponent (`bot/` — pure JVM, sibling module, the second rock)

Be honest about scope: **a playing engine is a second project of roughly the same size as the legality engine.** Material-only search at shallow depth hangs pieces past the horizon and plays embarrassingly. The floor for a decent opponent:

- **Eval:** material + piece-square tables. Midgame/endgame interpolation is optional polish; start simple.
- **Search:** alpha-beta with iterative deepening; **quiescence search on captures** (the single biggest quality lever — non-negotiable); MVV-LVA move ordering (killer moves if cheap); transposition table keyed by the engine's Zobrist hash, small fixed size (a few MB).
- **Time:** hard cap ~2 s per move at the top level; lower levels cap depth instead of time.
- **Difficulty = search budget + selection noise.** Starting profile, tuned by self-play:
  - **L1:** depth 2; choose among all moves within a wide eval window of best (high temperature).
  - **L2:** depth 4; narrow window.
  - **L3:** iterative deepening to the 2 s cap; best move; TT on.
- Runs on `Dispatchers.Default`, cancellable, and **deterministic given (position, level, seed)** so bugs reproduce and self-play doesn't flake.

**Bot gate (green before any solo UI starts):**

- **Tactics suite:** small curated EPD file in-repo — mate-in-1 and mate-in-2 sets solved at L3, plus a "don't hang" set (obvious captures/recaptures found).
- **Self-play ladder in CI:** L3 beats L1 in ≥ 80% of 100 fast games (reduced time caps); L2 lands strictly between. If the ladder inverts, difficulty is broken.
- **Device time budget:** L3 move ≤ 2 s on the LP3, measured and logged at M3.

## Phone tool

```toml
[tool]
id = "dev.tyler.chess"   # decide now — the namespace derives from tool.id and it's forever; chess-first beats "corrchess"
label = "Chess"
permissions = []          # v1. v2 adds android.permission.INTERNET + ACCESS_NETWORK_STATE.
# serverPackage = emulator on AVD / "com.lightos" on LP3, as usual
```

```
engine/ (pure JVM)  ·  bot/ (pure JVM)  ·  data/ (Room: games, moves · DataStore: [v2] secrets, relay URL)
ui/ HomeScreen (games list + NEW GAME → vs Computer | vs Friend [v2]) · GameScreen (shared board Canvas, tap-tap moves with legal-target dots, move list, status) · JoinScreen [v2]
net/RelayApi.kt (Ktor) [v2] · ToolEntryPoint push wiring [v2]
```

- Room: `games.opponent` = `LOCAL(level, humanColor)` | `REMOTE(…)`; the moves table is shared; **every state is rebuildable from the move list, solo included** (resume = replay moves; bot recomputes if it's to move).
- Solo GameScreen: human moves → bot replies after think; status line covers check/mate/draw; resign concedes to the machine and archives like any finish.
- **Correspondence GameScreen exposes no hints or analysis, ever.** The bot module existing on-device must not become engine assistance in human games. (Solo may grow a hint later; not v1.)

## v2: Correspondence (everything below is gated on the ops commitment)

**Two pitch corrections from source (carried over):**

1. `GetToken` is **not identity** — the SDK server issues per-UID session UUIDs for IPC auth. Identity here is game-scoped secrets the relay mints (below).
2. Push delivers **data, not a visible nudge** — nothing in the snapshot surfaces notifications to the user. Push is a silent state-warmer: the board is already fresh when the player opens the tool. The "your move" moment is opening the phone — which is the correspondence ethos anyway. **Poll-on-open is the correctness path; push is the freshness bonus.**

**Verified SDK facts this phase is built on:**

- **LightOS is a UnifiedPush distributor** (`LightPushDistributor`, `sdk:server` manifest). `LightSdkApplication` force-selects it and registers a remote channel with VAPID (`LIGHT_VAPID_KEY` BuildConfig) when `LightEntryPoint.enablePushNotifications = true`. Push credentials (endpoint) stream to `onToolCreate(serverData: StateFlow<LightServerData?>)` → send to the relay; payloads arrive raw in `onPushNotification(data: ByteArray)`.
- `unifiedpush connector` is on the dependency allowlist; INTERNET/ACCESS_NETWORK_STATE permitted; Ktor client allowlisted. Room for local game state; `java.time`.
- **Phase-0 verifications before v2 feature code:** (a) read `LightServerData` + `LightPushManager` to pin the credential shape and how the LP3's endpoint URL is formed; (b) where `LIGHT_VAPID_KEY` comes from in a tool build (gradle property? SDK-provided?) — this determines whether the relay does real VAPID web-push or the field stays empty and push is skipped (poll-only still ships a complete product); (c) confirm `onPushNotification` fires with the tool cold (process not running) on the LP3 — if it only fires warm, push demotes to "nice while charging" and nothing else changes.

**Relay (`relay/` in-repo — Ktor JVM, target ≤ ~250 lines, FOSS, self-hostable):**

Thin, but **no longer legality-blind** — this is a deliberate revision. The relay is Kotlin in the same repo as a pure-JVM engine; importing `engine/` and validating each submitted move is ~10 lines, fits the 250-line budget, and closes a real failure mode: a buggy or hostile client submits an illegal move, the relay stores it, the honest client rejects it, and the game wedges permanently. The relay checks turn order, pacing, game status, **and legality**. Clients still validate everything they fetch (defense in depth), and an invalid fetched move marks the game **void** client-side — never crash, never auto-resign. Storage: SQLite via JDBC or flat JSON-per-game — pick boring.

```
POST /v1/games                     {}                        → {gameId, joinCode, playerSecret, color:"w"}
POST /v1/games/join                {joinCode}                → {gameId, playerSecret, color:"b"}
GET  /v1/games/{id}?since={n}      Auth: Bearer playerSecret → {moves:[{n,uci}], status, yourColor, drawOffer}
POST /v1/games/{id}/moves          {n, uci}  + Bearer        → 200 | 409 out-of-turn/stale-n | 422 illegal | 429 pacing | 410 game over
POST /v1/games/{id}/resign|draw    + Bearer                  → status
POST /v1/push/register             {gameId, endpoint, keys?} + Bearer → 204   (relay web-pushes opponent's endpoint on each accepted move)
```

`playerSecret` = 128-bit random, stored in the tool's DataStore; per-game; loss of phone = loss of seat (acceptable — say so). Rate-limit by IP + secret. No emails, no accounts, no names — colors only, or a self-chosen 12-char handle stored locally and sent never.

**Correspondence client behavior:** open → `GET ?since` per active remote game (parallel, tolerant of offline: render local state, mark "checking…" quietly). Submitting: optimistic-render, reconcile on 409 by re-syncing.

## Milestones · definitions of done

**v1 track (targets the Aug–Sep window):**

- **M1 Engine (pure JVM)** — perft + rules gate green; make/unmake and Zobrist in the Board API. **Nothing else starts first.**
- **M2 Bot (pure JVM)** — tactics suite + self-play ladder green in CI.
- **M3 Solo tool on AVD → LP3** — a full game against each of L1–L3 to a finish; kill/relaunch mid-game and mid-think resumes cleanly; L3 ≤ 2 s measured on the device.
- **M4 Polish + v1 submission** — archive, resign flow, glyph pass on the actual panel, `permissions = []` confirmed, game-first defense paragraph, Developer-Program answer on update re-vetting in hand.

**v2 track (starts only when the ops commitment is explicit):**

- **M5 Phase 0** — the three push verifications; relay skeleton answers healthcheck locally.
- **M6 Relay** — endpoint tests (turn order, pacing, auth, replay/stale-n, **illegal-move rejection**) via Ktor test host; runs on a $4 VPS or Tyler's home box behind TLS.
- **M7 Tool vs relay on AVD** — two AVDs play a full game to mate; kill/relaunch mid-game resumes.
- **M8 Push** — if Phase-0 allowed: move on device A appears on sleeping device B's board at next open without a manual sync (verified by log timestamps).
- **M9 Correspondence polish + update submission** — join/QR, draw-offer flow, self-host README for the relay, updated defense paragraph.

## Vetting defense (seed, rewritten game-first)

Chess is a complete game that works with the radios off: finite matches against a computer opponent, three quiet difficulty levels, no network, no permissions, no feed — a shelf-mate to Sudoku. The correspondence update adds multiplayer with the surface area of a postcard: two humans, moves only, no chat, no presence, and a server-enforced cadence of one move per day, through a ~250-line open-source, self-hostable relay that stores move sequences and nothing about people. It demonstrates that the LP3 can be social on Light's terms — contemplative, finite, and quiet.

## Sharp edges

- **Quiescence is not optional.** Without it the bot hangs pieces at every horizon and no amount of difficulty tuning hides it.
- Everything through the engine: the relay validates legality (it can — same repo, same JVM), clients validate everything they fetch anyway, and an invalid fetched move = game void, never a crash.
- Threefold repetition needs position hashing with **side-to-move, castling rights, and EP square** in the key or it's wrong — and the bot's TT inherits the same key, so a hashing bug now breaks two modules. Test the hash directly, not just through draw detection.
- UCI promotion suffix (`e7e8q`) — test all four pieces, including in bot games (the bot must be *able* to underpromote legally even if it rarely chooses to).
- **Bot determinism:** (position, level, seed) → same move, or the self-play ladder flakes and field bugs are unreproducible.
- **No engine assistance in correspondence UI, ever.** The cheapest way to invalidate the "social on Light's terms" pitch is a hint button in a human game.
- `onPushNotification` payloads are `ByteArray` — define the JSON envelope once, versioned (`{"v":1,…}`), and ignore unknown versions silently.
- If the relay is ever down for a week, nothing breaks — poll-on-open against a recovered server resyncs from `since=0`. Design every state, solo included, to be rebuildable from the move list.

## Repo integration notes (scaffold, 2026-07-22)

Facts established when this branch was scaffolded, so they aren't rediscovered. This repo is tyleryancey's fork of `lightphone/light-sdk`; each tool lives on its own long-lived branch of the fork (`sudoku`, `ledger`, now `chess`), editing the `tool/` module in place. Tool branches never merge to `main`; `main` advances by **merging** upstream (it carries fork-only CI commits, so fast-forward is impossible — the weekly sync workflow does a real merge and lists the incoming upstream commits in the merge commit body, for `main` and for each tool branch).

- **Amendment to the module diagram above:** `engine/`, `bot/`, `data/`, `ui/` are **packages inside `tool/src/main/kotlin/dev/tyler/chess/`**, not sibling Gradle modules. The official server-side builder (`builder/lightbuilder/extract.py`) extracts only `tool/build.gradle.kts` + `tool/lighttool.toml` + `tool/src/main/**` and discards every other module, and the `sudoku` branch (this charter's own precedent) uses exactly this layout (`dev.tyler.sudoku.{engine,data,ui,feedback}`). "Pure JVM" for `engine`/`bot` survives as a discipline: no `android.*`/`androidx.*`/Compose imports in those two packages; their unit tests are plain-JVM tests under `tool/src/test/kotlin/`. The v2 `relay/` is a server-side artifact and is unaffected (it never ships in the APK), but it cannot import `engine/` as a Gradle module — it will need the engine sources shared another way (decide at M6; simplest: the relay module includes `tool/src/main/kotlin/dev/tyler/chess/engine` as a source directory).
- **Build:** JDK 17, Gradle 9.0, Kotlin 2.3.20, AGP 8.12.3. Gate: `./gradlew check` (CI runs it only on PRs to `main`, so tool branches verify locally). Tests are `kotlin.test` style. Resolving the SDK's private `lp3keyboard` artifact needs GitHub Packages credentials: `gpr.user`/`gpr.key` in `local.properties` (gitignored) or `GH_PACKAGES_USER`/`GH_PACKAGES_TOKEN` env vars.
- **Tool policy (enforced by the `com.thelightphone.light-sdk` plugin):** manifest is generated from `tool/lighttool.toml`; never hand-write `AndroidManifest.xml` or set `namespace`/`applicationId`/`versionCode`/`versionName` in `build.gradle.kts`. Exactly one `@EntryPoint object : LightEntryPoint` and one `@InitialScreen` screen class. Banned in tool sources: `android.content.*`/`Activity`/`Intent`/`Service`, reflection, `LocalContext`/`LocalView`. Dependency allowlist already covers everything v1/v2 need (Compose, coroutines, Room + KSP room-compiler, DataStore, Ktor, kotlinx-serialization, unifiedpush connector).
- **SDK idioms for M3** (all under `sdk/`): screens are `LightScreen<Result, VM>`/`SimpleLightScreen` navigated via `navigateTo {}` (`sdk/client/.../LightScreen.kt`); Room via `lightContext.buildDatabase(...)` (`sdk/client/.../LightDb.kt`, exemplar `examples/authenticator/`); prefs via `lightContext.dataStore`; theme is a strict 3-color monochrome palette read from `LightThemeTokens.colors` (`sdk/ui/.../LightTheme.kt`); tap input via `Modifier.lightClickable` (no ripple); size the board in `LightGrid` units (27×31 grid, `sdk/ui/.../LightGrid.kt`); Canvas drawing is standard Compose `DrawScope` (precedent: `QrViewfinderOverlay` in `sdk/ui/.../LightQrCodeScanner.kt`). The nav back stack and ViewModels are in-memory only — process death loses them — which is why every state must be rebuildable from the Room move list (already a rule above).
- **Done 2026-07-23:** `chess` is in the sync matrix on `main`. The sync workflow was also repaired (its `--ff-only` step could never succeed once `main` carried the workflow's own commits) and the repo setting "Allow GitHub Actions to create and approve pull requests" was enabled so the conflict-PR notification path works.
- **`versionName` must be semver** (`x.y.z`) — upstream builder enforcement merged 2026-07-23; `lighttool.toml` is at `0.1.0`.
