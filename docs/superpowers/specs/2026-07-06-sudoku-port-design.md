# Sudoku port into `tool/` — design spec

Date: 2026-07-06
Status: approved by Tyler (sections reviewed interactively)
Source scaffold: `~/Documents/sudoku-light` (native Kotlin/Compose, `PORTING_GUIDE.md` inside)
Canonical UX reference: `~/Documents/sudoku-light/reference/sudoku-light.html`

## Goal

Port the Sudoku Light scaffold into this repo's `tool/` module as a Light SDK tool that
passes the plugin scan (`LightSdkPlugin` blocked imports/patterns) and lint. The scaffold's
`ComponentActivity` + `setContent` entry point is replaced by the SDK screen stack
(`LightScreen<Result, VM>` / `LightViewModel<T>`, `navigateTo()` / `goBack()`).

Non-goals: submission packaging, real-LP3 sideload flow (`serverPackage` flip), engine changes.

## Decisions made (with Tyler, 2026-07-06)

1. **Theming:** follow `LightThemeController` — dual palette, not the prototype's fixed black.
2. **Solve feedback:** chime + haptic together, both gated by the single existing `sound`
   setting. Ringer/DND-aware branching is impossible in the sandbox (`getSystemService(` is a
   banned pattern); the OS gates each channel instead (media volume, system haptics).
3. **Tool identity:** `lighttool.toml` id `dev.tyler.sudoku`, label `Sudoku`. Kotlin package
   `dev.tyler.sudoku`. Id is permanent once published.
4. **Architecture:** Approach A — per-screen ViewModels, stateless `PuzzleStore`, no shared
   session singleton.

## Sandbox constraints that shaped the design

From `plugin/src/main/kotlin/com/thelightphone/plugin/LightSdkPlugin.kt`:

- Blocked imports include `android.app.*`, `android.content.Context`, `ComponentActivity`,
  `setContent`, `LocalContext`/`LocalView`/`LocalLifecycleOwner`, reflection.
- Blocked patterns include `getSystemService(`, `startActivity(`, `as Activity`,
  `LocalContext.current`, `.javaClass`.
- **Not** blocked: `android.media.*` (→ `AudioTrack` chime survives, Context-free),
  `LocalHapticFeedback`, material3, `androidx.compose.ui.window.Dialog`, `org.json`
  (we still drop it — see Persistence).
- `SealedLightContext` exposes exactly `dataStore` / `filesDir` / `fileShare`. The
  `dataStore` is the process-wide `preferencesDataStore("DEFAULT_DATASTORE")`, so every
  screen sees the same store — this is what lets `PuzzleStore` stay stateless per-VM.

## File layout

```
tool/lighttool.toml                  id="dev.tyler.sudoku", label="Sudoku", permissions=[]
tool/src/main/kotlin/dev/tyler/sudoku/
  ToolEntryPoint.kt                  @EntryPoint object, empty hooks (offline tool, no push)
  engine/SudokuEngine.kt             VERBATIM copy from scaffold, package rename only
  data/PuzzleStore.kt                KeyValueStore interface + DataStore impl; same keys
  data/Codecs.kt                     kotlinx-serialization DTOs (progress, puzzle cache,
                                     settings, index) — same JSON field names as scaffold
  feedback/SolveFeedback.kt          playChime() only (PCM arpeggio via AudioTrack)
  ui/theme/SudokuPalette.kt          Dark + Light palettes, LocalSudokuPalette
  ui/home/HomeScreen.kt              @InitialScreen
  ui/game/GameScreen.kt              LightScreen<GameResult, GameViewModel> + overlays
  ui/game/GameViewModel.kt           ported monolith minus routing
  ui/game/Board.kt                   Board() + PencilMarks(), palette-parameterized
  ui/archive/ArchiveScreen.kt        LightScreen<Unit, ArchiveViewModel>
tool/src/test/kotlin/dev/tyler/sudoku/
  engine/EngineTest.kt               VERBATIM copy — port-fidelity green bar
  game/GameViewModelTest.kt          new, over in-memory KeyValueStore
```

Deleted from the sample tool: `com/thelightphone/sample/*` (ToolEntryPoint is rewritten under
the new package). Not carried from the scaffold: `MainActivity.kt`, `AndroidManifest.xml`,
`res/values/*` (manifest is generated from `lighttool.toml`).

`tool/build.gradle.kts` is unchanged (Room/KSP wiring stays even though unused; serialization
plugin already applied).

## Screen graph

```
HomeScreen (@InitialScreen)
 ├─ navigateTo GameScreen(dateKey=today, diff)      ← Easy/Medium/Hard buttons
 │    └─ goBack(GameResult.OpenArchive) → Home callback → navigateTo ArchiveScreen
 └─ navigateTo ArchiveScreen                        ← "Past puzzles"
      └─ navigateTo GameScreen(dateKey, diff)       ← row chips (60 days back)
```

- `GameScreen` constructor params: `dateKey`, `difficulty` (closed over in the
  `navigateTo` factory lambda). Result type `GameResult` (`Closed` | `OpenArchive`).
- Archive ignores `GameResult` (already there); its `onScreenShow` re-reads the index from
  DataStore, so statuses auto-refresh after a game — no shared in-memory index.

## Lifecycle mapping (scaffold → SDK)

| Scaffold | Port |
|---|---|
| `MainActivity.onPause` → `vm.pause()` | `GameViewModel.onAppPause()` and `onScreenHide()`: stop timer, persist |
| return to foreground | `onScreenShow()`: restart ticker if `!solved` |
| (none — system back closed app) | `onBackPressed()`: overlay open → close it, return `true`; else persist, return `false` (screen pops) |
| `tick()` on interaction (TODO in scaffold) | real VM coroutine ticker, 250 ms while `running` |

## GameViewModel state model

`GameUiState` carried over with two changes:

- `screen: Screen` field deleted (routing is the platform's job now).
- Transient UI moves from `SharedFlow<GameEvent>` to state:
  - `overlay: Overlay?` — sealed: `Menu`, `HintPage`, `Settings`, `ConfirmReset`,
    `ConfirmReveal`, `Win(timeText, subtitle)`.
  - `toast: String?` — auto-cleared by a VM coroutine after ~1.7 s.

All game transforms (input/erase/undo/hints/check/reveal/reset/win, conflicts, peers,
auto-candidate) port line-for-line from the scaffold's `GameViewModel`.

## Persistence

Same four key families in the shared DataStore, same JSON field names as the scaffold
(`v/c/l/a/t/s/r`; index `{status, time}`; settings with `__v: 2` guard):

- `settings`, `index`, `puz:<date>:<diff>`, `prog:<date>:<diff>`

Encoding switches from `org.json` to kotlinx-serialization `@Serializable` DTOs:
org.json lives in `android.jar` (throws in JVM unit tests), kotlinx is already applied to the
module, and the scaffold's own guide marked org.json "swap later". No data migration exists —
this is a fresh install surface.

`PuzzleStore` = `KeyValueStore` interface (`get`/`set`/`delete` suspend, string values) +
`DataStorePuzzleStore(dataStore)` impl. VMs construct it from `lightContext.dataStore`.
Tests use an in-memory map impl.

Corrupt/missing stored JSON → `runCatching` → treated as absent. Losing a puzzle cache is
harmless: generation is deterministic per (dateKey, difficulty).

## Theming

`SudokuPalette` data class holds all board tokens (Bg, Frame, Line, Box, GivenTile, Peer,
SameNum, Sel, GivenInk, EntryInk, PencilInk, Ring, Dot, Txt, TxtDim, TxtFaint, Hair, Btn,
BtnLine, SelInk).

- `SudokuPalette.Dark` = scaffold's `SudokuColors` verbatim.
- `SudokuPalette.Light` = mirrored ramp, starting values: Bg `#FFFFFF`, GivenTile `#EBEBEB`,
  Peer `#E0E0E0`, SameNum `#D0D0D0`, Sel `#0E0E0E` (inverted selection), Frame `#232323`,
  Line `#E4E4E4`, Box `#B5B5B5`, GivenInk `#0F0F0F`, EntryInk `#363636`, PencilInk `#919191`,
  Ring `#757575`, Dot `#0E0E0E`, Txt `#121212`, TxtDim `#838383`, TxtFaint `#AAAAAA`,
  Hair `#D9D9D9`, Btn `#E9E9E9`, BtnLine `#CCCCCC`, SelInk `#F5F5F5`.
  Both ramps are device-calibration candidates (see scaffold guide).

Every screen's `Content()`: `val colors by LightThemeController.colors.collectAsState()` →
`LightTheme(colors) { CompositionLocalProvider(LocalSudokuPalette provides paletteFor(colors)) { … } }`
where `paletteFor` selects via `colors.inferredSurfaceScheme()`. Board/chrome read
`LocalSudokuPalette.current`. The prototype's `plain` setting keeps its existing meaning
(suppress highlights); no new settings toggle for theme.

Typography: keep the scaffold's own sizes (the sdk:ui scale is much larger); monospace for
the timer as before. Material3 components inherit sane colors from `LightTheme`'s
`MaterialTheme` wrapping.

## Solve feedback

- `SolveFeedback.playChime()`: the scaffold's PCM arpeggio (C5–E5–G5–C6) unchanged;
  `AudioTrack` + `AudioAttributes`/`AudioFormat` need no Context. Called from
  `GameViewModel.checkWin()` when `settings.sound`. try/catch fail-soft.
- Haptic: UI-side. `Content()` reads `LocalHapticFeedback.current`;
  `LaunchedEffect` keyed on the solved transition performs `HapticFeedbackType.LongPress`
  when a legitimate solve (not reveal) lands with `settings.sound` on.
- No VIBRATE permission needed (`performHapticFeedback` path); `permissions = []` in
  `lighttool.toml`.

## Overlays

Rendered inside `GameScreen.Content()` from `ui.overlay`:

- Menu sheet with Hint sub-page (Point/Fill), Reveal & reset entries → material3
  `ModalBottomSheet` (outside-tap closes).
- Settings sheet (scrollable, all toggles) → `ModalBottomSheet`.
- Confirm Reset / Confirm Reveal / Win modal → `androidx.compose.ui.window.Dialog`
  (scrim tap dismisses). Win modal: time + "Past puzzles" (→ `goBack(OpenArchive)`) / Close.
- Toast → bottom pill, VM auto-dismiss.

Board and controls are gated (non-interactive) while an overlay is open, as in the prototype.

## Error handling

- Chime/haptic failures: swallowed (never crash a solve).
- Hard-difficulty generation (~hundreds of ms) on `Dispatchers.Default` behind the
  `generating` full-screen state.
- DataStore read failures → absent → regenerate/reset to defaults.
- Settings version guard: `__v != 2` → ignore stored settings (all-off defaults).

## Testing

1. `EngineTest` verbatim; `./gradlew :tool:test` green = engine port faithful
   (determinism, uniqueness, technique grading).
2. `GameViewModelTest` (new) over in-memory `KeyValueStore`: input/candidate/undo,
   check-on-entry retro-flagging, win detection, reveal-blocks-win, progress round-trip,
   settings `__v` guard, timer accounting. `GameViewModel` takes a `now: () -> Long`
   parameter (defaulting to `System.currentTimeMillis`) so timer tests use a virtual clock.
3. Build gate: `:tool:assembleDebug` runs the plugin scan — zero violations expected.
4. Manual on `Light_Phone` AVD: both themes (toggle), back-button overlay semantics,
   archive refresh after solve, generating gate on hard.

## Risks / notes

- `LightActivity` hides system bars and the SDK controls insets; board sizing on the
  1080×1240 AVD needs a look early (the scaffold's 440 dp max-width shell is dropped —
  screens fill the SDK's container).
- material3 `ModalBottomSheet` under `LightTheme`'s MaterialTheme: verify scrim/container
  colors look right in both palettes; hand-style if not.
- Light-palette ramp values are guesses until seen on the panel; keep them in one file.
- The one-`sound`-toggle-drives-two-channels behavior is a deliberate sandbox compromise;
  revisit if the SDK ever exposes ringer state.
