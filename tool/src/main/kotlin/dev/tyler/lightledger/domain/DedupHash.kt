package dev.tyler.lightledger.domain

import java.security.MessageDigest

object DedupHash {
    private val TRAILING_DIGITS = Regex("\\s*\\d+$")
    private val WHITESPACE = Regex("\\s+")

    fun normalizePayee(payee: String): String {
        val collapsed = payee.lowercase().trim().replace(WHITESPACE, " ")
        return collapsed.replace(TRAILING_DIGITS, "")
    }

    fun compute(accountId: Long, postedEpochDay: Long, amountMinor: Long, payee: String): String {
        val input = "$accountId|$postedEpochDay|$amountMinor|${normalizePayee(payee)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
