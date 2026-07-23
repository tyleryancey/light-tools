package dev.tyler.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameStatusTest {
    @Test
    fun foolsMateIsCheckmate() {
        val board = Board.replay(listOf("f2f3", "e7e5", "g2g4", "d8h4"))
        assertTrue(board.isCheck(), "king is in check")
        assertEquals(GameStatus.CHECKMATE, board.status(), "fool's mate")
    }

    @Test
    fun stalemateDetected() {
        val board = Board.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertEquals(GameStatus.STALEMATE, board.status(), "no moves, no check")
    }

    @Test
    fun fiftyMoveRuleDraw() {
        val board = Board.fromFen("4k3/8/8/8/8/8/8/4K2R w - - 99 80")
        assertTrue(board.tryMoveUci("h1h2"), "quiet rook move")
        assertEquals(100, board.halfmoveClock, "clock reaches 100")
        assertEquals(GameStatus.DRAW_FIFTY, board.status(), "fifty-move draw")
    }

    @Test
    fun checkmatePrecedesFiftyMoveDraw() {
        // The mating move is also the 100th quiet half-move: mate wins.
        val board = Board.fromFen("7k/8/6QK/8/8/8/8/8 w - - 99 80")
        assertTrue(board.tryMoveUci("g6g7"), "quiet mating move")
        assertEquals(100, board.halfmoveClock, "clock reaches 100")
        assertEquals(GameStatus.CHECKMATE, board.status(), "mate outranks the fifty-move rule")
    }

    @Test
    fun threefoldRepetitionViaKnightShuffle() {
        val board = Board.initial()
        val shuffle = listOf("g1f3", "g8f6", "f3g1", "f6g8")
        for (uci in shuffle) assertTrue(board.tryMoveUci(uci), "$uci legal")
        assertEquals(2, board.repetitionCount(), "second occurrence after one shuffle")
        assertEquals(GameStatus.ONGOING, board.status(), "not yet a draw")
        for (uci in shuffle) assertTrue(board.tryMoveUci(uci), "$uci legal")
        assertEquals(3, board.repetitionCount(), "third occurrence after two shuffles")
        assertEquals(GameStatus.DRAW_THREEFOLD, board.status(), "threefold draw")
    }

    @Test
    fun insufficientMaterialCases() {
        val draws = listOf(
            "4k3/8/8/8/8/8/8/4K3 w - - 0 1", // K vs K
            "4k3/8/8/8/8/8/8/2B1K3 w - - 0 1", // KB vs K
            "4k3/8/8/8/8/8/8/1N2K3 w - - 0 1", // KN vs K
            "4k3/8/8/8/5b2/8/8/2B1K3 w - - 0 1" // KB vs KB, same square color
        )
        for (fen in draws) {
            assertEquals(GameStatus.DRAW_MATERIAL, Board.fromFen(fen).status(), "dead draw: $fen")
        }
        val ongoing = listOf(
            "4k3/8/8/8/8/8/8/1NN1K3 w - - 0 1", // KNN vs K
            "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1", // KP vs K
            "4k3/8/8/5b2/8/8/8/2B1K3 w - - 0 1" // KB vs KB, opposite colors
        )
        for (fen in ongoing) {
            assertEquals(GameStatus.ONGOING, Board.fromFen(fen).status(), "not a dead draw: $fen")
        }
    }

    // The charter's scripted-full-game gate: Morphy's Opera Game (1858),
    // 17 moves with O-O-O, checks, sacrifices, and mate.
    @Test
    fun operaGameReplaysToCheckmate() {
        val moves = listOf(
            "e2e4", "e7e5", "g1f3", "d7d6", "d2d4", "c8g4", "d4e5", "g4f3",
            "d1f3", "d6e5", "f1c4", "g8f6", "f3b3", "d8e7", "b1c3", "c7c6",
            "c1g5", "b7b5", "c3b5", "c6b5", "c4b5", "b8d7", "e1c1", "a8d8",
            "d1d7", "d8d7", "h1d1", "e7e6", "b5d7", "f6d7", "b3b8", "d7b8",
            "d1d8"
        )
        val board = Board.initial()
        for (uci in moves) {
            assertEquals(GameStatus.ONGOING, board.status(), "ongoing before $uci")
            assertTrue(board.tryMoveUci(uci), "$uci is legal")
        }
        assertEquals(GameStatus.CHECKMATE, board.status(), "Rd8# ends the game")
        assertEquals(Board.replay(moves).fen(), board.fen(), "replay agrees with incremental play")
    }
}
