package dev.tyler.chess.bot

import dev.tyler.chess.engine.Board
import dev.tyler.chess.engine.NO_MOVE

/**
 * Search budgets. Limits are node-count based so the same (position, level,
 * seed) always yields the same move on every device — the charter's
 * determinism rule. [deadlineNanos] (absolute, System.nanoTime scale) is a
 * device-side wall-clock backstop the UI may pass; a fired deadline forfeits
 * determinism by design and exists only to honor the ~2 s think budget.
 */
class SearchLimits(
    val maxDepth: Int,
    val maxNodes: Long,
    val deadlineNanos: Long = Long.MAX_VALUE
)

// Deterministic seeded generator for the difficulty noise (same SplitMix64
// mix as the engine's Zobrist fill; constants are the negative two's-
// complement forms of the canonical unsigned values).
internal class SplitMix64(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()
}

/**
 * The computer opponent. Difficulty = search budget + selection noise:
 * Gentle searches depth 2 and picks uniformly among all moves within a wide
 * window of the best; Fair searches depth 4 with a narrow window; Strong
 * runs iterative deepening with the transposition table and always plays
 * the best move (seed-independent).
 *
 * Contracts:
 * - Single caller at a time: the transposition table is shared state.
 * - [chooseMove] mutates the board during search but always restores it,
 *   aborted searches included.
 * - Pass the board built by replaying the game's move list (never
 *   Board.fromFen of a snapshot): repetition awareness lives in the undo
 *   stack's history.
 * - Deterministic: same (position + history, level, seed, limits) → same
 *   move. The table is cleared on every call so no state leaks between moves.
 */
object Bot {
    const val LEVEL_GENTLE = 1
    const val LEVEL_FAIR = 2
    const val LEVEL_STRONG = 3

    private val ttKeys = LongArray(TT_SIZE)
    private val ttData = LongArray(TT_SIZE)

    fun limitsFor(level: Int): SearchLimits = when (level) {
        LEVEL_GENTLE -> SearchLimits(maxDepth = 2, maxNodes = 100_000)
        LEVEL_FAIR -> SearchLimits(maxDepth = 4, maxNodes = 400_000)
        // Node budget provisional until M3 measures the LP3; the UI passes a
        // deadline backstop for the ~2 s cap.
        else -> SearchLimits(maxDepth = 32, maxNodes = 600_000)
    }

    private fun windowFor(level: Int): Int = when (level) {
        LEVEL_GENTLE -> 150
        LEVEL_FAIR -> 40
        else -> 0
    }

    /** Returns a legal move for the side to move, or [NO_MOVE] if the game is over. */
    fun chooseMove(
        board: Board,
        level: Int,
        seed: Long,
        limits: SearchLimits = limitsFor(level),
        cancelled: () -> Boolean = { false }
    ): Int {
        ttKeys.fill(0L)
        ttData.fill(0L)
        val window = windowFor(level)
        val result = Search(board, limits, cancelled, ttKeys, ttData).findMove(window)
        if (result.bestMove == NO_MOVE) return NO_MOVE
        if (window == 0 || result.candidates.size <= 1) return result.bestMove
        return result.candidates[SplitMix64(seed).nextInt(result.candidates.size)]
    }
}
