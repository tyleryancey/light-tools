package dev.tyler.lightledger.data

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * JVM-testable stand-in for [AndroidAccessUrlCipher]. Does NOT use AndroidKeyStore —
 * it simply Base64-encodes/decodes the plaintext, so it is reversible but provides
 * no real confidentiality. Lets ViewModel/connect-flow tests (Task 10) exercise the
 * connect flow on the JVM without a device/emulator.
 */
class FakeAccessUrlCipher : AccessUrlCipher {
    override fun encryptToBase64(plaintext: String): String =
        Base64.getEncoder().encodeToString(plaintext.toByteArray(StandardCharsets.UTF_8))

    override fun decryptFromBase64(blob: String): String =
        String(Base64.getDecoder().decode(blob), StandardCharsets.UTF_8)
}
