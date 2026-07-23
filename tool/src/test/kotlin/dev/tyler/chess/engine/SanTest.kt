package dev.tyler.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SanTest {
    private fun san(fen: String, uci: String): String {
        val board = Board.fromFen(fen)
        val m = board.uciToMove(uci)
        assertNotEquals(NO_MOVE, m, "$uci legal in $fen")
        return San.render(board, m)
    }

    @Test
    fun pawnMovesCapturesAndPromotions() {
        assertEquals("e4", san(Fen.START, "e2e4"), "pawn push")
        assertEquals(
            "exd5",
            san("rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2", "e4d5"),
            "pawn capture"
        )
        assertEquals(
            "dxe3",
            san("rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 3", "d4e3"),
            "en passant renders as a plain pawn capture"
        )
        assertEquals("e8=Q+", san("3r3k/4P3/8/8/8/8/8/4K3 w - - 0 1", "e7e8q"), "push promotion, with check")
        assertEquals("exd8=N", san("3r3k/4P3/8/8/8/8/8/4K3 w - - 0 1", "e7d8n"), "capture underpromotion")
    }

    @Test
    fun disambiguationByFileRankAndBoth() {
        // Three queens that can all reach e1: h4 needs file AND rank, e4 needs
        // file only, h1 needs rank only.
        val fen = "1k6/8/8/8/4Q2Q/8/K7/7Q w - - 0 1"
        assertEquals("Qee1", san(fen, "e4e1"), "file disambiguation")
        assertEquals("Q1e1", san(fen, "h1e1"), "rank disambiguation")
        assertEquals("Qh4e1", san(fen, "h4e1"), "file and rank disambiguation")
    }

    @Test
    fun pinnedTwinNeedsNoDisambiguation() {
        // Both knights could reach f3, but the d2 knight is pinned by the d8
        // rook — disambiguation considers legal moves only.
        val board = Board.fromFen("3r3k/8/8/8/8/8/3N4/3K2N1 w - - 0 1")
        assertEquals(0, board.legalMovesFrom(parseSquare("d2")).size, "d2 knight is pinned")
        assertEquals("Nf3", San.render(board, board.uciToMove("g1f3")), "no disambiguation needed")
    }

    @Test
    fun castlingRendersAsOO() {
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        assertEquals("O-O", san(fen, "e1g1"), "kingside")
        assertEquals("O-O-O", san(fen, "e1c1"), "queenside")
    }

    // Double gate: the Opera Game rendered move-by-move against the canonical
    // score — covers piece letters, captures, Nbd7 disambiguation, O-O-O,
    // check and mate suffixes.
    @Test
    fun operaGameSanMatchesCanonicalScore() {
        val uci = listOf(
            "e2e4", "e7e5", "g1f3", "d7d6", "d2d4", "c8g4", "d4e5", "g4f3",
            "d1f3", "d6e5", "f1c4", "g8f6", "f3b3", "d8e7", "b1c3", "c7c6",
            "c1g5", "b7b5", "c3b5", "c6b5", "c4b5", "b8d7", "e1c1", "a8d8",
            "d1d7", "d8d7", "h1d1", "e7e6", "b5d7", "f6d7", "b3b8", "d7b8",
            "d1d8"
        )
        val expectedSan = listOf(
            "e4", "e5", "Nf3", "d6", "d4", "Bg4", "dxe5", "Bxf3",
            "Qxf3", "dxe5", "Bc4", "Nf6", "Qb3", "Qe7", "Nc3", "c6",
            "Bg5", "b5", "Nxb5", "cxb5", "Bxb5+", "Nbd7", "O-O-O", "Rd8",
            "Rxd7", "Rxd7", "Rd1", "Qe6", "Bxd7+", "Nxd7", "Qb8+", "Nxb8",
            "Rd8#"
        )
        val board = Board.initial()
        for (i in uci.indices) {
            val m = board.uciToMove(uci[i])
            assertNotEquals(NO_MOVE, m, "${uci[i]} legal at ply $i")
            assertEquals(expectedSan[i], San.render(board, m), "SAN at ply $i")
            board.makeMove(m)
        }
    }
}
