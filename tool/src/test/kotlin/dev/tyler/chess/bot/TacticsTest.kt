package dev.tyler.chess.bot

import dev.tyler.chess.engine.Board
import dev.tyler.chess.engine.GameStatus
import dev.tyler.chess.engine.KNIGHT
import dev.tyler.chess.engine.NO_MOVE
import dev.tyler.chess.engine.movePromo
import dev.tyler.chess.engine.moveToUci
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The charter's tactics gate. Positions are curated EPD-style FEN constants
 * kept in-repo here as string data — the SDK build plugin bans classloader /
 * resource-loading APIs in test sources, so a loose .epd file cannot be read.
 *
 * The asserts are self-verifying through the M1 engine: a mate claim is
 * checked by actually making the move and asking the board, so a mis-curated
 * position fails loudly instead of silently testing nothing.
 */
class TacticsTest {
    private val tacticsLimits = SearchLimits(maxDepth = 16, maxNodes = 200_000)

    private fun botMove(board: Board): Int =
        Bot.chooseMove(board, Bot.LEVEL_STRONG, seed = 3, limits = tacticsLimits)

    // --- mate in 1 ---------------------------------------------------------

    private val mateInOne = listOf(
        "6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1", // back rank: Ra8#
        "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4", // scholar's: Qxf7#
        "6rk/6pp/7N/8/8/8/8/6K1 w - - 0 1", // smothered: Nf7#
        "3k4/8/3K4/8/8/8/8/6Q1 w - - 0 1", // Qg8#
        "7k/8/5K2/8/8/8/8/6Q1 w - - 0 1", // Qg7#
        "4k3/8/8/8/8/8/r4PPP/6K1 b - - 0 1" // black to move: Ra1#
    )

    @Test
    fun mateInOneSolvedAtStrongLevel() {
        for (fen in mateInOne) {
            val board = Board.fromFen(fen)
            val m = botMove(board)
            assertNotEquals(NO_MOVE, m, "a move exists in $fen")
            board.makeMove(m)
            assertEquals(GameStatus.CHECKMATE, board.status(), "${moveToUci(m)} mates in $fen")
        }
    }

    // Charter sharp edge: the bot must be able to underpromote. Here g8=N is
    // the position's only mating move (g8=Q gives no check at all).
    @Test
    fun knightUnderpromotionMateIsFound() {
        val board = Board.fromFen("8/6P1/7k/8/7K/8/2B5/6R1 w - - 0 1")
        val m = botMove(board)
        assertEquals(KNIGHT, movePromo(m), "only the knight underpromotion mates, got ${moveToUci(m)}")
        board.makeMove(m)
        assertEquals(GameStatus.CHECKMATE, board.status(), "underpromotion mate delivered")
    }

    // --- mate in 2 ---------------------------------------------------------

    private val mateInTwo = listOf(
        "k7/8/8/8/8/8/6R1/K6R w - - 0 1", // rook ladder: Rg7 then Rh8#
        "7k/8/5K2/8/8/8/8/7Q w - - 0 1" // king step then Qa8# (or equivalent)
    )

    @Test
    fun mateInTwoForcedAtStrongLevel() {
        for (fen in mateInTwo) assertBotForcesMateWithinTwo(fen)
    }

    /**
     * Verifies the bot's chosen move truly forces mate: every legal defense
     * must leave a mating reply (engine-verified, which also validates the
     * curation), and the bot must actually deliver that mate. An empty
     * defense set must itself be checkmate — never stalemate.
     */
    private fun assertBotForcesMateWithinTwo(fen: String) {
        val board = Board.fromFen(fen)
        val first = botMove(board)
        assertNotEquals(NO_MOVE, first, "a move exists in $fen")
        board.makeMove(first)
        val defenses = board.legalMoves()
        if (defenses.isEmpty()) {
            assertEquals(GameStatus.CHECKMATE, board.status(), "${moveToUci(first)} with no replies must be mate in $fen")
            return
        }
        for (defense in defenses) {
            board.makeMove(defense)
            val mateExists = board.legalMoves().any { reply ->
                board.makeMove(reply)
                val mate = board.status() == GameStatus.CHECKMATE
                board.unmakeMove()
                mate
            }
            assertTrue(
                mateExists,
                "defense ${moveToUci(defense)} escapes mate after ${moveToUci(first)} in $fen"
            )
            val finisher = botMove(board)
            board.makeMove(finisher)
            assertEquals(
                GameStatus.CHECKMATE, board.status(),
                "bot must finish with mate after ${moveToUci(defense)} in $fen"
            )
            board.unmakeMove()
            board.unmakeMove()
        }
    }

    // --- don't hang --------------------------------------------------------

    private val freePieceCaptures = listOf(
        // Undefended black queen on d5; Nxd5 wins it outright.
        "rnb1kbnr/ppp1pppp/8/3q4/8/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 3" to "c3d5",
        // Undefended white knight on e5; Nxe5 recaptures the piece.
        "r1bqkbnr/pppp1ppp/2n5/4N3/8/8/PPPPPPPP/RNBQKB1R b KQkq - 0 3" to "c6e5",
        // Undefended rook attacking the queen; Qxd5 wins it.
        "4k3/8/8/3r4/8/8/3Q4/4K3 w - - 0 1" to "d2d5"
    )

    @Test
    fun obviousCapturesAreFound() {
        for ((fen, expected) in freePieceCaptures) {
            val m = botMove(Board.fromFen(fen))
            assertEquals(expected, moveToUci(m), "free material in $fen")
        }
    }

    // Fifty-move boundary: white (winning) at halfmove clock 99 sees a royal
    // fork Nf7+ that "wins the rook" — but the quiet check makes the clock
    // hit 100 and the game is drawn on the spot. Only a capture (gxh3 or
    // Nxh3) resets the clock and keeps the win.
    @Test
    fun winningSideAvoidsDrawingByFiftyMoveRule() {
        val board = Board.fromFen("3r3k/R7/8/6N1/8/7p/6P1/6K1 w - - 99 80")
        val m = botMove(board)
        assertNotEquals("g5f7", moveToUci(m), "the fork check draws by the fifty-move rule")
        assertTrue(
            moveToUci(m) == "g2h3" || moveToUci(m) == "g5h3",
            "a clock-resetting capture keeps the win, got ${moveToUci(m)}"
        )
    }
}
