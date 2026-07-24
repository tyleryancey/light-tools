package dev.tyler.chess.bot

import dev.tyler.chess.engine.Board
import dev.tyler.chess.engine.moveToUci
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * The charter's "quiescence is not optional" rule, tested at the horizon:
 * white's queen can grab the d8 rook, but the a8 rook recaptures one ply
 * beyond a depth-1 search. Only quiescence makes depth 1 see the loss.
 */
class QuiescenceTest {
    private val defendedRookTrap = "r2r3k/8/8/8/3Q4/8/8/7K w - - 0 1"

    @Test
    fun depthOneSeesTheRecaptureBehindTheHorizon() {
        val board = Board.fromFen(defendedRookTrap)
        val m = Bot.chooseMove(
            board, Bot.LEVEL_STRONG, seed = 1,
            limits = SearchLimits(maxDepth = 1, maxNodes = 100_000)
        )
        assertNotEquals("d4d8", moveToUci(m), "Qxd8?? Rxd8 loses the queen for a rook")
    }

    @Test
    fun strongLevelAvoidsTheTrap() {
        val m = Bot.chooseMove(Board.fromFen(defendedRookTrap), Bot.LEVEL_STRONG, seed = 1)
        assertNotEquals("d4d8", moveToUci(m), "L3 avoids the exchange-losing capture")
    }
}
