# Flickable 4-margin keypad + shrink (Sudoku v1.3) — design spec

**Date:** 2026-07-15 · **Branch:** `sudoku` · **Builds on:** v1.2 adaptive floating keypad.

## Problem / goal
v1.2 docks the keypad TOP/BOTTOM opposite the selected cell. The user wanted (1) a **smaller** keypad
that covers at most the 3 board cells nearest its edge, and (2) the ability to **flick** it to any of
the four screen margins, with the smart "keep the selected cell visible" behaviour extended to all four.

## Interaction model
- **Two margin values.** `settings.keypadMargin` (persisted **preferred** margin, default BOTTOM,
  stored as a `String` so the `data` layer stays independent of the UI enum) and
  `GameUiState.keypadDockNow` (transient **rendered** margin).
- **Flick wins.** A directional flick calls `setKeypadMargin(m)`, which sets `keypadDockNow = m`
  *directly* (authoritative — even if `m` covers the current cell) and persists `m` as preferred.
- **Auto-avoid on selection.** `select`/`undo`/`pointHint` set `keypadDockNow = keypadDock(i, preferred)`
  = the pure transition: use `preferred` unless it hides `i`'s 3-cell band, else the **opposite**
  (TOP↔BOTTOM, LEFT↔RIGHT). `hides`: TOP→rows 0-2, BOTTOM→6-8, LEFT→cols 0-2, RIGHT→6-8. A cell in one
  band can't be in its opposite's, so the hop always un-hides it. This split is the crux: a pure
  recompute would instantly undo a flick toward a covered cell (the "flick feels inverted" bug).
- **Directional flick.** `flickToMargin` = `pointerInput { detectDragGestures }`; on drag-end, past a
  24dp threshold, the dominant axis+sign picks the margin (up→TOP … right→RIGHT). Slop-based, so a
  stationary tap on a key never reads as a flick.

## Layouts
- **Horizontal (TOP/BOTTOM):** full-width card, `height(boardSize/3)` (exactly 3 rows), 3 equal
  weighted rows — control row (Normal/Candidate switcher + Auto + Undo + ▾) then two digit rows.
- **Vertical (LEFT/RIGHT):** `width(boardSize/3)` strip — a 3×3 digit grid + a compact `IconKey`
  control stack (✎ mode toggle, A auto, ✕ erase, ↺ undo, ▾ dismiss). Placed with `AbsoluteAlignment`
  so a physical-left flick always docks physically left (RTL-proof).
- Keys go sub-minimum (like the board cells). `NumKey`/`SegButton` were made size-agnostic; `IconKey`
  is the shared control-key primitive. The v1.2 no-op tap-consumer is retained.

## Changes
- `ui/game/GameViewModel.kt`: `KeypadDock` → {TOP,BOTTOM,LEFT,RIGHT}; `hides`/`opposite`;
  `keypadDock(selected, preferred)`; `keypadDockNow` state; `setKeypadMargin` (shielded settings write
  via `withContext(NonCancellable)`); `marginPref(settings)`; recompute in select/undo/pointHint/open.
- `ui/game/GameScreen.kt`: 4-way `Modifier.align`; `FloatingKeypad(ui, horizontal, boardSize)` with the
  two layouts; `flickToMargin`; `IconKey`; size-agnostic `NumKey`/`SegButton`.
- `data/Codecs.kt`: `keypadMargin: String = "BOTTOM"` on `Settings`/`SettingsDto` + encode/decode,
  keeping `__v = 2` (old blobs decode to BOTTOM, no reset).

## Verification
- **Unit tests (59):** `keypadDock` truth table + opposite-hop + "rendered margin never hides the cell"
  invariant across all 4 preferred margins; flick-wins guard; select/undo recompute; margin persists
  across reopen; settings round-trip + old-blob default. (GameViewModelTest 50, CodecsTest 9.)
- **On-device (Light_Phone AVD):** shrunk keypad; flick to all 4 margins; vertical reshape; digit taps
  still fire after the drag detector; auto-avoid across 4 margins; flick-wins.
- **Reviews:** independent code review (4 Minor, 0 Critical; fixed the cancellable-settings-write and
  the RTL placement mismatch, deduped `marginPref`, clamped `keyH`) + a clean security/robustness pass.

## Release
`lighttool.toml` → versionCode 4 / versionName "1.3"; signed with the `lightsdk-dev` cert (installs as
an update); published as `sudoku-v1.3` on `tyleryancey/light-tools`.
