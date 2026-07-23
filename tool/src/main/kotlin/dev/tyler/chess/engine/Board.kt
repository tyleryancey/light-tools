package dev.tyler.chess.engine

enum class GameStatus { ONGOING, CHECKMATE, STALEMATE, DRAW_FIFTY, DRAW_THREEFOLD, DRAW_MATERIAL }

// Applied on every move as rights and CASTLE_MASK[from] and CASTLE_MASK[to].
// Masking on the destination square is what catches the case of a rook being
// captured on its home square, which if-chains on the moving piece miss.
private val CASTLE_MASK = IntArray(64) { 15 }.also {
    it[0] = 15 - CASTLE_WQ
    it[4] = 15 - CASTLE_WK - CASTLE_WQ
    it[7] = 15 - CASTLE_WK
    it[56] = 15 - CASTLE_BQ
    it[60] = 15 - CASTLE_BK - CASTLE_BQ
    it[63] = 15 - CASTLE_BK
}

/**
 * Keeps an en-passant square only when a pawn of [sideToMove] can actually
 * play the capture (adjacent-file pseudo-check; pins are ignored). A "phantom"
 * EP square that no pawn can use would make two otherwise-identical positions
 * hash differently and silently break threefold detection.
 */
internal fun normalizedEp(squares: IntArray, sideToMove: Int, ep: Int): Int {
    if (ep < 0) return -1
    val expectedRank = if (sideToMove == WHITE) 5 else 2
    if (ep shr 3 != expectedRank) return -1
    // The full double-push geometry must hold, or a hostile FEN could make
    // move generation emit an EP capture with no victim behind it: the target
    // square empty and the just-pushed enemy pawn on the square behind it.
    val victim = if (sideToMove == WHITE) B_PAWN else W_PAWN
    val victimSq = if (sideToMove == WHITE) ep - 8 else ep + 8
    if (squares[ep] != EMPTY || squares[victimSq] != victim) return -1
    val capturerRank = if (sideToMove == WHITE) 4 else 3
    val capturer = if (sideToMove == WHITE) W_PAWN else B_PAWN
    val file = ep and 7
    if (file > 0 && squares[capturerRank * 8 + file - 1] == capturer) return ep
    if (file < 7 && squares[capturerRank * 8 + file + 1] == capturer) return ep
    return -1
}

/**
 * Mutable position with make/unmake over an allocation-free undo stack —
 * M2's search cannot afford a board copy per node, so this is in the API
 * from day one. Construct via [initial], [fromFen], or [replay].
 */
class Board internal constructor() {
    internal val squares = IntArray(64)
    var sideToMove: Int = WHITE
        internal set
    var castlingRights: Int = 0
        internal set
    var epSquare: Int = -1
        internal set
    var halfmoveClock: Int = 0
        internal set
    var fullmoveNumber: Int = 1
        internal set
    var zobristKey: Long = 0L
        internal set

    internal val kingSq = IntArray(2)

    // Undo stack as parallel primitive arrays. undoState packs:
    // bits 0-3 captured piece, 4-7 prior castling rights, 8-14 prior ep + 1,
    // bit 15+ prior halfmove clock. undoKeys saves the full prior key so
    // unmake restores it by assignment instead of reverse-XOR.
    private var undoMoves = IntArray(512)
    private var undoState = IntArray(512)
    private var undoKeys = LongArray(512)
    private var ply = 0

    fun pieceAt(sq: Int): Int = squares[sq]

    /** [m] must come from [legalMoves] or [uciToMove]; no validation here. */
    fun makeMove(m: Int) {
        if (ply == undoMoves.size) grow()
        val from = moveFrom(m)
        val to = moveTo(m)
        val piece = squares[from]
        val special = moveSpecial(m)
        var key = zobristKey
        var captured = squares[to]

        if (special == SPECIAL_EN_PASSANT) {
            val victimSq = if (sideToMove == WHITE) to - 8 else to + 8
            captured = squares[victimSq]
            squares[victimSq] = EMPTY
            key = key xor Zobrist.pieceSquare[(captured - 1) * 64 + victimSq]
        } else if (captured != EMPTY) {
            key = key xor Zobrist.pieceSquare[(captured - 1) * 64 + to]
        }

        undoMoves[ply] = m
        undoState[ply] = captured or (castlingRights shl 4) or ((epSquare + 1) shl 8) or (halfmoveClock shl 15)
        undoKeys[ply] = zobristKey
        ply++

        squares[from] = EMPTY
        key = key xor Zobrist.pieceSquare[(piece - 1) * 64 + from]
        val placed = if (movePromo(m) != 0) pieceOf(sideToMove, movePromo(m)) else piece
        squares[to] = placed
        key = key xor Zobrist.pieceSquare[(placed - 1) * 64 + to]

        if (special == SPECIAL_CASTLE) {
            val rookFrom: Int
            val rookTo: Int
            when (to) {
                6 -> { rookFrom = 7; rookTo = 5 }
                2 -> { rookFrom = 0; rookTo = 3 }
                62 -> { rookFrom = 63; rookTo = 61 }
                else -> { rookFrom = 56; rookTo = 59 }
            }
            val rook = squares[rookFrom]
            squares[rookFrom] = EMPTY
            squares[rookTo] = rook
            key = key xor Zobrist.pieceSquare[(rook - 1) * 64 + rookFrom] xor
                Zobrist.pieceSquare[(rook - 1) * 64 + rookTo]
        }

        val newRights = castlingRights and CASTLE_MASK[from] and CASTLE_MASK[to]
        if (newRights != castlingRights) {
            key = key xor Zobrist.castling[castlingRights] xor Zobrist.castling[newRights]
            castlingRights = newRights
        }

        if (epSquare >= 0) key = key xor Zobrist.epFile[epSquare and 7]
        epSquare = if (special == SPECIAL_DOUBLE_PUSH) {
            val target = if (sideToMove == WHITE) from + 8 else from - 8
            normalizedEp(squares, 1 - sideToMove, target)
        } else {
            -1
        }
        if (epSquare >= 0) key = key xor Zobrist.epFile[epSquare and 7]

        halfmoveClock = if (typeOf(piece) == PAWN || captured != EMPTY) 0 else halfmoveClock + 1
        if (sideToMove == BLACK) fullmoveNumber++
        if (typeOf(piece) == KING) kingSq[sideToMove] = to

        sideToMove = 1 - sideToMove
        zobristKey = key xor Zobrist.sideBlack
    }

    fun unmakeMove() {
        check(ply > 0) { "no move to unmake" }
        ply--
        val m = undoMoves[ply]
        val state = undoState[ply]
        val captured = state and 15
        val from = moveFrom(m)
        val to = moveTo(m)
        val special = moveSpecial(m)

        sideToMove = 1 - sideToMove // back to the mover
        val mover = if (movePromo(m) != 0) pieceOf(sideToMove, PAWN) else squares[to]
        squares[from] = mover
        squares[to] = EMPTY

        if (special == SPECIAL_EN_PASSANT) {
            val victimSq = if (sideToMove == WHITE) to - 8 else to + 8
            squares[victimSq] = captured
        } else if (captured != EMPTY) {
            squares[to] = captured
        }

        if (special == SPECIAL_CASTLE) {
            val rookFrom: Int
            val rookTo: Int
            when (to) {
                6 -> { rookFrom = 7; rookTo = 5 }
                2 -> { rookFrom = 0; rookTo = 3 }
                62 -> { rookFrom = 63; rookTo = 61 }
                else -> { rookFrom = 56; rookTo = 59 }
            }
            squares[rookFrom] = squares[rookTo]
            squares[rookTo] = EMPTY
        }

        castlingRights = (state shr 4) and 15
        epSquare = ((state shr 8) and 127) - 1
        halfmoveClock = state ushr 15
        if (sideToMove == BLACK) fullmoveNumber--
        if (typeOf(mover) == KING) kingSq[sideToMove] = from
        zobristKey = undoKeys[ply]
    }

    private fun grow() {
        undoMoves = undoMoves.copyOf(undoMoves.size * 2)
        undoState = undoState.copyOf(undoState.size * 2)
        undoKeys = undoKeys.copyOf(undoKeys.size * 2)
    }

    fun legalMoves(): IntArray = MoveGen.legal(this)

    fun legalMovesFrom(from: Int): IntArray {
        val all = legalMoves()
        val out = IntArray(all.size)
        var n = 0
        for (m in all) if (moveFrom(m) == from) out[n++] = m
        return out.copyOf(n)
    }

    fun isCheck(): Boolean = MoveGen.isSquareAttacked(this, kingSq[sideToMove], 1 - sideToMove)

    fun status(): GameStatus {
        if (legalMoves().isEmpty()) return if (isCheck()) GameStatus.CHECKMATE else GameStatus.STALEMATE
        if (halfmoveClock >= 100) return GameStatus.DRAW_FIFTY
        if (repetitionCount() >= 3) return GameStatus.DRAW_THREEFOLD
        if (insufficientMaterial()) return GameStatus.DRAW_MATERIAL
        return GameStatus.ONGOING
    }

    /**
     * Occurrences of the current position, counting itself. Only the last
     * [halfmoveClock] plies can contain a repeat — nothing before the last
     * irreversible move can recur — and the undo stack's saved keys are that
     * history, so no separate structure is needed.
     */
    fun repetitionCount(): Int {
        var count = 1
        val window = if (halfmoveClock < ply) halfmoveClock else ply
        var i = ply - 1
        val stop = ply - window
        while (i >= stop) {
            if (undoKeys[i] == zobristKey) count++
            i--
        }
        return count
    }

    // Dead-draw material only: K/K, KB/K, KN/K, and bishops (any number, both
    // sides) all on one square color. KNN/K and anything with a pawn, rook,
    // or queen stays ONGOING.
    private fun insufficientMaterial(): Boolean {
        var knights = 0
        var bishops = 0
        var bishopSquareColors = 0
        for (sq in 0..63) {
            val type = typeOf(squares[sq])
            if (type == PAWN || type == ROOK || type == QUEEN) return false
            if (type == KNIGHT) knights++
            if (type == BISHOP) {
                bishops++
                bishopSquareColors = bishopSquareColors or (1 shl (((sq shr 3) + sq) and 1))
            }
        }
        return when {
            knights + bishops <= 1 -> true
            knights == 0 && bishopSquareColors != 3 -> true
            else -> false
        }
    }

    fun fen(): String = Fen.render(this)

    /** Resolves a UCI string against the current legal moves; [NO_MOVE] otherwise. */
    fun uciToMove(uci: String): Int {
        if (uci.length < 4 || uci.length > 5) return NO_MOVE
        val from = parseSquare(uci.substring(0, 2))
        val to = parseSquare(uci.substring(2, 4))
        if (from < 0 || to < 0) return NO_MOVE
        val promo = if (uci.length == 5) {
            when (uci[4]) {
                'n' -> KNIGHT
                'b' -> BISHOP
                'r' -> ROOK
                'q' -> QUEEN
                else -> return NO_MOVE
            }
        } else {
            0
        }
        for (m in legalMoves()) {
            if (moveFrom(m) == from && moveTo(m) == to && movePromo(m) == promo) return m
        }
        return NO_MOVE
    }

    fun tryMoveUci(uci: String): Boolean {
        val m = uciToMove(uci)
        if (m == NO_MOVE) return false
        makeMove(m)
        return true
    }

    // Called by Fen.parse once the raw fields are in place.
    internal fun syncDerivedState() {
        var whiteKing = -1
        var blackKing = -1
        for (sq in 0..63) {
            when (squares[sq]) {
                W_KING -> {
                    require(whiteKing < 0) { "more than one white king" }
                    whiteKing = sq
                }
                B_KING -> {
                    require(blackKing < 0) { "more than one black king" }
                    blackKing = sq
                }
            }
        }
        require(whiteKing >= 0 && blackKing >= 0) { "both kings are required" }
        // Pawns on the first or last rank would step off the board inside
        // move generation; reject the position at the boundary instead.
        for (sq in 0..7) {
            require(typeOf(squares[sq]) != PAWN && typeOf(squares[sq + 56]) != PAWN) {
                "pawn on the first or last rank"
            }
        }
        kingSq[WHITE] = whiteKing
        kingSq[BLACK] = blackKing
        zobristKey = Zobrist.compute(this)
    }

    companion object {
        fun initial(): Board = Fen.parse(Fen.START)

        fun fromFen(fen: String): Board = Fen.parse(fen)

        /**
         * Rebuilds a game purely from its move list — the product's resume
         * path: every state, solo included, must be reconstructible this way.
         */
        fun replay(uciMoves: List<String>, fromFen: String? = null): Board {
            val board = if (fromFen == null) initial() else Fen.parse(fromFen)
            for (i in uciMoves.indices) {
                require(board.tryMoveUci(uciMoves[i])) { "illegal move at index $i: ${uciMoves[i]}" }
            }
            return board
        }
    }
}
