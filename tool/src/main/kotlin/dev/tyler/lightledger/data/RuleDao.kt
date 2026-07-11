package dev.tyler.lightledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface RuleDao {
    @Insert
    fun insert(rule: RuleEntity): Long

    @Query("SELECT * FROM rules WHERE enabled = 1")
    fun listEnabled(): List<RuleEntity>
}
