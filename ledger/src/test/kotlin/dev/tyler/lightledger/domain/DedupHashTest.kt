package dev.tyler.lightledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DedupHashTest {
    @Test fun normalizesCaseAndWhitespace() {
        assertEquals("coffee shop", DedupHash.normalizePayee("  Coffee   Shop  "))
    }

    @Test fun stripsTrailingDigitSuffix() {
        assertEquals("pos", DedupHash.normalizePayee("POS 1234"))
    }

    @Test fun keepsInternalDigits() {
        assertEquals("7 eleven", DedupHash.normalizePayee("7 Eleven"))
    }

    @Test fun hashIsCaseInsensitiveOnPayee() {
        val a = DedupHash.compute(1L, 20000L, -450L, "Coffee Shop")
        val b = DedupHash.compute(1L, 20000L, -450L, "coffee shop")
        assertEquals(a, b)
    }

    @Test fun hashDiffersWhenAmountDiffers() {
        val a = DedupHash.compute(1L, 20000L, -450L, "Coffee Shop")
        val b = DedupHash.compute(1L, 20000L, -451L, "Coffee Shop")
        assertNotEquals(a, b)
    }

    @Test fun hashDiffersWhenAccountDiffers() {
        val a = DedupHash.compute(1L, 20000L, -450L, "Coffee Shop")
        val b = DedupHash.compute(2L, 20000L, -450L, "Coffee Shop")
        assertNotEquals(a, b)
    }

    @Test fun hashDiffersWhenDayDiffers() {
        val a = DedupHash.compute(1L, 20000L, -450L, "Coffee Shop")
        val b = DedupHash.compute(1L, 20001L, -450L, "Coffee Shop")
        assertNotEquals(a, b)
    }
}
