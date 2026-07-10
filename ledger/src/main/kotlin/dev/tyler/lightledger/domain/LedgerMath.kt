package dev.tyler.lightledger.domain

import java.time.YearMonth

data class TransactionAmount(val categoryId: Long?, val currency: String, val amountMinor: Long)

data class CategoryTotal(val categoryId: Long?, val currency: String, val totalMinor: Long)

object LedgerMath {
    fun epochDayRange(yearMonth: YearMonth): LongRange {
        val start = yearMonth.atDay(1).toEpochDay()
        val end = yearMonth.atEndOfMonth().toEpochDay()
        return start..end
    }

    fun categoryTotals(transactions: List<TransactionAmount>): List<CategoryTotal> =
        transactions
            .groupBy { it.categoryId to it.currency }
            .map { (key, group) ->
                CategoryTotal(
                    categoryId = key.first,
                    currency = key.second,
                    totalMinor = group.sumOf { it.amountMinor },
                )
            }
}
