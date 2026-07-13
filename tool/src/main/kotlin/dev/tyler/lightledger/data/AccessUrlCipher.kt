package dev.tyler.lightledger.data

/**
 * Encrypts/decrypts the SimpleFIN Access URL (a credential) at rest, exposing
 * Base64-encoded blobs so callers never handle raw ciphertext bytes.
 *
 * [AndroidAccessUrlCipher] is the production implementation, backed by
 * AndroidKeyStore AES-256-GCM. It is not JVM-unit-testable (requires a device
 * or emulator), so tests use a fake implementing this interface instead.
 */
interface AccessUrlCipher {
    fun encryptToBase64(plaintext: String): String
    fun decryptFromBase64(blob: String): String
}
