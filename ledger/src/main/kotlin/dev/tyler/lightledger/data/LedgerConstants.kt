package dev.tyler.lightledger.data

object AccountKind {
    const val MANUAL = "MANUAL"
    const val CSV = "CSV"
    const val SIMPLEFIN = "SIMPLEFIN"
}

object TransactionStatus {
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
    const val CONFIRMED = "CONFIRMED"
}

object TransactionSource {
    const val MANUAL = "MANUAL"
    const val CSV = "CSV"
    const val SIMPLEFIN = "SIMPLEFIN"
}
