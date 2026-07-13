package dev.tyler.lightledger.data

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed AES-256-GCM cipher for the SimpleFIN Access URL.
 * Copied from the proven authenticator TOTP cipher
 * (examples/authenticator/.../TotpSecretCipher.kt), wrapped behind
 * [AccessUrlCipher] so callers deal only in Base64 strings.
 *
 * Not JVM-unit-testable (requires AndroidKeyStore, i.e. a device/emulator);
 * correctness is verified on-device. JVM tests use [FakeAccessUrlCipher].
 */
class AndroidAccessUrlCipher(
    private val keystore: AccessUrlKeystore = AccessUrlKeystore(),
) : AccessUrlCipher {
    init {
        keystore.ensureKey()
    }

    override fun encryptToBase64(plaintext: String): String =
        Base64.getEncoder().encodeToString(encrypt(plaintext))

    override fun decryptFromBase64(blob: String): String =
        decrypt(Base64.getDecoder().decode(blob))

    fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystore.getSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    fun decrypt(blob: ByteArray): String {
        val buffer = ByteBuffer.wrap(blob)
        val iv = ByteArray(GCM_IV_LENGTH)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystore.getSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
