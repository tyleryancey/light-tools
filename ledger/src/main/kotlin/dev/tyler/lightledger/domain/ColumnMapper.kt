package dev.tyler.lightledger.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class CsvColumnMapping(
    val dateCol: Int,
    val dateFormat: String,
    val payeeCol: Int,
    val amountCol: Int? = null,
    val debitCol: Int? = null,
    val creditCol: Int? = null,
    val memoCol: Int? = null,
    val negateAmounts: Boolean = false,
)

data class MappedCsvRow(
    val date: LocalDate,
    val amountMinor: Long,
    val payee: String,
    val memo: String,
)

object ColumnMapper {
    fun map(table: CsvTable, mapping: CsvColumnMapping, currencyExponent: Int = 2): List<MappedCsvRow> {
        val formatter = DateTimeFormatter.ofPattern(mapping.dateFormat)
        return table.rows.map { row ->
            MappedCsvRow(
                date = LocalDate.parse(row[mapping.dateCol].trim(), formatter),
                amountMinor = resolveAmountMinor(row, mapping, currencyExponent),
                payee = row[mapping.payeeCol].trim(),
                memo = mapping.memoCol?.let { row.getOrElse(it) { "" }.trim() } ?: "",
            )
        }
    }

    private fun resolveAmountMinor(row: List<String>, mapping: CsvColumnMapping, exponent: Int): Long {
        val amount = if (mapping.amountCol != null) {
            AmountParser.parseToMinorUnits(row[mapping.amountCol], exponent)
        } else {
            val debitText = mapping.debitCol?.let { row.getOrNull(it) }?.trim().orEmpty()
            val creditText = mapping.creditCol?.let { row.getOrNull(it) }?.trim().orEmpty()
            when {
                debitText.isNotEmpty() -> -abs(AmountParser.parseToMinorUnits(debitText, exponent))
                creditText.isNotEmpty() -> abs(AmountParser.parseToMinorUnits(creditText, exponent))
                else -> 0L
            }
        }
        return if (mapping.negateAmounts) -amount else amount
    }
}
