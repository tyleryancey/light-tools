package dev.tyler.lightledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CurrencyExponentTest {
    @Test fun usdMapsToTwo() {
        assertEquals(2, CurrencyExponent.of("USD"))
    }

    @Test fun jpyMapsToZero() {
        assertEquals(0, CurrencyExponent.of("JPY"))
    }

    @Test fun bhdMapsToThree() {
        assertEquals(3, CurrencyExponent.of("BHD"))
    }

    @Test fun unknownCodeMapsToTwo() {
        assertEquals(2, CurrencyExponent.of("XYZ"))
    }

    @Test fun lowercaseCodeIsNormalized() {
        assertEquals(0, CurrencyExponent.of("jpy"))
    }

    @Test fun whitespaceIsTrimmed() {
        assertEquals(2, CurrencyExponent.of("  usd "))
    }
}
