package dev.tyler.lightledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val currency: String,
    val externalId: String? = null,
    val csvProfileId: Long? = null,
    val archived: Boolean = false,
)
