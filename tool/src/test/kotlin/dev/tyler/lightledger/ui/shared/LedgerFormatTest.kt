package dev.tyler.lightledger.ui.shared

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerFormatTest {
    @Test fun formatsUsdWithTwoDecimals() {
        assertEquals("$4.50", LedgerFormat.amount(450, "USD"))
    }

    @Test fun formatsNegativeUsd() {
        assertEquals("-$4.50", LedgerFormat.amount(-450, "USD"))
    }

    @Test fun formatsJpyWithNoDecimals() {
        assertEquals("¥1,200", LedgerFormat.amount(1200, "JPY"))
    }

    @Test fun formatsBhdWithThreeDecimals() {
        assertTrue(LedgerFormat.amount(1234, "BHD").contains("1.234"))
    }

    @Test fun unknownCurrencyFallsBackToUsd() {
        assertEquals("$5.00", LedgerFormat.amount(500, "XYZ"))
    }

    @Test fun formatsDateAsAbbreviatedMonthDayYear() {
        val epochDay = LocalDate.of(2026, 7, 14).toEpochDay()
        val expected = LocalDate.ofEpochDay(epochDay).let {
            it.month.name.take(3) + " " + it.dayOfMonth + ", " + it.year
        }
        assertEquals(expected, LedgerFormat.date(epochDay))
    }
}
