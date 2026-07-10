package dev.tyler.lightledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface AccountDao {
    @Insert
    fun insert(account: AccountEntity): Long

    @Query("SELECT COUNT(*) FROM accounts")
    fun count(): Int

    @Query("SELECT * FROM accounts WHERE kind = 'MANUAL' AND archived = 0 LIMIT 1")
    fun findManualAccount(): AccountEntity?
}
