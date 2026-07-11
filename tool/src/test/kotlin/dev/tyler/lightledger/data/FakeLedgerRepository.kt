package dev.tyler.lightledger.data

import dev.tyler.lightledger.domain.LedgerMath
import dev.tyler.lightledger.domain.TransactionAmount
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicLong

class FakeLedgerRepository : LedgerRepository {
    private val categories = mutableListOf<Category>()
    private val transactions = mutableListOf<Transaction>()
    private val categoryIds = AtomicLong(1)
    private val transactionIds = AtomicLong(1)
    private val archivedCategoryIds = mutableSetOf<Long>()

    var seeded = false
        private set

    override suspend fun ensureSeeded() {
        if (seeded) return
        seeded = true
        listOf("Groceries", "Dining", "Transport", "Home", "Health", "Fun", "Income", "Other")
            .forEach { addCategory(it) }
    }

    override suspend fun listCategories(): List<Category> =
        categories.filterNot { it.id in archivedCategoryIds }

    override suspend fun addCategory(name: String): Category {
        val category = Category(id = categoryIds.getAndIncrement(), name = name, sortOrder = categories.size)
        categories.add(category)
        return category
    }

    override suspend fun renameCategory(id: Long, name: String) {
        val index = categories.indexOfFirst { it.id == id }
        if (index >= 0) categories[index] = categories[index].copy(name = name)
    }

    override suspend fun archiveCategory(id: Long) {
        archivedCategoryIds.add(id)
    }

    override suspend fun addManualTransaction(
        amountMinor: Long,
        payee: String,
        categoryId: Long,
        memo: String,
    ): Long {
        val id = transactionIds.getAndIncrement()
        transactions.add(
            Transaction(
                id = id,
                accountId = 1L,
                postedEpochDay = LocalDate.now().toEpochDay(),
                amountMinor = amountMinor,
                payee = payee,
                memo = memo,
                categoryId = categoryId,
            ),
        )
        return id
    }

    override suspend fun monthSummary(month: YearMonth): List<CategoryMonthTotal> {
        val range = LedgerMath.epochDayRange(month)
        val inRange = transactions.filter { it.postedEpochDay in range }
        return LedgerMath.categoryTotals(
            inRange.mapNotNull { txn ->
                val categoryId = txn.categoryId ?: return@mapNotNull null
                TransactionAmount(categoryId = categoryId, currency = "USD", amountMinor = txn.amountMinor)
            },
        ).mapNotNull { total ->
            val categoryId = total.categoryId ?: return@mapNotNull null
            val name = categories.firstOrNull { it.id == categoryId }?.name ?: return@mapNotNull null
            CategoryMonthTotal(categoryId = categoryId, categoryName = name, totalMinor = total.totalMinor)
        }
    }

    override suspend fun listTransactions(month: YearMonth): List<Transaction> {
        val range = LedgerMath.epochDayRange(month)
        return transactions.filter { it.postedEpochDay in range }
            .sortedWith(compareByDescending<Transaction> { it.postedEpochDay }.thenByDescending { it.id })
    }

    override suspend fun getTransaction(id: Long): Transaction? = transactions.firstOrNull { it.id == id }

    override suspend fun updateTransactionCategory(id: Long, categoryId: Long) {
        val index = transactions.indexOfFirst { it.id == id }
        if (index >= 0) transactions[index] = transactions[index].copy(categoryId = categoryId)
    }

    override suspend fun updateTransactionMemo(id: Long, memo: String) {
        val index = transactions.indexOfFirst { it.id == id }
        if (index >= 0) transactions[index] = transactions[index].copy(memo = memo)
    }

    override suspend fun deleteTransaction(id: Long) {
        transactions.removeAll { it.id == id }
    }

    override suspend fun needsReviewCount(): Int = 0
}
