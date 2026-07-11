package dev.tyler.lightledger.simplefin

import kotlinx.serialization.Serializable

/**
 * SimpleFIN "Account Set" response shape, per CLAUDE-light-ledger.md §6.1.4.
 * Only the fields the ledger actually consumes are modeled; everything else
 * (balance, available-balance, balance-date, org, ...) is tolerated via
 * [SimpleFinDecoder]'s `ignoreUnknownKeys = true`.
 */
@Serializable
data class AccountSet(
    val errors: List<String> = emptyList(),
    val accounts: List<SimpleFinAccount> = emptyList(),
)

@Serializable
data class SimpleFinAccount(
    val id: String,
    val name: String,
    val currency: String,
    val transactions: List<SimpleFinTransaction> = emptyList(),
)

@Serializable
data class SimpleFinTransaction(
    val id: String,
    val posted: Long,
    val amount: String,
    val description: String = "",
    val payee: String? = null,
    val memo: String? = null,
    val pending: Boolean = false,
)
