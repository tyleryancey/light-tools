package dev.tyler.chess.bot

import dev.tyler.chess.engine.BLACK
import dev.tyler.chess.engine.Board
import dev.tyler.chess.engine.EMPTY
import dev.tyler.chess.engine.KING
import dev.tyler.chess.engine.ROOK
import dev.tyler.chess.engine.WHITE
import dev.tyler.chess.engine.colorOf
import dev.tyler.chess.engine.typeOf

/**
 * Static evaluation: material + piece-square tables (the charter's floor;
 * midgame/endgame interpolation is deliberately left out for now), plus a
 * mop-up term so a winning side actually corners a bare king instead of
 * shuffling to the fifty-move rule. Scores are centipawns from the
 * side-to-move's perspective (negamax convention).
 */
internal object Eval {
    const val MATE = 30_000
    const val MATE_BOUND = 29_000
    const val INF = 31_000

    val MATERIAL = intArrayOf(0, 100, 320, 330, 500, 900, 0)

    // Piece-square tables (classic simplified-eval values), written as seen
    // on a printed board: first row = rank 8, files a..h. A white piece on
    // square sq reads PST[type][sq xor 56] (rank flip); a black piece reads
    // PST[type][sq] directly.
    private val PAWN_PST = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0
    )
    private val KNIGHT_PST = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50
    )
    private val BISHOP_PST = intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20
    )
    private val ROOK_PST = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0
    )
    private val QUEEN_PST = intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20
    )
    private val KING_PST = intArrayOf(
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20
    )
    private val PST = arrayOf(
        IntArray(64), PAWN_PST, KNIGHT_PST, BISHOP_PST, ROOK_PST, QUEEN_PST, KING_PST
    )

    fun evaluate(board: Board): Int {
        var score = 0
        var whiteMaterial = 0
        var blackMaterial = 0
        for (sq in 0..63) {
            val piece = board.squares[sq]
            if (piece == EMPTY) continue
            val type = typeOf(piece)
            if (colorOf(piece) == WHITE) {
                score += MATERIAL[type] + PST[type][sq xor 56]
                if (type != KING) whiteMaterial += MATERIAL[type]
            } else {
                score -= MATERIAL[type] + PST[type][sq]
                if (type != KING) blackMaterial += MATERIAL[type]
            }
        }
        if (blackMaterial == 0 && whiteMaterial >= MATERIAL[ROOK]) {
            score += mopUp(board.kingSq[WHITE], board.kingSq[BLACK])
        } else if (whiteMaterial == 0 && blackMaterial >= MATERIAL[ROOK]) {
            score -= mopUp(board.kingSq[BLACK], board.kingSq[WHITE])
        }
        return if (board.sideToMove == WHITE) score else -score
    }

    // Reward pushing the bare king toward a corner and bringing our king up.
    private fun mopUp(ourKing: Int, theirKing: Int): Int {
        val tf = theirKing and 7
        val tr = theirKing shr 3
        val fileDist = if (tf <= 3) 3 - tf else tf - 4
        val rankDist = if (tr <= 3) 3 - tr else tr - 4
        val kingGap = kotlin.math.abs((ourKing and 7) - tf) + kotlin.math.abs((ourKing shr 3) - tr)
        return 10 * (fileDist + rankDist) + 4 * (14 - kingGap)
    }
}
