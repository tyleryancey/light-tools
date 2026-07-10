package dev.tyler.lightledger.data

data class Category(val id: Long, val name: String, val sortOrder: Int)

data class Transaction(
    val id: Long,
    val accountId: Long,
    val postedEpochDay: Long,
    val amountMinor: Long,
    val payee: String,
    val memo: String,
    val categoryId: Long?,
)

data class CategoryMonthTotal(val categoryId: Long, val categoryName: String, val totalMinor: Long)
