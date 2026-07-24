package dev.tyler.chess.bot

import dev.tyler.chess.engine.Board
import dev.tyler.chess.engine.EMPTY
import dev.tyler.chess.engine.MoveGen
import dev.tyler.chess.engine.NO_MOVE
import dev.tyler.chess.engine.PAWN
import dev.tyler.chess.engine.SPECIAL_EN_PASSANT
import dev.tyler.chess.engine.moveFrom
import dev.tyler.chess.engine.movePromo
import dev.tyler.chess.engine.moveSpecial
import dev.tyler.chess.engine.moveTo
import dev.tyler.chess.engine.typeOf

private const val MAX_PLY = 100
internal const val TT_BITS = 18
internal const val TT_SIZE = 1 shl TT_BITS
private const val TT_MASK = (1L shl TT_BITS) - 1
private const val FLAG_NONE = 0
private const val FLAG_EXACT = 1
private const val FLAG_LOWER = 2
private const val FLAG_UPPER = 3

/**
 * Thrown to unwind an over-budget search. Stackless singleton: every frame
 * restores the board in a finally block, so the position survives an abort
 * intact.
 */
internal object SearchAborted : Exception() {
    override fun fillInStackTrace(): Throwable = this
    private fun readResolve(): Any = SearchAborted
}

internal class RootResult(val bestMove: Int, val candidates: IntArray)

/**
 * Negamax alpha-beta with iterative deepening, quiescence on captures,
 * MVV-LVA + transposition-table + killer ordering, and a check extension.
 *
 * Difficulty noise happens at the root: alpha is held `window` below the
 * best score so every move within the window gets an exactly-known score
 * cheaply, and the caller picks among those candidates. Window 0 degenerates
 * to a normal alpha-beta root.
 *
 * The depth-1 iteration is exempt from every abort source (node budget,
 * deadline, cancellation), so a legal move is always available to return.
 */
internal class Search(
    private val board: Board,
    private val limits: SearchLimits,
    private val cancelled: () -> Boolean,
    private val ttKeys: LongArray,
    private val ttData: LongArray
) {
    private var nodes = 0L
    private var abortChecksEnabled = false
    private val killers = Array(MAX_PLY) { IntArray(2) }

    fun findMove(window: Int): RootResult {
        val order = board.legalMoves()
        if (order.isEmpty()) return RootResult(NO_MOVE, IntArray(0))
        if (order.size == 1) return RootResult(order[0], order.copyOf())

        val scores = IntArray(order.size)
        val exact = BooleanArray(order.size)
        var bestMove = order[0]
        var candidates = intArrayOf(order[0])

        for (depth in 1..limits.maxDepth) {
            abortChecksEnabled = depth > 1
            try {
                searchRoot(order, scores, exact, depth, window)
            } catch (aborted: SearchAborted) {
                break
            }
            // Snapshot this completed depth. The true best is always among the
            // exactly-scored moves: a fail-low return is an upper bound below
            // the then-best minus window, so it can never be the best move.
            var best = -Eval.INF
            var bestIdx = 0
            for (i in order.indices) {
                if (exact[i] && scores[i] > best) {
                    best = scores[i]
                    bestIdx = i
                }
            }
            bestMove = order[bestIdx]
            val within = IntArray(order.size)
            var n = 0
            for (i in order.indices) {
                if (exact[i] && scores[i] >= best - window) within[n++] = order[i]
            }
            candidates = within.copyOf(n)
            sortByScoreDescending(order, scores)
            if (best > Eval.MATE_BOUND) break // mate found; deeper search cannot improve it
        }
        return RootResult(bestMove, candidates)
    }

    private fun searchRoot(order: IntArray, scores: IntArray, exact: BooleanArray, depth: Int, window: Int) {
        var best = -Eval.INF
        var alpha = -Eval.INF
        for (i in order.indices) {
            val m = order[i]
            board.makeMove(m)
            val score = try {
                -negamax(depth - 1, 1, -Eval.INF, -alpha)
            } finally {
                board.unmakeMove()
            }
            exact[i] = score > alpha // fail-soft: at or below alpha is only an upper bound
            scores[i] = score
            if (score > best) best = score
            val floor = best - window
            if (floor > alpha) alpha = floor
        }
    }

    private fun negamax(depthIn: Int, ply: Int, alphaIn: Int, beta: Int): Int {
        nodes++
        if (abortChecksEnabled && (nodes and 1023L) == 0L) checkAbort()

        val inCheck = board.isCheck()
        // First repetition of anything in game or search history scores as a
        // draw; the 50-move return must not outrank an actual mate, so it
        // defers to movegen while in check.
        if (board.repetitionCount() >= 2) return 0
        if (board.halfmoveClock >= 100 && !inCheck) return 0

        var depth = depthIn
        if (inCheck) depth++ // check extension; depth never exceeds the root depth
        if (depth <= 0) return qsearch(ply, alphaIn, beta)
        if (ply >= MAX_PLY - 1) return Eval.evaluate(board)

        var alpha = alphaIn
        val key = board.zobristKey
        val slot = (key and TT_MASK).toInt()
        var ttMove = NO_MOVE
        if (ttKeys[slot] == key) {
            val data = ttData[slot]
            val flag = ((data ushr 40) and 3L).toInt()
            if (flag != FLAG_NONE) {
                ttMove = (data and 0x1FFFFL).toInt()
                if (((data ushr 33) and 0x7FL).toInt() >= depth) {
                    var ttScore = ((data ushr 17) and 0xFFFFL).toInt() - 32768
                    if (ttScore > Eval.MATE_BOUND) ttScore -= ply
                    else if (ttScore < -Eval.MATE_BOUND) ttScore += ply
                    when (flag) {
                        FLAG_EXACT -> return ttScore
                        FLAG_LOWER -> if (ttScore >= beta) return ttScore
                        else -> if (ttScore <= alpha) return ttScore
                    }
                }
            }
        }

        // Pseudo-legal with lazy legality: a move pruned by beta is never
        // legality-checked at all, and mate/stalemate falls out of "no legal
        // child survived".
        val moves = MoveGen.pseudoLegal(board)
        orderMoves(moves, ttMove, ply)

        var best = -Eval.INF
        var bestMove = NO_MOVE
        var anyLegal = false
        var flag = FLAG_UPPER
        val us = board.sideToMove
        for (m in moves) {
            board.makeMove(m)
            if (MoveGen.isSquareAttacked(board, board.kingSq[us], 1 - us)) {
                board.unmakeMove()
                continue
            }
            anyLegal = true
            val score = try {
                -negamax(depth - 1, ply + 1, -beta, -alpha)
            } finally {
                board.unmakeMove()
            }
            if (score > best) {
                best = score
                bestMove = m
            }
            if (best > alpha) {
                alpha = best
                flag = FLAG_EXACT
            }
            if (alpha >= beta) {
                flag = FLAG_LOWER
                if (!isTactical(m)) storeKiller(ply, m)
                break
            }
        }
        if (!anyLegal) return if (inCheck) -(Eval.MATE - ply) else 0
        // In check at the fifty-move boundary with evasions available: still
        // the draw — only checkmate outranks the rule. Not TT-stored: the
        // zobrist key does not include the clock.
        if (board.halfmoveClock >= 100) return 0

        var stored = best
        if (stored > Eval.MATE_BOUND) stored += ply
        else if (stored < -Eval.MATE_BOUND) stored -= ply
        ttKeys[slot] = key
        ttData[slot] = (bestMove.toLong() and 0x1FFFFL) or
            ((stored + 32768).toLong() shl 17) or
            (depth.toLong() shl 33) or
            (flag.toLong() shl 40)
        return best
    }

    private fun qsearch(ply: Int, alphaIn: Int, beta: Int): Int {
        nodes++
        if (abortChecksEnabled && (nodes and 1023L) == 0L) checkAbort()

        val inCheck = board.isCheck()
        if (!inCheck && board.halfmoveClock >= 100) return 0 // reachable via evasion chains
        var alpha = alphaIn
        var best: Int
        if (inCheck) {
            best = -Eval.INF // no standing pat out of check
        } else {
            best = Eval.evaluate(board)
            if (best >= beta) return best
            if (best > alpha) alpha = best
        }
        if (ply >= MAX_PLY - 1) return if (inCheck) Eval.evaluate(board) else best

        val moves = MoveGen.pseudoLegal(board)
        orderMoves(moves, NO_MOVE, ply)
        var anyLegal = false
        val us = board.sideToMove
        for (m in moves) {
            if (!inCheck && !isTactical(m)) continue
            board.makeMove(m)
            if (MoveGen.isSquareAttacked(board, board.kingSq[us], 1 - us)) {
                board.unmakeMove()
                continue
            }
            anyLegal = true
            val score = try {
                -qsearch(ply + 1, -beta, -alpha)
            } finally {
                board.unmakeMove()
            }
            if (score > best) best = score
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        // In check every move was tried, so no legal survivor means mate.
        if (inCheck && !anyLegal) return -(Eval.MATE - ply)
        if (inCheck && board.halfmoveClock >= 100) return 0 // fifty-move draw outranks evasions
        return best
    }

    /** Capture (incl. en passant) or promotion — searched in quiescence, never a killer. */
    private fun isTactical(m: Int): Boolean =
        movePromo(m) != 0 ||
            moveSpecial(m) == SPECIAL_EN_PASSANT ||
            board.pieceAt(moveTo(m)) != EMPTY

    private fun storeKiller(ply: Int, m: Int) {
        val slots = killers[ply]
        if (slots[0] != m) {
            slots[1] = slots[0]
            slots[0] = m
        }
    }

    private fun moveOrderScore(m: Int, ttMove: Int, ply: Int): Int {
        if (m == ttMove) return 1_000_000
        val victim = if (moveSpecial(m) == SPECIAL_EN_PASSANT) PAWN else typeOf(board.pieceAt(moveTo(m)))
        val promo = movePromo(m)
        if (victim != 0 || promo != 0) {
            return 100_000 + Eval.MATERIAL[victim] * 16 -
                typeOf(board.pieceAt(moveFrom(m))) + Eval.MATERIAL[promo]
        }
        val slots = killers[ply]
        return when (m) {
            slots[0] -> 90_000
            slots[1] -> 89_999
            else -> 0
        }
    }

    // Deterministic insertion sorts: stable, and the arrays are small.

    private fun orderMoves(moves: IntArray, ttMove: Int, ply: Int) {
        val scores = IntArray(moves.size)
        for (i in moves.indices) scores[i] = moveOrderScore(moves[i], ttMove, ply)
        for (i in 1 until moves.size) {
            val m = moves[i]
            val s = scores[i]
            var j = i - 1
            while (j >= 0 && scores[j] < s) {
                moves[j + 1] = moves[j]
                scores[j + 1] = scores[j]
                j--
            }
            moves[j + 1] = m
            scores[j + 1] = s
        }
    }

    private fun sortByScoreDescending(moves: IntArray, scores: IntArray) {
        for (i in 1 until moves.size) {
            val m = moves[i]
            val s = scores[i]
            var j = i - 1
            while (j >= 0 && scores[j] < s) {
                moves[j + 1] = moves[j]
                scores[j + 1] = scores[j]
                j--
            }
            moves[j + 1] = m
            scores[j + 1] = s
        }
    }

    private fun checkAbort() {
        if (nodes >= limits.maxNodes || cancelled() || System.nanoTime() >= limits.deadlineNanos) {
            throw SearchAborted
        }
    }
}
