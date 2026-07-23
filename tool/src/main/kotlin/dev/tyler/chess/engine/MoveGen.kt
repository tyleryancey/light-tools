package dev.tyler.chess.engine

/**
 * Pseudo-legal generation with a make/own-king-attacked/unmake legality
 * filter — the simplest correct scheme, and the perft gate validates it while
 * hammering make/unmake on every generated move. Stepping is by (rank, file)
 * deltas so board-edge wraparound is impossible by construction.
 */
internal object MoveGen {
    // Flattened (rankDelta, fileDelta) pairs.
    private val KNIGHT_STEPS = intArrayOf(-2, -1, -2, 1, -1, -2, -1, 2, 1, -2, 1, 2, 2, -1, 2, 1)
    private val KING_STEPS = intArrayOf(-1, -1, -1, 0, -1, 1, 0, -1, 0, 1, 1, -1, 1, 0, 1, 1)
    private val BISHOP_DIRS = intArrayOf(-1, -1, -1, 1, 1, -1, 1, 1)
    private val ROOK_DIRS = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1)

    fun legal(board: Board): IntArray {
        val pseudo = pseudoLegal(board)
        val out = IntArray(pseudo.size)
        var n = 0
        val us = board.sideToMove
        for (m in pseudo) {
            board.makeMove(m)
            if (!isSquareAttacked(board, board.kingSq[us], 1 - us)) out[n++] = m
            board.unmakeMove()
        }
        return out.copyOf(n)
    }

    fun pseudoLegal(board: Board): IntArray {
        val moves = IntArray(256)
        var n = 0
        val us = board.sideToMove
        for (from in 0..63) {
            val piece = board.squares[from]
            if (piece == EMPTY || colorOf(piece) != us) continue
            when (typeOf(piece)) {
                PAWN -> n = pawnMoves(board, from, moves, n)
                KNIGHT -> n = stepMoves(board, from, KNIGHT_STEPS, moves, n)
                BISHOP -> n = slideMoves(board, from, BISHOP_DIRS, moves, n)
                ROOK -> n = slideMoves(board, from, ROOK_DIRS, moves, n)
                QUEEN -> {
                    n = slideMoves(board, from, BISHOP_DIRS, moves, n)
                    n = slideMoves(board, from, ROOK_DIRS, moves, n)
                }
                KING -> {
                    n = stepMoves(board, from, KING_STEPS, moves, n)
                    n = castleMoves(board, from, moves, n)
                }
            }
        }
        return moves.copyOf(n)
    }

    fun isSquareAttacked(board: Board, sq: Int, byColor: Int): Boolean {
        val squares = board.squares
        val rank = sq shr 3
        val file = sq and 7

        if (byColor == WHITE) {
            if (rank > 0) {
                if (file > 0 && squares[sq - 9] == W_PAWN) return true
                if (file < 7 && squares[sq - 7] == W_PAWN) return true
            }
        } else {
            if (rank < 7) {
                if (file > 0 && squares[sq + 7] == B_PAWN) return true
                if (file < 7 && squares[sq + 9] == B_PAWN) return true
            }
        }

        val knight = pieceOf(byColor, KNIGHT)
        var i = 0
        while (i < KNIGHT_STEPS.size) {
            val r = rank + KNIGHT_STEPS[i]
            val f = file + KNIGHT_STEPS[i + 1]
            if (r in 0..7 && f in 0..7 && squares[r * 8 + f] == knight) return true
            i += 2
        }

        val king = pieceOf(byColor, KING)
        i = 0
        while (i < KING_STEPS.size) {
            val r = rank + KING_STEPS[i]
            val f = file + KING_STEPS[i + 1]
            if (r in 0..7 && f in 0..7 && squares[r * 8 + f] == king) return true
            i += 2
        }

        val queen = pieceOf(byColor, QUEEN)
        if (rayHits(squares, rank, file, BISHOP_DIRS, pieceOf(byColor, BISHOP), queen)) return true
        if (rayHits(squares, rank, file, ROOK_DIRS, pieceOf(byColor, ROOK), queen)) return true
        return false
    }

    private fun rayHits(squares: IntArray, rank: Int, file: Int, dirs: IntArray, slider: Int, queen: Int): Boolean {
        var i = 0
        while (i < dirs.size) {
            var r = rank + dirs[i]
            var f = file + dirs[i + 1]
            while (r in 0..7 && f in 0..7) {
                val piece = squares[r * 8 + f]
                if (piece != EMPTY) {
                    if (piece == slider || piece == queen) return true
                    break
                }
                r += dirs[i]
                f += dirs[i + 1]
            }
            i += 2
        }
        return false
    }

    private fun pawnMoves(board: Board, from: Int, moves: IntArray, n0: Int): Int {
        var n = n0
        val squares = board.squares
        val us = board.sideToMove
        val dir = if (us == WHITE) 8 else -8
        val startRank = if (us == WHITE) 1 else 6
        val promoRank = if (us == WHITE) 7 else 0
        val rank = from shr 3
        val file = from and 7

        val one = from + dir // always on-board: pawns never stand on the last rank
        if (squares[one] == EMPTY) {
            n = addPawnMove(from, one, promoRank, moves, n)
            if (rank == startRank) {
                val two = from + 2 * dir
                if (squares[two] == EMPTY) moves[n++] = move(from, two, 0, SPECIAL_DOUBLE_PUSH)
            }
        }

        val ep = board.epSquare
        if (file > 0) {
            val to = from + dir - 1
            val target = squares[to]
            if (target != EMPTY && colorOf(target) != us) {
                n = addPawnMove(from, to, promoRank, moves, n)
            } else if (to == ep) {
                moves[n++] = move(from, to, 0, SPECIAL_EN_PASSANT)
            }
        }
        if (file < 7) {
            val to = from + dir + 1
            val target = squares[to]
            if (target != EMPTY && colorOf(target) != us) {
                n = addPawnMove(from, to, promoRank, moves, n)
            } else if (to == ep) {
                moves[n++] = move(from, to, 0, SPECIAL_EN_PASSANT)
            }
        }
        return n
    }

    private fun addPawnMove(from: Int, to: Int, promoRank: Int, moves: IntArray, n0: Int): Int {
        var n = n0
        if (to shr 3 == promoRank) {
            moves[n++] = move(from, to, QUEEN)
            moves[n++] = move(from, to, ROOK)
            moves[n++] = move(from, to, BISHOP)
            moves[n++] = move(from, to, KNIGHT)
        } else {
            moves[n++] = move(from, to)
        }
        return n
    }

    private fun stepMoves(board: Board, from: Int, steps: IntArray, moves: IntArray, n0: Int): Int {
        var n = n0
        val squares = board.squares
        val us = board.sideToMove
        val rank = from shr 3
        val file = from and 7
        var i = 0
        while (i < steps.size) {
            val r = rank + steps[i]
            val f = file + steps[i + 1]
            if (r in 0..7 && f in 0..7) {
                val to = r * 8 + f
                val target = squares[to]
                if (target == EMPTY || colorOf(target) != us) moves[n++] = move(from, to)
            }
            i += 2
        }
        return n
    }

    private fun slideMoves(board: Board, from: Int, dirs: IntArray, moves: IntArray, n0: Int): Int {
        var n = n0
        val squares = board.squares
        val us = board.sideToMove
        val rank = from shr 3
        val file = from and 7
        var i = 0
        while (i < dirs.size) {
            var r = rank + dirs[i]
            var f = file + dirs[i + 1]
            while (r in 0..7 && f in 0..7) {
                val to = r * 8 + f
                val target = squares[to]
                if (target == EMPTY) {
                    moves[n++] = move(from, to)
                } else {
                    if (colorOf(target) != us) moves[n++] = move(from, to)
                    break
                }
                r += dirs[i]
                f += dirs[i + 1]
            }
            i += 2
        }
        return n
    }

    // Generation already rejects castling out of, through, or into check, and
    // requires the rook actually on its home square (defends against loose
    // FENs that claim rights without the rook).
    private fun castleMoves(board: Board, from: Int, moves: IntArray, n0: Int): Int {
        var n = n0
        val squares = board.squares
        val rights = board.castlingRights
        val us = board.sideToMove
        val them = 1 - us
        if (us == WHITE) {
            if (from != 4) return n
            if (rights and CASTLE_WK != 0 && squares[7] == W_ROOK &&
                squares[5] == EMPTY && squares[6] == EMPTY &&
                !isSquareAttacked(board, 4, them) &&
                !isSquareAttacked(board, 5, them) &&
                !isSquareAttacked(board, 6, them)
            ) {
                moves[n++] = move(4, 6, 0, SPECIAL_CASTLE)
            }
            if (rights and CASTLE_WQ != 0 && squares[0] == W_ROOK &&
                squares[1] == EMPTY && squares[2] == EMPTY && squares[3] == EMPTY &&
                !isSquareAttacked(board, 4, them) &&
                !isSquareAttacked(board, 3, them) &&
                !isSquareAttacked(board, 2, them)
            ) {
                moves[n++] = move(4, 2, 0, SPECIAL_CASTLE)
            }
        } else {
            if (from != 60) return n
            if (rights and CASTLE_BK != 0 && squares[63] == B_ROOK &&
                squares[61] == EMPTY && squares[62] == EMPTY &&
                !isSquareAttacked(board, 60, them) &&
                !isSquareAttacked(board, 61, them) &&
                !isSquareAttacked(board, 62, them)
            ) {
                moves[n++] = move(60, 62, 0, SPECIAL_CASTLE)
            }
            if (rights and CASTLE_BQ != 0 && squares[56] == B_ROOK &&
                squares[57] == EMPTY && squares[58] == EMPTY && squares[59] == EMPTY &&
                !isSquareAttacked(board, 60, them) &&
                !isSquareAttacked(board, 59, them) &&
                !isSquareAttacked(board, 58, them)
            ) {
                moves[n++] = move(60, 58, 0, SPECIAL_CASTLE)
            }
        }
        return n
    }
}
