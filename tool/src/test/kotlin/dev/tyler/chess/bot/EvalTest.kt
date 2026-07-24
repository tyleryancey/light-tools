package dev.tyler.chess.bot

import dev.tyler.chess.engine.Board
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvalTest {
    @Test
    fun startposIsBalanced() {
        assertEquals(0, Eval.evaluate(Board.initial()), "symmetric starting position")
    }

    @Test
    fun scoreFlipsWithSideToMove() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R"
        val white = Eval.evaluate(Board.fromFen("$fen w KQkq - 0 1"))
        val black = Eval.evaluate(Board.fromFen("$fen b KQkq - 0 1"))
        assertEquals(white, -black, "same position from the opposite perspective")
    }

    @Test
    fun extraRookDominates() {
        assertTrue(Eval.evaluate(Board.fromFen("4k3/8/8/8/8/8/8/R3K3 w - - 0 1")) > 400, "rook up")
        assertTrue(Eval.evaluate(Board.fromFen("4k3/8/8/8/8/8/8/R3K3 b - - 0 1")) < -400, "rook down")
    }

    @Test
    fun centralKnightBeatsRimKnight() {
        val central = Eval.evaluate(Board.fromFen("4k3/8/8/3N4/8/8/8/4K3 w - - 0 1"))
        val rim = Eval.evaluate(Board.fromFen("4k3/8/8/N7/8/8/8/4K3 w - - 0 1"))
        assertTrue(central > rim, "piece-square table rewards centralization ($central vs $rim)")
    }
}
