package dev.tyler.lightledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface CategoryDao {
    @Insert
    fun insert(category: CategoryEntity): Long

    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY sortOrder")
    fun listActive(): List<CategoryEntity>

    @Query("SELECT * FROM categories")
    fun listAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    fun count(): Int

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    fun rename(id: Long, name: String): Int

    @Query("UPDATE categories SET archived = 1 WHERE id = :id")
    fun archive(id: Long): Int
}
