package dev.tyler.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FenTest {
    private fun assertRoundTrip(fen: String) {
        assertEquals(fen, Board.fromFen(fen).fen(), "round trip")
    }

    @Test
    fun roundTripStartpos() {
        assertRoundTrip("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        assertEquals(Board.initial().fen(), Board.fromFen(Board.initial().fen()).fen(), "initial")
    }

    @Test
    fun roundTripKiwipete() {
        // The canonical Kiwipete FEN has only 4 fields; render always emits 6.
        val board = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -")
        assertEquals(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            board.fen(),
            "kiwipete with default clocks"
        )
        assertRoundTrip(board.fen())
    }

    @Test
    fun roundTripMidgamePositions() {
        // Midgame, black to move.
        assertRoundTrip("rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2")
        // Partial castling rights.
        assertRoundTrip("r3k2r/8/8/8/8/8/8/R3K2R w Kq - 4 20")
        // Real EP square: the black d4 pawn can capture on e3, so it survives.
        assertRoundTrip("rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 3")
    }

    @Test
    fun parseToleratesMissingClockFields() {
        val board = Board.fromFen("4k3/8/8/8/8/8/8/4K3 w - -")
        assertEquals(0, board.halfmoveClock, "default halfmove clock")
        assertEquals(1, board.fullmoveNumber, "default fullmove number")
    }

    @Test
    fun phantomEpSquareIsNormalizedToDash() {
        // After 1.e4 no black pawn can capture on e3: the EP square is
        // dropped so identical positions hash identically (threefold rule).
        val board = Board.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        assertEquals(-1, board.epSquare, "phantom EP dropped")
        assertTrue(board.fen().contains(" - 0 1"), "renders as -")
    }

    @Test
    fun epSquareWithoutVictimPawnIsDropped() {
        // A capturer exists on d4 but there is no white pawn on e4 behind the
        // claimed e3 square: without the geometry check, move generation
        // would emit an EP capture of an empty square and crash makeMove.
        val board = Board.fromFen("rnbqkbnr/ppp1pppp/8/8/3p4/8/PPPPPPPP/RNBQKBNR b KQkq e3 0 3")
        assertEquals(-1, board.epSquare, "EP without a victim pawn dropped")
        assertEquals(0, board.legalMoves().count { moveSpecial(it) == SPECIAL_EN_PASSANT }, "no EP moves generated")
    }

    @Test
    fun parseRejectsPawnsOnBackRanks() {
        assertFailsWith<IllegalArgumentException>("white pawn on rank 8") {
            Board.fromFen("P3k3/8/8/8/8/8/8/4K3 w - - 0 1")
        }
        assertFailsWith<IllegalArgumentException>("black pawn on rank 1") {
            Board.fromFen("4k3/8/8/8/8/8/8/p3K3 w - - 0 1")
        }
    }

    @Test
    fun parseRejectsGarbage() {
        assertFailsWith<IllegalArgumentException>("not a fen") { Board.fromFen("not a fen") }
        assertFailsWith<IllegalArgumentException>("short rank") {
            Board.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN w KQkq - 0 1")
        }
        assertFailsWith<IllegalArgumentException>("bad piece char") {
            Board.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNX w KQkq - 0 1")
        }
        assertFailsWith<IllegalArgumentException>("bad side") {
            Board.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1")
        }
        assertFailsWith<IllegalArgumentException>("no kings") { Board.fromFen("8/8/8/8/8/8/8/8 w - - 0 1") }
        assertFailsWith<IllegalArgumentException>("negative clock") {
            Board.fromFen("4k3/8/8/8/8/8/8/4K3 w - - -1 1")
        }
    }
}
