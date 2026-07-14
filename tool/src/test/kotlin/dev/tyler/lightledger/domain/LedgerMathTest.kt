package dev.tyler.lightledger.domain

import dev.tyler.lightledger.data.CategoryMonthTotal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LedgerMathTest {
    @Test fun epochDayRangeCoversFullMonth() {
        val range = LedgerMath.epochDayRange(YearMonth.of(2026, 2))
        assertEquals(LocalDate.of(2026, 2, 1).toEpochDay(), range.first)
        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), range.last)
    }

    @Test fun epochDayRangeHandlesYearWrap() {
        val range = LedgerMath.epochDayRange(YearMonth.of(2025, 12))
        assertEquals(LocalDate.of(2025, 12, 1).toEpochDay(), range.first)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), range.last)
    }

    @Test fun categoryTotalsSumsPerCategory() {
        val totals = LedgerMath.categoryTotals(
            listOf(
                TransactionAmount(categoryId = 1L, currency = "USD", amountMinor = -1000L),
                TransactionAmount(categoryId = 1L, currency = "USD", amountMinor = -500L),
                TransactionAmount(categoryId = 2L, currency = "USD", amountMinor = 5000L),
            ),
        )
        assertEquals(-1500L, totals.first { it.categoryId == 1L }.totalMinor)
        assertEquals(5000L, totals.first { it.categoryId == 2L }.totalMinor)
    }

    @Test fun keepsCurrenciesSeparate() {
        val totals = LedgerMath.categoryTotals(
            listOf(
                TransactionAmount(categoryId = 1L, currency = "USD", amountMinor = -1000L),
                TransactionAmount(categoryId = 1L, currency = "EUR", amountMinor = -900L),
            ),
        )
        assertEquals(2, totals.size)
        assertEquals(-1000L, totals.first { it.currency == "USD" }.totalMinor)
        assertEquals(-900L, totals.first { it.currency == "EUR" }.totalMinor)
    }

    @Test fun primaryCurrencyTotalPicksLargerAbsoluteSpendNeverSumsAcrossCurrencies() {
        val totals = listOf(
            CategoryMonthTotal(categoryId = 1L, categoryName = "Groceries", totalMinor = -5000L, currency = "USD"),
            CategoryMonthTotal(categoryId = 2L, categoryName = "Dining", totalMinor = -9000L, currency = "JPY"),
        )
        // JPY's 9000 minor-unit spend outweighs USD's 5000, so JPY wins — and the result is
        // exactly JPY's own spend (9000), never USD + JPY summed together (14000).
        assertEquals("JPY" to 9000L, LedgerMath.primaryCurrencyTotal(totals))
    }

    @Test fun primaryCurrencyTotalIgnoresPositiveTotalsWhenSummingSpend() {
        val totals = listOf(
            CategoryMonthTotal(categoryId = 1L, categoryName = "Groceries", totalMinor = -1200L, currency = "USD"),
            CategoryMonthTotal(categoryId = 2L, categoryName = "Income", totalMinor = 5000L, currency = "USD"),
        )
        assertEquals("USD" to 1200L, LedgerMath.primaryCurrencyTotal(totals))
    }

    @Test fun primaryCurrencyTotalReturnsNullWhenEmpty() {
        assertNull(LedgerMath.primaryCurrencyTotal(emptyList()))
    }
}
