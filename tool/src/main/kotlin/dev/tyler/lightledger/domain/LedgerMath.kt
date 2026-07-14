package dev.tyler.lightledger.domain

import dev.tyler.lightledger.data.CategoryMonthTotal
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

    /**
     * The never-sum-across-currencies guard: groups [totals] by currency, sums only each
     * currency's negative (spend) totals, and returns the (currency, spend) pair with the largest
     * absolute spend. Never adds two currencies' minor units together, since minor units aren't
     * comparable across currencies. Returns `null` when [totals] is empty.
     */
    fun primaryCurrencyTotal(totals: List<CategoryMonthTotal>): Pair<String, Long>? =
        totals
            .groupBy { it.currency }
            .map { (currency, group) -> currency to group.filter { it.totalMinor < 0L }.sumOf { -it.totalMinor } }
            .maxByOrNull { (_, spend) -> spend }
}
