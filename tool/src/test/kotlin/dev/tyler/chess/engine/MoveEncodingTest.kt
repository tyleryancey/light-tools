package dev.tyler.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MoveEncodingTest {
    @Test
    fun movePackingRoundTripsAllFields() {
        for (from in 0..63) {
            for (to in 0..63) {
                for (promo in intArrayOf(0, KNIGHT, BISHOP, ROOK, QUEEN)) {
                    for (special in 0..3) {
                        val m = move(from, to, promo, special)
                        assertEquals(from, moveFrom(m), "from")
                        assertEquals(to, moveTo(m), "to")
                        assertEquals(promo, movePromo(m), "promo")
                        assertEquals(special, moveSpecial(m), "special")
                    }
                }
            }
        }
    }

    @Test
    fun squareNamesRoundTrip() {
        for (sq in 0..63) {
            assertEquals(sq, parseSquare(squareName(sq)), "square $sq")
        }
        assertEquals("a1", squareName(0), "a1")
        assertEquals("h8", squareName(63), "h8")
        assertEquals(28, parseSquare("e4"), "e4")
        assertEquals(-1, parseSquare("i9"), "off-board file/rank")
        assertEquals(-1, parseSquare("e"), "too short")
    }

    @Test
    fun uciFormattingIncludesAllFourPromotionPieces() {
        val from = parseSquare("e7")
        val to = parseSquare("e8")
        assertEquals("e2e4", moveToUci(move(parseSquare("e2"), parseSquare("e4"))), "plain move")
        assertEquals("e7e8q", moveToUci(move(from, to, QUEEN)), "queen promotion")
        assertEquals("e7e8r", moveToUci(move(from, to, ROOK)), "rook promotion")
        assertEquals("e7e8b", moveToUci(move(from, to, BISHOP)), "bishop promotion")
        assertEquals("e7e8n", moveToUci(move(from, to, KNIGHT)), "knight promotion")
    }

    // Charter gate: the UCI promotion suffix must resolve to a legal move for
    // all four pieces — push and capture-promotion, both colors.
    @Test
    fun uciPromotionResolvesForAllFourPiecesWhite() {
        val board = Board.fromFen("3r3k/4P3/8/8/8/8/8/4K3 w - - 0 1")
        for (uci in listOf("e7e8q", "e7e8r", "e7e8b", "e7e8n", "e7d8q", "e7d8r", "e7d8b", "e7d8n")) {
            val m = board.uciToMove(uci)
            assertNotEquals(NO_MOVE, m, "$uci should be legal")
            assertEquals(uci, moveToUci(m), "$uci round-trips")
            assertTrue(movePromo(m) != 0, "$uci is a promotion")
        }
        assertEquals(NO_MOVE, board.uciToMove("e7e8"), "promotion without suffix is not a move")
    }

    @Test
    fun uciPromotionResolvesForAllFourPiecesBlack() {
        val board = Board.fromFen("4k3/8/8/8/8/8/4p3/3R2K1 b - - 0 1")
        for (uci in listOf("e2e1q", "e2e1r", "e2e1b", "e2e1n", "e2d1q", "e2d1r", "e2d1b", "e2d1n")) {
            val m = board.uciToMove(uci)
            assertNotEquals(NO_MOVE, m, "$uci should be legal")
            assertEquals(uci, moveToUci(m), "$uci round-trips")
        }
    }
}
