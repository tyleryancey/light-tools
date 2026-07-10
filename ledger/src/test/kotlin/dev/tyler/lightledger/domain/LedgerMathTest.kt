package dev.tyler.lightledger.domain

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
