package dev.tyler.lightledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface TransactionDao {
    @Insert
    fun insert(transaction: TransactionEntity): Long

    @Query(
        "SELECT * FROM transactions WHERE status = 'CONFIRMED' " +
            "AND postedEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY postedEpochDay DESC, id DESC",
    )
    fun listConfirmedInRange(startEpochDay: Long, endEpochDay: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun getById(id: Long): TransactionEntity?

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE id = :id")
    fun updateCategory(id: Long, categoryId: Long): Int

    @Query("UPDATE transactions SET memo = :memo WHERE id = :id")
    fun updateMemo(id: Long, memo: String): Int

    @Query("DELETE FROM transactions WHERE id = :id")
    fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'NEEDS_REVIEW'")
    fun countNeedsReview(): Int

    @Query("SELECT * FROM transactions WHERE accountId = :accountId AND externalId = :externalId LIMIT 1")
    fun findByExternalId(accountId: Long, externalId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE dedupHash = :dedupHash")
    fun findByDedupHash(dedupHash: String): List<TransactionEntity>

    @Query(
        "SELECT t.id AS id, t.postedEpochDay AS postedEpochDay, t.amountMinor AS amountMinor, " +
            "t.payee AS payee, a.name AS accountName, t.categoryId AS categoryId " +
            "FROM transactions t INNER JOIN accounts a ON t.accountId = a.id " +
            "WHERE t.status = 'NEEDS_REVIEW' " +
            "ORDER BY t.postedEpochDay DESC, t.id DESC",
    )
    fun listNeedsReviewWithAccount(): List<ReviewItem>

    @Query("UPDATE transactions SET categoryId = :categoryId, status = 'CONFIRMED' WHERE id = :id")
    fun confirm(id: Long, categoryId: Long): Int

    @Query(
        "UPDATE transactions SET amountMinor = :amountMinor, payee = :payee, " +
            "postedEpochDay = :postedEpochDay, pendingExternal = :pendingExternal WHERE id = :id",
    )
    fun updateExternalFields(
        id: Long,
        amountMinor: Long,
        payee: String,
        postedEpochDay: Long,
        pendingExternal: Boolean,
    ): Int

    @Query("UPDATE transactions SET externalId = :externalId WHERE id = :id")
    fun setExternalId(id: Long, externalId: String): Int

    @Query("SELECT payee, categoryId FROM transactions WHERE status = 'CONFIRMED' AND categoryId IS NOT NULL")
    fun listConfirmedPayeeCategory(): List<PayeeCategoryRow>

    @Query("DELETE FROM transactions WHERE accountId IN (SELECT id FROM accounts WHERE kind = 'SIMPLEFIN')")
    fun deleteBySimpleFinAccounts(): Int

    @Query(
        "SELECT t.categoryId AS categoryId, t.amountMinor AS amountMinor, a.currency AS currency " +
            "FROM transactions t JOIN accounts a ON t.accountId = a.id " +
            "WHERE t.status = 'CONFIRMED' AND t.postedEpochDay BETWEEN :startEpochDay AND :endEpochDay",
    )
    fun listConfirmedAmountsInRange(startEpochDay: Long, endEpochDay: Long): List<CategoryAmountRow>
}

/** Narrow projection for [TransactionDao.listConfirmedPayeeCategory]; not exposed outside the data layer. */
internal data class PayeeCategoryRow(val payee: String, val categoryId: Long)

/**
 * Narrow projection for [TransactionDao.listConfirmedAmountsInRange] — joins the account's real
 * currency so [RoomLedgerRepository.monthSummary] never hard-codes USD onto a non-USD account's
 * transactions. Not exposed outside the data layer.
 */
internal data class CategoryAmountRow(val categoryId: Long?, val amountMinor: Long, val currency: String)
