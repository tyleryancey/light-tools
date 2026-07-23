package dev.tyler.chess.engine

// Piece codes stored in Board.squares: 0 = empty, 1..6 white P N B R Q K,
// 7..12 black P N B R Q K. The 1..12 range indexes Zobrist.pieceSquare as
// (piece - 1) * 64 + square.
const val EMPTY = 0
const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6

const val W_PAWN = 1
const val W_KNIGHT = 2
const val W_BISHOP = 3
const val W_ROOK = 4
const val W_QUEEN = 5
const val W_KING = 6
const val B_PAWN = 7
const val B_KNIGHT = 8
const val B_BISHOP = 9
const val B_ROOK = 10
const val B_QUEEN = 11
const val B_KING = 12

const val WHITE = 0
const val BLACK = 1

fun typeOf(piece: Int): Int = if (piece > 6) piece - 6 else piece
fun colorOf(piece: Int): Int = if (piece > 6) BLACK else WHITE
fun pieceOf(color: Int, type: Int): Int = if (color == BLACK) type + 6 else type

// Castling-rights bits. The full 0..15 mask indexes Zobrist.castling directly.
const val CASTLE_WK = 1
const val CASTLE_WQ = 2
const val CASTLE_BK = 4
const val CASTLE_BQ = 8

// Squares: a1 = 0 .. h8 = 63, sq = rank * 8 + file.
fun square(file: Int, rank: Int): Int = rank * 8 + file
fun fileOf(sq: Int): Int = sq and 7
fun rankOf(sq: Int): Int = sq shr 3
fun squareName(sq: Int): String = "${'a' + (sq and 7)}${1 + (sq shr 3)}"
fun parseSquare(name: String): Int {
    if (name.length != 2) return -1
    val file = name[0] - 'a'
    val rank = name[1] - '1'
    return if (file in 0..7 && rank in 0..7) rank * 8 + file else -1
}

// Moves are packed Ints: bits 0-5 from, 6-11 to, 12-14 promotion piece type
// (0 none, else KNIGHT..QUEEN), 15-16 special. The captured piece is not
// encoded; makeMove records it in the undo stack.
const val NO_MOVE = 0
const val SPECIAL_NONE = 0
const val SPECIAL_EN_PASSANT = 1
const val SPECIAL_CASTLE = 2
const val SPECIAL_DOUBLE_PUSH = 3

fun move(from: Int, to: Int, promo: Int = 0, special: Int = SPECIAL_NONE): Int =
    from or (to shl 6) or (promo shl 12) or (special shl 15)

fun moveFrom(m: Int): Int = m and 63
fun moveTo(m: Int): Int = (m shr 6) and 63
fun movePromo(m: Int): Int = (m shr 12) and 7
fun moveSpecial(m: Int): Int = (m shr 15) and 3

fun moveToUci(m: Int): String {
    val base = squareName(moveFrom(m)) + squareName(moveTo(m))
    val promo = movePromo(m)
    return if (promo == 0) base else base + "nbrq"[promo - KNIGHT]
}
