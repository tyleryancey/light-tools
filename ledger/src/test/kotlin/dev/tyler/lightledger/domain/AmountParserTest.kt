package dev.tyler.lightledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmountParserTest {
    @Test fun parsesPlainDecimal() {
        assertEquals(450L, AmountParser.parseToMinorUnits("4.50"))
    }

    @Test fun parsesNegativeSign() {
        assertEquals(-450L, AmountParser.parseToMinorUnits("-4.50"))
    }

    @Test fun parsesParenthesesAsNegative() {
        assertEquals(-450L, AmountParser.parseToMinorUnits("(4.50)"))
    }

    @Test fun parsesCommaAsDecimalSeparator() {
        assertEquals(450L, AmountParser.parseToMinorUnits("4,50"))
    }

    @Test fun parsesCommaAsThousandsSeparatorWithDot() {
        assertEquals(123456L, AmountParser.parseToMinorUnits("1,234.56"))
    }

    @Test fun parsesCommaAsThousandsSeparatorWithoutDot() {
        assertEquals(123400L, AmountParser.parseToMinorUnits("1,234"))
    }

    @Test fun stripsCurrencySymbolsAndSpaces() {
        assertEquals(450L, AmountParser.parseToMinorUnits("$ 4.50"))
        assertEquals(450L, AmountParser.parseToMinorUnits("€4.50"))
    }

    @Test fun parsesWholeNumberAsMajorUnits() {
        assertEquals(123400L, AmountParser.parseToMinorUnits("1234"))
    }

    @Test fun roundTripsParseFormatParse() {
        val minor = AmountParser.parseToMinorUnits("12.34")
        val text = "%.2f".format(minor / 100.0)
        assertEquals(minor, AmountParser.parseToMinorUnits(text))
    }

    @Test fun blankAmountThrows() {
        assertFailsWith<IllegalArgumentException> { AmountParser.parseToMinorUnits("") }
    }
}
