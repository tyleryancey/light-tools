package dev.tyler.chess.engine

/**
 * Zobrist keys for position hashing. The key includes side-to-move, castling
 * rights, and the en-passant file — all three are required for correct
 * threefold-repetition detection, and M2's transposition table reuses the
 * same key, so this is a first-class export of the engine.
 *
 * Tables are filled by SplitMix64 from a fixed literal seed so keys are
 * identical across runs and devices (bot determinism depends on this).
 */
internal object Zobrist {
    // SplitMix64 constants exceed Long.MAX_VALUE, so they are written as the
    // equivalent negative two's-complement literals:
    //   GOLDEN = 0x9E3779B97F4A7C15, MIX1 = 0xBF58476D1CE4E5B9, MIX2 = 0x94D049BB133111EB
    private const val GOLDEN = -0x61c8864680b583ebL
    private const val MIX1 = -0x40a7b892e31b1a47L
    private const val MIX2 = -0x6b2fb644ecceee15L

    private var state = 0x123456789ABCDEF0L

    private fun next(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    val pieceSquare = LongArray(768) // [(piece - 1) * 64 + square]
    val castling = LongArray(16)     // indexed by the whole rights mask
    val epFile = LongArray(8)
    val sideBlack: Long

    init {
        // Fill order is part of the contract: changing it changes every key.
        for (i in pieceSquare.indices) pieceSquare[i] = next()
        for (i in castling.indices) castling[i] = next()
        for (i in epFile.indices) epFile[i] = next()
        sideBlack = next()
    }

    /** From-scratch key; the oracle the incremental key is tested against. */
    fun compute(board: Board): Long {
        var key = 0L
        for (sq in 0..63) {
            val piece = board.squares[sq]
            if (piece != EMPTY) key = key xor pieceSquare[(piece - 1) * 64 + sq]
        }
        key = key xor castling[board.castlingRights]
        if (board.epSquare >= 0) key = key xor epFile[board.epSquare and 7]
        if (board.sideToMove == BLACK) key = key xor sideBlack
        return key
    }
}
