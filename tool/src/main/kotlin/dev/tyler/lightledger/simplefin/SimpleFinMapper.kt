package dev.tyler.lightledger.simplefin

import dev.tyler.lightledger.domain.AmountParser
import java.time.Instant
import java.time.ZoneId

/** A SimpleFIN transaction mapped into ledger-native shapes (pure Kotlin, no Android). */
data class MappedExternalTxn(
    val externalId: String,
    val postedEpochDay: Long,
    val amountMinor: Long,
    val payee: String,
    val memo: String,
    val pending: Boolean,
)

object SimpleFinMapper {
    fun toMappedTransactions(account: SimpleFinAccount): List<MappedExternalTxn> =
        account.transactions.map { txn ->
            MappedExternalTxn(
                externalId = txn.id,
                postedEpochDay = Instant.ofEpochSecond(txn.posted)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toEpochDay(),
                amountMinor = AmountParser.parseToMinorUnits(txn.amount),
                payee = txn.payee ?: txn.description,
                memo = txn.memo ?: "",
                pending = txn.pending,
            )
        }
}
