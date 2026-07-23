package dev.tyler.chess.engine

/**
 * Test-only perft harness (never ships — the builder extracts src/main only).
 * Bulk-counting at depth 1 is safe here because the legality filter itself
 * make/unmakes every generated move.
 */
object Perft {
    fun perft(board: Board, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = board.legalMoves()
        if (depth == 1) return moves.size.toLong()
        var nodes = 0L
        for (m in moves) {
            board.makeMove(m)
            nodes += perft(board, depth - 1)
            board.unmakeMove()
        }
        return nodes
    }

    /** Per-root-move subtree counts, sorted by UCI — diffable against `stockfish go perft`. */
    fun divide(board: Board, depth: Int): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        for (m in board.legalMoves()) {
            board.makeMove(m)
            out.add(moveToUci(m) to perft(board, depth - 1))
            board.unmakeMove()
        }
        return out.sortedBy { it.first }
    }
}
