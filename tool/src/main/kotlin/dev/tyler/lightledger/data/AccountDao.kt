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

    @Query("SELECT * FROM accounts WHERE externalId = :externalId LIMIT 1")
    fun findByExternalId(externalId: String): AccountEntity?

    @Query("UPDATE accounts SET name = :name, currency = :currency WHERE id = :id")
    fun updateAccount(id: Long, name: String, currency: String): Int

    @Query("DELETE FROM accounts WHERE kind = 'SIMPLEFIN'")
    fun deleteSimpleFinAccounts(): Int

    @Query("SELECT name FROM accounts WHERE kind = 'SIMPLEFIN' AND archived = 0 ORDER BY name")
    fun listSimpleFinAccountNames(): List<String>
}
