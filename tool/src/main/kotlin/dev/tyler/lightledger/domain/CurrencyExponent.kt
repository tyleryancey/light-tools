package dev.tyler.lightledger.domain

/**
 * Maps an ISO-4217 currency code to its minor-unit exponent (decimal places), so
 * [AmountParser.parseToMinorUnits] can scale non-USD SimpleFIN amounts correctly.
 * Pure Kotlin, no Android dependencies.
 */
object CurrencyExponent {
    private val ZERO_DECIMAL_CODES = setOf(
        "JPY", "KRW", "VND", "CLP", "ISK", "HUF", "XOF", "XAF", "XPF",
    )

    private val THREE_DECIMAL_CODES = setOf(
        "BHD", "KWD", "OMR", "TND", "IQD", "JOD", "LYD",
    )

    fun of(code: String): Int {
        val normalized = code.trim().uppercase()
        return when (normalized) {
            in ZERO_DECIMAL_CODES -> 0
            in THREE_DECIMAL_CODES -> 3
            else -> 2
        }
    }
}
