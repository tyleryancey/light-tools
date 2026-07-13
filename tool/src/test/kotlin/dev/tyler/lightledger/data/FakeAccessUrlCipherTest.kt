package dev.tyler.lightledger.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Proves [FakeAccessUrlCipher] is a faithful round-trip stand-in for
 * [AndroidAccessUrlCipher] (which is not JVM-unit-testable — see Task 13).
 */
class FakeAccessUrlCipherTest {
    private val cipher = FakeAccessUrlCipher()

    @Test fun roundTripsPlaintext() {
        val accessUrl = "https://user:token@bridge.simplefin.org/simplefin"
        assertEquals(accessUrl, cipher.decryptFromBase64(cipher.encryptToBase64(accessUrl)))
    }

    @Test fun roundTripsEmptyString() {
        assertEquals("", cipher.decryptFromBase64(cipher.encryptToBase64("")))
    }

    @Test fun encodedBlobDiffersFromPlaintext() {
        val accessUrl = "https://user:token@bridge.simplefin.org/simplefin"
        assertNotEquals(accessUrl, cipher.encryptToBase64(accessUrl))
    }
}
