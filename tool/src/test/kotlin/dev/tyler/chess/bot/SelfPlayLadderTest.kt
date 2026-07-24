package dev.tyler.chess.bot

import dev.tyler.chess.engine.Board
import dev.tyler.chess.engine.GameStatus
import dev.tyler.chess.engine.WHITE
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The charter's difficulty gate: L3 beats L1 in >= 80% of 100 fast games and
 * L2 lands strictly between, or the ladder is broken. "Fast" reduces only
 * L3's budget (L1/L2 already are their real depth-2/depth-4 selves).
 * Everything is seeded, so results are exactly reproducible — this test
 * cannot flake; it can only be legitimately broken by bot changes.
 */
class SelfPlayLadderTest {
    private val fastStrong = SearchLimits(maxDepth = 16, maxNodes = 15_000)

    private fun limitsFor(level: Int): SearchLimits =
        if (level == Bot.LEVEL_STRONG) fastStrong else Bot.limitsFor(level)

    /** Plays [games] games, colors alternating; returns levelA's score (win 1, draw 0.5). */
    private fun playPair(levelA: Int, levelB: Int, games: Int): Double {
        var total = 0.0
        for (game in 0 until games) {
            val aPlaysWhite = game % 2 == 0
            val board = Board.initial()
            var ply = 0
            var aScore: Double
            while (true) {
                val status = board.status()
                if (status != GameStatus.ONGOING) {
                    aScore = if (status == GameStatus.CHECKMATE) {
                        val loserIsWhite = board.sideToMove == WHITE
                        if (loserIsWhite == aPlaysWhite) 0.0 else 1.0
                    } else {
                        0.5
                    }
                    break
                }
                if (ply >= 300) {
                    aScore = 0.5
                    break
                }
                val whiteToMove = board.sideToMove == WHITE
                val level = if (whiteToMove == aPlaysWhite) levelA else levelB
                val m = Bot.chooseMove(board, level, seed = game * 1_000L + ply, limits = limitsFor(level))
                board.makeMove(m)
                ply++
            }
            total += aScore
        }
        return total
    }

    @Test
    fun strongDominatesGentle() {
        val score = playPair(Bot.LEVEL_STRONG, Bot.LEVEL_GENTLE, 100)
        println("ladder L3 vs L1: $score/100")
        assertTrue(score >= 80.0, "L3 vs L1 scored $score/100; the charter gate is >= 80")
    }

    @Test
    fun fairBeatsGentle() {
        val score = playPair(Bot.LEVEL_FAIR, Bot.LEVEL_GENTLE, 100)
        println("ladder L2 vs L1: $score/100")
        assertTrue(score > 50.0, "L2 vs L1 scored $score/100; must be > 50")
    }

    @Test
    fun strongBeatsFair() {
        val score = playPair(Bot.LEVEL_STRONG, Bot.LEVEL_FAIR, 100)
        println("ladder L3 vs L2: $score/100")
        assertTrue(score > 50.0, "L3 vs L2 scored $score/100; must be > 50")
    }
}
