package dev.tyler.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveGenTest {
    private fun uciSet(moves: IntArray): Set<String> = moves.map { moveToUci(it) }.toSet()

    @Test
    fun startposHasTwentyMoves() {
        assertEquals(20, Board.initial().legalMoves().size, "startpos move count")
    }

    @Test
    fun castlingGeneratedWhenPathClearAndSafe() {
        val moves = uciSet(Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").legalMoves())
        assertTrue("e1g1" in moves, "O-O available")
        assertTrue("e1c1" in moves, "O-O-O available")
    }

    @Test
    fun castlingBlockedThroughAttackedTransitSquare() {
        // Black rook on f3 covers f1: O-O is out, O-O-O is fine.
        val moves = uciSet(Board.fromFen("r3k2r/8/8/8/8/5r2/8/R3K2R w KQkq - 0 1").legalMoves())
        assertTrue("e1g1" !in moves, "O-O through attacked f1 is illegal")
        assertTrue("e1c1" in moves, "O-O-O unaffected")
    }

    @Test
    fun castlingForbiddenWhileInCheck() {
        val moves = uciSet(Board.fromFen("r3k2r/8/8/8/8/4r3/8/R3K2R w KQkq - 0 1").legalMoves())
        assertTrue("e1g1" !in moves, "no O-O out of check")
        assertTrue("e1c1" !in moves, "no O-O-O out of check")
    }

    @Test
    fun enPassantDiscoveredCheckIsIllegal() {
        // CPW position 3: after e2e4 the f4 pawn may not capture en passant —
        // removing both pawns exposes the h4 king to the b4 rook.
        val board = Board.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - -")
        assertEquals(true, board.tryMoveUci("e2e4"), "e2e4 is legal")
        assertEquals(NO_MOVE, board.uciToMove("f4e3"), "EP capture into discovered check")
    }

    @Test
    fun pinnedPieceCannotMove() {
        // Knight e2 is pinned by the e3 rook against the e1 king.
        val board = Board.fromFen("4k3/8/8/8/8/4r3/4N3/4K3 w - - 0 1")
        assertEquals(0, board.legalMovesFrom(parseSquare("e2")).size, "pinned knight has no moves")
    }

    @Test
    fun promotionGeneratesAllFourPiecesForPushAndCapture() {
        val board = Board.fromFen("3r3k/4P3/8/8/8/8/8/4K3 w - - 0 1")
        val fromE7 = board.legalMovesFrom(parseSquare("e7"))
        assertEquals(8, fromE7.size, "4 push + 4 capture promotions")
        for (promo in intArrayOf(KNIGHT, BISHOP, ROOK, QUEEN)) {
            assertEquals(2, fromE7.count { movePromo(it) == promo }, "promotion pair for type $promo")
        }
    }

    @Test
    fun inCheckGeneratesOnlyEvasions() {
        // White king e1 checked by the e2 rook: Kxe2, Kd1, Kf1, Ng1xe2.
        val board = Board.fromFen("4k3/8/8/8/8/8/4r3/4K1N1 w - - 0 1")
        val moves = uciSet(board.legalMoves())
        assertEquals(setOf("e1e2", "e1d1", "e1f1", "g1e2"), moves, "check evasions only")
    }
}
