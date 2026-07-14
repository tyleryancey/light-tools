package dev.tyler.lightledger.data

import dev.tyler.lightledger.domain.CategoryRule
import dev.tyler.lightledger.domain.DedupHash
import dev.tyler.lightledger.domain.LedgerMath
import dev.tyler.lightledger.domain.PastConfirmation
import dev.tyler.lightledger.domain.TransactionAmount
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory mirror of [RoomLedgerRepository]. Must reproduce the same ordering/filtering
 * semantics as the Room implementation (see its DAO queries) so that ViewModel tests built
 * against this fake predict on-device behavior.
 */
class FakeLedgerRepository : LedgerRepository {
    private data class FakeAccount(
        val id: Long,
        var name: String,
        val kind: String,
        var currency: String,
        val externalId: String?,
    )

    private data class FakeTxn(
        val id: Long,
        val accountId: Long,
        var postedEpochDay: Long,
        var amountMinor: Long,
        var payee: String,
        var memo: String,
        var categoryId: Long?,
        var status: String,
        val source: String,
        var externalId: String?,
        var pendingExternal: Boolean,
        val dedupHash: String,
    )

    private val categories = mutableListOf<Category>()
    private val accounts = mutableListOf<FakeAccount>()
    private val transactions = mutableListOf<FakeTxn>()
    private val rules = mutableListOf<CategoryRule>()
    private val categoryIds = AtomicLong(1)
    private val transactionIds = AtomicLong(1)
    private val accountIds = AtomicLong(1)
    private val ruleIds = AtomicLong(1)
    private val archivedCategoryIds = mutableSetOf<Long>()
    private var manualAccountId: Long? = null

    var seeded = false
        private set

    override suspend fun ensureSeeded() {
        if (seeded) return
        seeded = true
        val id = accountIds.getAndIncrement()
        manualAccountId = id
        accounts.add(FakeAccount(id = id, name = "Cash", kind = AccountKind.MANUAL, currency = "USD", externalId = null))
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
        val accountId = manualAccountId ?: error("Manual account missing — call ensureSeeded() first")
        val id = transactionIds.getAndIncrement()
        val postedEpochDay = LocalDate.now().toEpochDay()
        transactions.add(
            FakeTxn(
                id = id,
                accountId = accountId,
                postedEpochDay = postedEpochDay,
                amountMinor = amountMinor,
                payee = payee,
                memo = memo,
                categoryId = categoryId,
                status = TransactionStatus.CONFIRMED,
                source = TransactionSource.MANUAL,
                externalId = null,
                pendingExternal = false,
                dedupHash = DedupHash.compute(accountId, postedEpochDay, amountMinor, payee),
            ),
        )
        return id
    }

    override suspend fun monthSummary(month: YearMonth): List<CategoryMonthTotal> {
        val range = LedgerMath.epochDayRange(month)
        val inRange = transactions.filter { it.status == TransactionStatus.CONFIRMED && it.postedEpochDay in range }
        return LedgerMath.categoryTotals(
            // Mirrors the Room DAO's JOIN accounts ON t.accountId = a.id: a row whose account no
            // longer exists is dropped (INNER JOIN semantics), and the currency always comes from
            // the account, never a hard-coded default.
            inRange.mapNotNull { txn ->
                val categoryId = txn.categoryId ?: return@mapNotNull null
                val currency = accounts.firstOrNull { it.id == txn.accountId }?.currency ?: return@mapNotNull null
                TransactionAmount(categoryId = categoryId, currency = currency, amountMinor = txn.amountMinor)
            },
        ).mapNotNull { total ->
            val categoryId = total.categoryId ?: return@mapNotNull null
            val name = categories.firstOrNull { it.id == categoryId }?.name ?: return@mapNotNull null
            CategoryMonthTotal(
                categoryId = categoryId,
                categoryName = name,
                totalMinor = total.totalMinor,
                currency = total.currency,
            )
        }
    }

    override suspend fun listTransactions(month: YearMonth): List<Transaction> {
        val range = LedgerMath.epochDayRange(month)
        return transactions.filter { it.status == TransactionStatus.CONFIRMED && it.postedEpochDay in range }
            .sortedWith(compareByDescending<FakeTxn> { it.postedEpochDay }.thenByDescending { it.id })
            .map { it.toTransaction() }
    }

    override suspend fun getTransaction(id: Long): Transaction? =
        transactions.firstOrNull { it.id == id }?.toTransaction()

    override suspend fun updateTransactionCategory(id: Long, categoryId: Long) {
        transactions.firstOrNull { it.id == id }?.categoryId = categoryId
    }

    override suspend fun updateTransactionMemo(id: Long, memo: String) {
        transactions.firstOrNull { it.id == id }?.memo = memo
    }

    override suspend fun deleteTransaction(id: Long) {
        transactions.removeAll { it.id == id }
    }

    override suspend fun needsReviewCount(): Int =
        transactions.count { it.status == TransactionStatus.NEEDS_REVIEW }

    override suspend fun upsertSimpleFinAccount(externalId: String, name: String, currency: String): Long {
        val existing = accounts.firstOrNull { it.externalId == externalId }
        if (existing != null) {
            existing.name = name
            existing.currency = currency
            return existing.id
        }
        val id = accountIds.getAndIncrement()
        accounts.add(FakeAccount(id = id, name = name, kind = AccountKind.SIMPLEFIN, currency = currency, externalId = externalId))
        return id
    }

    override suspend fun findTransactionByExternal(accountId: Long, externalId: String): TxnRef? =
        transactions.firstOrNull { it.accountId == accountId && it.externalId == externalId }?.toTxnRef()

    override suspend fun findDedupCandidates(dedupHash: String): List<TxnRef> =
        transactions.filter { it.dedupHash == dedupHash }.map { it.toTxnRef() }

    override suspend fun insertExternalTransaction(
        accountId: Long,
        postedEpochDay: Long,
        amountMinor: Long,
        payee: String,
        memo: String,
        categoryId: Long?,
        status: String,
        externalId: String,
        pendingExternal: Boolean,
        dedupHash: String,
    ): Long {
        val id = transactionIds.getAndIncrement()
        transactions.add(
            FakeTxn(
                id = id,
                accountId = accountId,
                postedEpochDay = postedEpochDay,
                amountMinor = amountMinor,
                payee = payee,
                memo = memo,
                categoryId = categoryId,
                status = status,
                source = TransactionSource.SIMPLEFIN,
                externalId = externalId,
                pendingExternal = pendingExternal,
                dedupHash = dedupHash,
            ),
        )
        return id
    }

    override suspend fun updateExternalTransactionFields(
        id: Long,
        amountMinor: Long,
        payee: String,
        postedEpochDay: Long,
        pendingExternal: Boolean,
    ) {
        transactions.firstOrNull { it.id == id }?.let {
            it.amountMinor = amountMinor
            it.payee = payee
            it.postedEpochDay = postedEpochDay
            it.pendingExternal = pendingExternal
        }
    }

    override suspend fun adoptExternalId(id: Long, externalId: String) {
        transactions.firstOrNull { it.id == id }?.externalId = externalId
    }

    override suspend fun listReviewInbox(): List<ReviewItem> =
        transactions.filter { it.status == TransactionStatus.NEEDS_REVIEW }
            .sortedWith(compareByDescending<FakeTxn> { it.postedEpochDay }.thenByDescending { it.id })
            // Mirrors Room's INNER JOIN accounts: a row whose account no longer exists is
            // dropped, not shown with a blank account name.
            .mapNotNull { txn ->
                val accountName = accounts.firstOrNull { it.id == txn.accountId }?.name ?: return@mapNotNull null
                ReviewItem(
                    id = txn.id,
                    postedEpochDay = txn.postedEpochDay,
                    amountMinor = txn.amountMinor,
                    payee = txn.payee,
                    accountName = accountName,
                    categoryId = txn.categoryId,
                )
            }

    override suspend fun confirmReview(id: Long, categoryId: Long) {
        transactions.firstOrNull { it.id == id }?.let {
            it.categoryId = categoryId
            it.status = TransactionStatus.CONFIRMED
        }
    }

    override suspend fun pastConfirmations(): List<PastConfirmation> =
        transactions.filter { it.status == TransactionStatus.CONFIRMED && it.categoryId != null }
            .map { PastConfirmation(normalizedPayee = DedupHash.normalizePayee(it.payee), categoryId = it.categoryId!!) }

    override suspend fun insertRule(payeeContains: String, categoryId: Long) {
        rules.add(
            CategoryRule(
                id = ruleIds.getAndIncrement(),
                payeeContains = DedupHash.normalizePayee(payeeContains),
                categoryId = categoryId,
                enabled = true,
            ),
        )
    }

    override suspend fun listRules(): List<CategoryRule> = rules.filter { it.enabled }

    override suspend fun deleteSimpleFinData() {
        val simpleFinAccountIds = accounts.filter { it.kind == AccountKind.SIMPLEFIN }.map { it.id }.toSet()
        transactions.removeAll { it.accountId in simpleFinAccountIds }
        accounts.removeAll { it.kind == AccountKind.SIMPLEFIN }
    }

    // Mirrors AccountDao.listSimpleFinAccountNames' "kind = 'SIMPLEFIN' AND archived = 0 ORDER
    // BY name". FakeAccount has no `archived` field — nothing in this codebase archives
    // accounts, so that clause is a no-op here; the kind filter alone is a faithful mirror.
    override suspend fun listSimpleFinAccounts(): List<String> =
        accounts.filter { it.kind == AccountKind.SIMPLEFIN }.sortedBy { it.name }.map { it.name }

    // Mirrors TransactionDao.listStalePendingExternalRows' "accountId = :accountId AND
    // pendingExternal = 1 AND postedEpochDay <= :olderThanEpochDay".
    override suspend fun listStalePendingExternal(accountId: Long, olderThanEpochDay: Long): List<TxnRef> =
        transactions.filter {
            it.accountId == accountId && it.pendingExternal && it.postedEpochDay <= olderThanEpochDay
        }.map { it.toTxnRef() }

    // Mirrors TransactionDao.findSettledMatchRows' "accountId = :accountId AND pendingExternal = 0
    // AND amountMinor = :amountMinor AND postedEpochDay BETWEEN :minEpochDay AND :maxEpochDay"
    // (BETWEEN is inclusive on both ends, matched here by the `..` closed range).
    override suspend fun findSettledMatches(
        accountId: Long,
        amountMinor: Long,
        minEpochDay: Long,
        maxEpochDay: Long,
    ): List<TxnRef> = transactions.filter {
        it.accountId == accountId &&
            !it.pendingExternal &&
            it.amountMinor == amountMinor &&
            it.postedEpochDay in minEpochDay..maxEpochDay
    }.map { it.toTxnRef() }

    private fun FakeTxn.toTransaction() = Transaction(
        id = id,
        accountId = accountId,
        postedEpochDay = postedEpochDay,
        amountMinor = amountMinor,
        payee = payee,
        memo = memo,
        categoryId = categoryId,
    )

    private fun FakeTxn.toTxnRef() = TxnRef(
        id = id,
        accountId = accountId,
        source = source,
        status = status,
        categoryId = categoryId,
        externalId = externalId,
        postedEpochDay = postedEpochDay,
        amountMinor = amountMinor,
        payee = payee,
        pendingExternal = pendingExternal,
    )
}
