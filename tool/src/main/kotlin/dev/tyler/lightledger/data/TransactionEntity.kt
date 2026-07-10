package dev.tyler.lightledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["accountId"]),
        // SQLite treats every NULL as distinct for UNIQUE purposes, so manual
        // rows (externalId = null) never collide with each other here even
        // without a partial-index "WHERE externalId IS NOT NULL" clause.
        Index(value = ["accountId", "externalId"], unique = true),
        Index(value = ["dedupHash"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val postedEpochDay: Long,
    val amountMinor: Long,
    val payee: String,
    val memo: String = "",
    val categoryId: Long? = null,
    val status: String,
    val source: String,
    val externalId: String? = null,
    val pendingExternal: Boolean = false,
    val dedupHash: String,
    val attachmentRef: String? = null,
    val createdAtEpochMs: Long,
)
