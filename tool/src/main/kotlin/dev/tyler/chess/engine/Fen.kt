package dev.tyler.chess.engine

internal object Fen {
    const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    // Index = piece code (space placeholder for EMPTY).
    private const val PIECE_CHARS = " PNBRQKpnbrqk"

    /**
     * Accepts 4 to 6 fields — clock fields default to 0 and 1. Several
     * canonical test FENs (Kiwipete among them) omit the clocks, so the
     * tolerance is required, not a convenience.
     */
    fun parse(fen: String): Board {
        val fields = fen.trim().split(" ").filter { it.isNotEmpty() }
        require(fields.size in 4..6) { "FEN must have 4 to 6 fields: $fen" }
        val board = Board()

        val ranks = fields[0].split("/")
        require(ranks.size == 8) { "FEN board must have 8 ranks: $fen" }
        for (row in 0..7) {
            val rank = 7 - row
            var file = 0
            for (c in ranks[row]) {
                if (c in '1'..'8') {
                    file += c - '0'
                } else {
                    val piece = PIECE_CHARS.indexOf(c)
                    require(piece > 0 && file < 8) { "bad FEN board field: $fen" }
                    board.squares[square(file, rank)] = piece
                    file++
                }
            }
            require(file == 8) { "FEN rank ${8 - row} does not span 8 files: $fen" }
        }

        board.sideToMove = when (fields[1]) {
            "w" -> WHITE
            "b" -> BLACK
            else -> throw IllegalArgumentException("bad side-to-move field: $fen")
        }

        var rights = 0
        if (fields[2] != "-") {
            for (c in fields[2]) {
                rights = rights or when (c) {
                    'K' -> CASTLE_WK
                    'Q' -> CASTLE_WQ
                    'k' -> CASTLE_BK
                    'q' -> CASTLE_BQ
                    else -> throw IllegalArgumentException("bad castling field: $fen")
                }
            }
        }
        board.castlingRights = rights

        val ep = if (fields[3] == "-") {
            -1
        } else {
            parseSquare(fields[3]).also { require(it >= 0) { "bad en-passant field: $fen" } }
        }
        board.epSquare = normalizedEp(board.squares, board.sideToMove, ep)

        board.halfmoveClock = if (fields.size >= 5) {
            fields[4].toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("bad halfmove clock: $fen")
        } else {
            0
        }
        board.fullmoveNumber = if (fields.size >= 6) {
            fields[5].toIntOrNull()?.takeIf { it >= 1 }
                ?: throw IllegalArgumentException("bad fullmove number: $fen")
        } else {
            1
        }

        board.syncDerivedState()
        return board
    }

    /** Always renders all 6 fields. */
    fun render(board: Board): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empties = 0
            for (file in 0..7) {
                val piece = board.squares[square(file, rank)]
                if (piece == EMPTY) {
                    empties++
                } else {
                    if (empties > 0) {
                        sb.append(empties)
                        empties = 0
                    }
                    sb.append(PIECE_CHARS[piece])
                }
            }
            if (empties > 0) sb.append(empties)
            if (rank > 0) sb.append('/')
        }
        sb.append(' ').append(if (board.sideToMove == WHITE) 'w' else 'b').append(' ')
        if (board.castlingRights == 0) {
            sb.append('-')
        } else {
            if (board.castlingRights and CASTLE_WK != 0) sb.append('K')
            if (board.castlingRights and CASTLE_WQ != 0) sb.append('Q')
            if (board.castlingRights and CASTLE_BK != 0) sb.append('k')
            if (board.castlingRights and CASTLE_BQ != 0) sb.append('q')
        }
        sb.append(' ').append(if (board.epSquare < 0) "-" else squareName(board.epSquare))
        sb.append(' ').append(board.halfmoveClock).append(' ').append(board.fullmoveNumber)
        return sb.toString()
    }
}
