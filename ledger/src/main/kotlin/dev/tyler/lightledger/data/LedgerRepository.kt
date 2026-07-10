package dev.tyler.lightledger.data

import java.time.YearMonth

interface LedgerRepository {
    suspend fun ensureSeeded()
    suspend fun listCategories(): List<Category>
    suspend fun addCategory(name: String): Category
    suspend fun renameCategory(id: Long, name: String)
    suspend fun archiveCategory(id: Long)
    suspend fun addManualTransaction(amountMinor: Long, payee: String, categoryId: Long, memo: String = ""): Long
    suspend fun monthSummary(month: YearMonth): List<CategoryMonthTotal>
    suspend fun listTransactions(month: YearMonth): List<Transaction>
    suspend fun getTransaction(id: Long): Transaction?
    suspend fun updateTransactionCategory(id: Long, categoryId: Long)
    suspend fun updateTransactionMemo(id: Long, memo: String)
    suspend fun deleteTransaction(id: Long)
    suspend fun needsReviewCount(): Int
}
