package dev.tyler.lightledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payeeContains: String,
    val categoryId: Long,
    val enabled: Boolean = true,
)
