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
}
