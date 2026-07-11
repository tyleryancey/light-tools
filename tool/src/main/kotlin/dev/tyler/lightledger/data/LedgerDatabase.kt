package dev.tyler.lightledger.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        CategoryEntity::class,
        RuleEntity::class,
        CsvProfileEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    internal abstract fun accountDao(): AccountDao
    internal abstract fun transactionDao(): TransactionDao
    internal abstract fun categoryDao(): CategoryDao
    internal abstract fun ruleDao(): RuleDao
}
