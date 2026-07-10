package dev.tyler.lightledger.domain

import java.math.BigDecimal
import java.math.RoundingMode

object AmountParser {
    private val CURRENCY_SYMBOLS = setOf('$', '€', '£', '¥')

    fun parseToMinorUnits(raw: String, exponent: Int = 2): Long {
        var text = raw.trim()
        require(text.isNotEmpty()) { "Amount is empty" }

        var negative = false
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true
            text = text.substring(1, text.length - 1)
        }

        text = text.filterNot { it.isWhitespace() || it in CURRENCY_SYMBOLS }

        when {
            text.startsWith("-") -> {
                negative = true
                text = text.substring(1)
            }
            text.startsWith("+") -> text = text.substring(1)
        }

        val commaCount = text.count { it == ',' }
        val dotCount = text.count { it == '.' }
        text = when {
            commaCount == 1 && dotCount == 0 -> {
                // A single comma with exactly 2 trailing digits reads as a decimal
                // separator ("4,50"); 3 trailing digits reads as a thousands
                // separator ("1,234"). Real bank exports use both conventions.
                val afterComma = text.substringAfter(',')
                if (afterComma.length == 3) text.replace(",", "") else text.replace(',', '.')
            }
            commaCount > 0 && dotCount > 0 -> text.replace(",", "")
            commaCount > 1 -> text.replace(",", "")
            else -> text
        }

        require(text.isNotEmpty() && text.any { it.isDigit() }) { "Amount has no digits: $raw" }
        val decimal = BigDecimal(text).setScale(exponent, RoundingMode.HALF_UP)
        val minor = decimal.movePointRight(exponent).longValueExact()
        return if (negative) -minor else minor
    }
}
