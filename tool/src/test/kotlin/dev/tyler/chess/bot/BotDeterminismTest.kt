package dev.tyler.chess.bot

import dev.tyler.chess.engine.Board
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The charter's determinism rule: (position, level, seed) → the same move,
 * or the self-play ladder flakes and field bugs are unreproducible.
 */
class BotDeterminismTest {
    private val midgame = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -"

    @Test
    fun sameInputsSameMoveAtEveryLevel() {
        for (level in 1..3) {
            val first = Bot.chooseMove(Board.fromFen(midgame), level, seed = 42)
            val second = Bot.chooseMove(Board.fromFen(midgame), level, seed = 42)
            assertEquals(first, second, "level $level is deterministic")
        }
    }

    @Test
    fun strongLevelIgnoresTheSeed() {
        val a = Bot.chooseMove(Board.fromFen(midgame), Bot.LEVEL_STRONG, seed = 1)
        val b = Bot.chooseMove(Board.fromFen(midgame), Bot.LEVEL_STRONG, seed = 987_654_321)
        assertEquals(a, b, "L3 plays the best move regardless of seed")
    }

    @Test
    fun immediateCancellationStillReturnsALegalMove() {
        val board = Board.fromFen(midgame)
        val fenBefore = board.fen()
        val m = Bot.chooseMove(board, Bot.LEVEL_STRONG, seed = 5, cancelled = { true })
        assertTrue(board.legalMoves().contains(m), "aborted search still yields a legal move")
        assertEquals(fenBefore, board.fen(), "board restored after aborted search")
    }

    @Test
    fun boardIsRestoredAfterEverySearch() {
        for (level in 1..3) {
            val board = Board.fromFen(midgame)
            val fen = board.fen()
            val key = board.zobristKey
            Bot.chooseMove(board, level, seed = 9)
            assertEquals(fen, board.fen(), "fen restored (level $level)")
            assertEquals(key, board.zobristKey, "zobrist key restored (level $level)")
        }
    }
}
