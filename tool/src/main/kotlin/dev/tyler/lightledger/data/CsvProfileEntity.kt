package dev.tyler.lightledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "csv_profiles")
data class CsvProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val headerHash: String,
    val dateCol: Int,
    val dateFormat: String,
    val amountCol: Int? = null,
    val debitCol: Int? = null,
    val creditCol: Int? = null,
    val payeeCol: Int,
    val memoCol: Int? = null,
    val negateAmounts: Boolean = false,
)
