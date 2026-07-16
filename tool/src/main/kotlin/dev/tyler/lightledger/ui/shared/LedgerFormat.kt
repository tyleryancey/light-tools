package dev.tyler.lightledger.ui.shared

import dev.tyler.lightledger.domain.CurrencyExponent
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Currency
import java.util.Locale

/**
 * Shared currency-aware amount/date formatting for Home/History/Review. Android-free (only
 * java.text/java.math/java.time/java.util + domain) so it's plain-JVM unit-testable.
 */
object LedgerFormat {

    /**
     * Formats minor units (cents) as a localized currency string, scaling by the currency's own
     * exponent (JPY=0, USD=2, BHD=3) rather than a fixed /100. NumberFormat.setCurrency does NOT
     * adjust the format's fraction-digit count on its own (JDK behavior) — without explicitly
     * setting min/max fraction digits here, non-USD currencies would render with USD's 2 decimal
     * places. Exact via BigDecimal to avoid float rounding.
     */
    fun amount(amountMinor: Long, currencyCode: String): String {
        val format = NumberFormat.getCurrencyInstance(Locale.US)
        try {
            format.currency = Currency.getInstance(currencyCode)
        } catch (e: IllegalArgumentException) {
            // Unknown/invalid ISO 4217 code — fall back to the default USD-formatted instance.
        }
        val exponent = CurrencyExponent.of(currencyCode)
        format.minimumFractionDigits = exponent
        format.maximumFractionDigits = exponent
        val major = BigDecimal.valueOf(amountMinor).movePointLeft(exponent)
        return format.format(major)
    }

    fun date(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return date.month.name.take(3) + " " + date.dayOfMonth + ", " + date.year
    }
}
