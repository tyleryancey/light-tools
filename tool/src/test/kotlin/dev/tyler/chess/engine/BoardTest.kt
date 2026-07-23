package dev.tyler.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardTest {
    @Test
    fun makeUnmakeRestoresFullState() {
        // One case per special-move mechanism.
        val cases = listOf(
            "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1" to "e1g1", // castle
            "r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1" to "e8c8", // castle, black
            "rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 3" to "d4e3", // en passant
            "3r3k/4P3/8/8/8/8/8/4K3 w - - 0 1" to "e7d8n", // underpromotion capture
            "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 2" to "d4e5" // plain capture
        )
        for ((fen, uci) in cases) {
            val board = Board.fromFen(fen)
            val fenBefore = board.fen()
            val keyBefore = board.zobristKey
            assertTrue(board.tryMoveUci(uci), "$uci legal in $fen")
            board.unmakeMove()
            assertEquals(fenBefore, board.fen(), "fen restored after $uci")
            assertEquals(keyBefore, board.zobristKey, "key restored after $uci")
        }
    }

    // Charter rule: every state must be rebuildable from the move list alone.
    @Test
    fun replayRebuildsIdenticalState() {
        val moves = listOf(
            "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5c6", "d7c6", "e1g1"
        )
        val incremental = Board.initial()
        for (uci in moves) assertTrue(incremental.tryMoveUci(uci), "$uci legal")
        val replayed = Board.replay(moves)
        assertEquals(incremental.fen(), replayed.fen(), "replayed fen")
        assertEquals(incremental.zobristKey, replayed.zobristKey, "replayed key")
        assertEquals(incremental.repetitionCount(), replayed.repetitionCount(), "replayed repetition count")
    }

    @Test
    fun replayFromFenStartsMidGame() {
        val fen = "rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 3"
        val board = Board.replay(listOf("d4e3"), fromFen = fen)
        assertEquals(WHITE, board.sideToMove, "white to move after EP capture")
        assertEquals(EMPTY, board.pieceAt(parseSquare("e4")), "EP victim removed")
        assertEquals(B_PAWN, board.pieceAt(parseSquare("e3")), "capturing pawn landed")
    }

    @Test
    fun replayThrowsWithIndexOnIllegalMove() {
        val failure = assertFailsWith<IllegalArgumentException> {
            Board.replay(listOf("e2e4", "e2e4"))
        }
        assertTrue("index 1" in failure.message.orEmpty(), "message names the offending index")
    }

    @Test
    fun tryMoveUciRejectsIllegalAndGarbage() {
        val board = Board.initial()
        assertFalse(board.tryMoveUci("e7e5"), "wrong side's move")
        assertFalse(board.tryMoveUci("e2e5"), "illegal distance")
        assertFalse(board.tryMoveUci("e2"), "truncated")
        assertFalse(board.tryMoveUci("zz99"), "garbage squares")
        assertFalse(board.tryMoveUci("e2e4x"), "bad promotion char")
        assertTrue(board.tryMoveUci("e2e4"), "legal move accepted")
        assertEquals(BLACK, board.sideToMove, "state advanced exactly once")
    }

    @Test
    fun undoStackGrowsPastInitialCapacity() {
        val board = Board.initial()
        val shuffle = listOf("g1f3", "g8f6", "f3g1", "f6g8")
        repeat(150) {
            for (uci in shuffle) assertTrue(board.tryMoveUci(uci), "$uci legal")
        }
        repeat(600) { board.unmakeMove() }
        assertEquals(Board.initial().fen(), board.fen(), "unwound to startpos")
        assertEquals(Board.initial().zobristKey, board.zobristKey, "startpos key restored")
    }
}
