package dev.tyler.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The hash is tested directly, not just through draw detection: the key must
 * differ by side-to-move, castling rights, and EP square alone, or threefold
 * detection and M2's transposition table are silently wrong.
 */
class ZobristTest {
    @Test
    fun keyIsDeterministicAcrossBoards() {
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        assertEquals(Board.fromFen(fen).zobristKey, Board.fromFen(fen).zobristKey, "same FEN, same key")
        // Pinned literal: the key must be identical across runs and devices
        // (M2 bot determinism and its transposition table depend on it). If
        // this fails, the seed or table fill order changed — that is a
        // breaking change, not a test to update casually.
        assertEquals(-7013612547671794577L, Board.initial().zobristKey, "startpos key is pinned")
    }

    @Test
    fun keyDiffersBySideToMoveOnly() {
        val white = Board.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
        val black = Board.fromFen("4k3/8/8/8/8/8/8/4K3 b - - 0 1")
        assertNotEquals(white.zobristKey, black.zobristKey, "side to move must be in the key")
    }

    @Test
    fun keyDiffersByCastlingRightsOnly() {
        val all = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").zobristKey
        val partial = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQk - 0 1").zobristKey
        val none = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w - - 0 1").zobristKey
        assertNotEquals(all, partial, "KQkq vs KQk")
        assertNotEquals(partial, none, "KQk vs -")
        assertNotEquals(all, none, "KQkq vs -")
    }

    @Test
    fun keyDiffersByEpSquareOnly() {
        // Same squares and side; the only difference is a usable EP square.
        val withEp = Board.fromFen("rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 3")
        val without = Board.fromFen("rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3")
        assertNotEquals(withEp.zobristKey, without.zobristKey, "EP square must be in the key")
    }

    // A line touching double pushes, captures, a discovered check, a
    // capture-promotion to queen, and kingside castling.
    private val scriptedLine = listOf(
        "e2e4", "d7d5", "e4d5", "g8f6", "f1b5", "c7c6", "d5c6", "e7e6",
        "c6b7", "c8d7", "b7a8q", "f8b4", "g1f3", "e8g8"
    )

    @Test
    fun incrementalKeyMatchesRecomputeThroughScriptedLine() {
        val board = Board.initial()
        for (uci in scriptedLine) {
            assertEquals(true, board.tryMoveUci(uci), "$uci is legal")
            assertEquals(Zobrist.compute(board), board.zobristKey, "incremental key after $uci")
        }
        // And through an en-passant capture.
        val epBoard = Board.initial()
        for (uci in listOf("e2e4", "a7a6", "e4e5", "d7d5", "e5d6")) {
            assertEquals(true, epBoard.tryMoveUci(uci), "$uci is legal")
            assertEquals(Zobrist.compute(epBoard), epBoard.zobristKey, "incremental key after $uci")
        }
    }

    @Test
    fun makeUnmakeRestoresKeyAndFenFromKiwipete() {
        val board = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -")
        walk(board, 3)
    }

    private fun walk(board: Board, depth: Int) {
        if (depth == 0) return
        val fenBefore = board.fen()
        val keyBefore = board.zobristKey
        for (m in board.legalMoves()) {
            board.makeMove(m)
            assertEquals(Zobrist.compute(board), board.zobristKey, "incremental key after ${moveToUci(m)}")
            walk(board, depth - 1)
            board.unmakeMove()
            assertEquals(keyBefore, board.zobristKey, "key restored after ${moveToUci(m)}")
            assertEquals(fenBefore, board.fen(), "fen restored after ${moveToUci(m)}")
        }
    }
}
