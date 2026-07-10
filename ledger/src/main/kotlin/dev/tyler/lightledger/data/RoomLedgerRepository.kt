package dev.tyler.lightledger.data

import dev.tyler.lightledger.domain.DedupHash
import dev.tyler.lightledger.domain.LedgerMath
import dev.tyler.lightledger.domain.TransactionAmount
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomLedgerRepository private constructor(
    database: LedgerDatabase,
) : LedgerRepository {
    private val accountDao = database.accountDao()
    private val transactionDao = database.transactionDao()
    private val categoryDao = database.categoryDao()

    override suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        if (accountDao.count() == 0) {
            accountDao.insert(AccountEntity(name = "Cash", kind = AccountKind.MANUAL, currency = DEFAULT_CURRENCY))
        }
        if (categoryDao.count() == 0) {
            DEFAULT_CATEGORIES.forEachIndexed { index, name ->
                categoryDao.insert(CategoryEntity(name = name, sortOrder = index))
            }
        }
    }

    override suspend fun listCategories(): List<Category> = withContext(Dispatchers.IO) {
        categoryDao.listActive().map { it.toDomain() }
    }

    override suspend fun addCategory(name: String): Category = withContext(Dispatchers.IO) {
        val nextOrder = categoryDao.count()
        val id = categoryDao.insert(CategoryEntity(name = name, sortOrder = nextOrder))
        Category(id = id, name = name, sortOrder = nextOrder)
    }

    override suspend fun renameCategory(id: Long, name: String): Unit = withContext(Dispatchers.IO) {
        categoryDao.rename(id, name)
        Unit
    }

    override suspend fun archiveCategory(id: Long): Unit = withContext(Dispatchers.IO) {
        categoryDao.archive(id)
        Unit
    }

    override suspend fun addManualTransaction(
        amountMinor: Long,
        payee: String,
        categoryId: Long,
        memo: String,
    ): Long = withContext(Dispatchers.IO) {
        val account = accountDao.findManualAccount()
            ?: error("Manual account missing — call ensureSeeded() first")
        val postedEpochDay = LocalDate.now().toEpochDay()
        transactionDao.insert(
            TransactionEntity(
                accountId = account.id,
                postedEpochDay = postedEpochDay,
                amountMinor = amountMinor,
                payee = payee,
                memo = memo,
                categoryId = categoryId,
                status = TransactionStatus.CONFIRMED,
                source = TransactionSource.MANUAL,
                dedupHash = DedupHash.compute(account.id, postedEpochDay, amountMinor, payee),
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun monthSummary(month: YearMonth): List<CategoryMonthTotal> = withContext(Dispatchers.IO) {
        val range = LedgerMath.epochDayRange(month)
        val transactions = transactionDao.listConfirmedInRange(range.first, range.last)
        val categoriesById = categoryDao.listActive().associateBy { it.id }
        LedgerMath.categoryTotals(
            transactions.mapNotNull { txn ->
                val categoryId = txn.categoryId ?: return@mapNotNull null
                TransactionAmount(categoryId = categoryId, currency = DEFAULT_CURRENCY, amountMinor = txn.amountMinor)
            },
        ).mapNotNull { total ->
            val categoryId = total.categoryId ?: return@mapNotNull null
            val name = categoriesById[categoryId]?.name ?: return@mapNotNull null
            CategoryMonthTotal(categoryId = categoryId, categoryName = name, totalMinor = total.totalMinor)
        }
    }

    override suspend fun listTransactions(month: YearMonth): List<Transaction> = withContext(Dispatchers.IO) {
        val range = LedgerMath.epochDayRange(month)
        transactionDao.listConfirmedInRange(range.first, range.last).map { it.toDomain() }
    }

    override suspend fun getTransaction(id: Long): Transaction? = withContext(Dispatchers.IO) {
        transactionDao.getById(id)?.toDomain()
    }

    override suspend fun updateTransactionCategory(id: Long, categoryId: Long): Unit = withContext(Dispatchers.IO) {
        transactionDao.updateCategory(id, categoryId)
        Unit
    }

    override suspend fun updateTransactionMemo(id: Long, memo: String): Unit = withContext(Dispatchers.IO) {
        transactionDao.updateMemo(id, memo)
        Unit
    }

    override suspend fun deleteTransaction(id: Long): Unit = withContext(Dispatchers.IO) {
        transactionDao.delete(id)
        Unit
    }

    override suspend fun needsReviewCount(): Int = withContext(Dispatchers.IO) {
        transactionDao.countNeedsReview()
    }

    companion object {
        const val DATABASE_NAME = "ledger.db"
        private const val DEFAULT_CURRENCY = "USD"
        private val DEFAULT_CATEGORIES = listOf(
            "Groceries", "Dining", "Transport", "Home", "Health", "Fun", "Income", "Other",
        )

        @Volatile
        private var instance: RoomLedgerRepository? = null

        fun getInstance(databaseProvider: () -> LedgerDatabase): RoomLedgerRepository {
            return instance ?: synchronized(this) {
                instance ?: RoomLedgerRepository(databaseProvider()).also { instance = it }
            }
        }
    }
}

private fun CategoryEntity.toDomain() = Category(id = id, name = name, sortOrder = sortOrder)

private fun TransactionEntity.toDomain() = Transaction(
    id = id,
    accountId = accountId,
    postedEpochDay = postedEpochDay,
    amountMinor = amountMinor,
    payee = payee,
    memo = memo,
    categoryId = categoryId,
)
