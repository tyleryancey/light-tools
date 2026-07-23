package dev.tyler.chess.engine

/** Standard Algebraic Notation rendering for the move-list display. */
object San {
    /**
     * Renders [m], which must be legal in [board] (the position before the
     * move). Temporarily mutates [board] via make/unmake to decide the +/#
     * suffix; the board is restored before returning.
     */
    fun render(board: Board, m: Int): String {
        val from = moveFrom(m)
        val to = moveTo(m)
        val type = typeOf(board.pieceAt(from))
        val special = moveSpecial(m)
        val isCapture = board.pieceAt(to) != EMPTY || special == SPECIAL_EN_PASSANT
        val sb = StringBuilder()

        if (special == SPECIAL_CASTLE) {
            sb.append(if (fileOf(to) == 6) "O-O" else "O-O-O")
        } else if (type == PAWN) {
            if (isCapture) sb.append('a' + fileOf(from)).append('x')
            sb.append(squareName(to))
            if (movePromo(m) != 0) sb.append('=').append("NBRQ"[movePromo(m) - KNIGHT])
        } else {
            sb.append("NBRQK"[type - KNIGHT])
            // Disambiguate against other LEGAL same-type moves to the same
            // square (legal, not pseudo — a pinned twin must not force it):
            // file if unique, else rank, else both.
            var conflict = false
            var fileTaken = false
            var rankTaken = false
            for (other in board.legalMoves()) {
                if (other == m || moveTo(other) != to) continue
                val otherFrom = moveFrom(other)
                if (otherFrom == from || typeOf(board.pieceAt(otherFrom)) != type) continue
                conflict = true
                if (fileOf(otherFrom) == fileOf(from)) fileTaken = true
                if (rankOf(otherFrom) == rankOf(from)) rankTaken = true
            }
            if (conflict) {
                if (!fileTaken) {
                    sb.append('a' + fileOf(from))
                } else if (!rankTaken) {
                    sb.append('1' + rankOf(from))
                } else {
                    sb.append('a' + fileOf(from)).append('1' + rankOf(from))
                }
            }
            if (isCapture) sb.append('x')
            sb.append(squareName(to))
        }

        board.makeMove(m)
        val givesCheck = board.isCheck()
        val opponentHasMoves = board.legalMoves().isNotEmpty()
        board.unmakeMove()
        if (givesCheck) sb.append(if (opponentHasMoves) '+' else '#')
        return sb.toString()
    }
}
