# Adaptive floating keypad (Sudoku v1.2) — design spec

**Date:** 2026-07-14 · **Branch:** `sudoku` · **Supersedes:** the v1.1 "minimizable keyboard."

## Problem
On the Light Phone 3's short, near-square panel (~411×472dp @ density 420), a full-width Sudoku
board is ~387dp square, leaving only ~85dp beneath it. In v1.1 the keypad was a non-weighted
`Column` child rendered **below** the board, so whenever a cell was selected it stole the board's
`weight(1f)` height and crushed the board to a ~130–250dp thumbnail — and the "Candidate" pill wrapped
to one letter per line. The "minimize" handle only *hid* this; the instant you entered a number you
were back to the cramped layout.

## Goal
Keep the board full-size **while entering numbers**, with the selected cell always visible.

## Design — adaptive floating keypad
- The board is **always full size (~387dp)**; it never trades height for the keypad again.
- The keypad is a **z-stacked overlay** inside a board-relative anchor `Box(Modifier.size(boardSize))`
  that also holds `Board`. It renders only while an editable cell is selected (existing gate,
  `shouldShowKeypad()`), and is drawn *after* the board so it wins hit-testing in its bounds.
- **Dock rule:** the panel docks on the half **opposite** the selected cell, so that cell (and its
  neighbouring rows) stay visible. Low rows (5–8) → dock **TOP**; upper rows (0–4) → dock **BOTTOM**.
  Encoded as a pure, unit-tested VM function `keypadDock(selected: Int): KeypadDock` and consumed as a
  Compose state read `keypadDock(ui.selected)` so the panel repositions when the selection moves.
- The panel is an **opaque framed card** (`pal.bg` fill + 2dp `pal.frame` border, inset 4dp off the
  board edge so the two frames don't merge) housing everything, so the **top bar gains no controls**:
  Normal/Candidate switcher, Auto-candidate, Undo, `▾` dismiss (`deselect()`), and digits 1–9 + erase.
- The panel root **consumes taps across its full bounds** (no-op `clickable` placed *before* the outer
  padding) so taps in gaps/gutters don't fall through to the board cell beneath.

### Accepted trade-offs
- **Occlusion:** the panel covers ~40% of the *non-selected* half. Unavoidable on a square panel with
  ~10–20dp of true slack — a below-board control strip would re-shrink the board and defeat the goal.
- **Re-dock "teleport":** because the dock is derived fresh from the selection, tapping a visible cell
  across the row-4/5 boundary flips the panel top↔bottom. Inherent to adaptive docking; **hysteresis**
  (re-dock only when the current dock would cover the new cell) is the deferred fix if it feels wrong.
- **Key height 44dp** (was 50dp) to keep the panel under half the board — just under Material's 48dp
  guideline, comfortable on the LP3.

## Changes
- `ui/game/GameViewModel.kt`: add `enum KeypadDock { TOP, BOTTOM }` + pure `keypadDock(selected)`.
  Nothing else changes — the full existing VM contract (and its keypad-visibility tests) is untouched.
- `ui/game/GameScreen.kt`: board-relative anchor + `FloatingKeypad` overlay in `Content()`; new
  `FloatingKeypad` (re-homes the old `Controls`); remove `Controls`/`KeypadHandle`; `SegButton` gains a
  `weight` modifier + `maxLines=1`/`softWrap=false`/`Ellipsis` (kills the vertical-wrap bug); `NumKey`
  50→44dp. `summonKeypad()` is retained as valid VM API though no longer wired to UI chrome.
- `ui/game/Board.kt`: unchanged (fonts derive from `boardSize`).

## Verification
- **Unit tests (46):** 4 new `keypadDock` tests (full 0–80 truth table, row-4/5 boundary, `-1` default,
  a non-tautological "selected row never under its own dock" invariant) + all 42 prior tests green.
- **On-device (Light_Phone AVD, dark):** idle full board; low cell → top dock; high cell → bottom
  dock; digit entry persists the keypad; `▾` dismisses; no text wrap. Light theme verified by
  construction (same palette tokens as the board; AVD theme is SDK-controlled, not flippable via
  Android night mode).
- **Reviews:** independent correctness review (1 real tap-fall-through bug found → fixed
  consume-before-inset; 1 defensive ellipsis applied) + a lightweight security/robustness pass (clean:
  `keypadDock` total over Int, no new write paths, no persistence/permission/network change, `▾` is a
  guaranteed escape).

## Release
`lighttool.toml` → versionCode 3 / versionName "1.2"; signed release with the `lightsdk-dev` cert
(same as v1.0/v1.1 → installs as an update); published as `sudoku-v1.2` on `tyleryancey/light-tools`.
