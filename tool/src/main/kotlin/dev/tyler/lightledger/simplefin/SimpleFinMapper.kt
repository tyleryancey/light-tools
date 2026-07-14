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
    // M3a assumes a 2-decimal (minor = 1/100) currency: AmountParser.parseToMinorUnits defaults
    // to exponent 2, and account.currency is not consulted here. This is correct for USD (all
    // M3a-supported accounts); non-2dp currencies (e.g. JPY=0, BHD=3) would mis-scale and need a
    // currency→exponent lookup — deferred to M3b.
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
