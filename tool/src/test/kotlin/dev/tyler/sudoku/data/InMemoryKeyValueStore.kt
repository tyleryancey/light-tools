package dev.tyler.sudoku.data

class InMemoryKeyValueStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun set(key: String, value: String) { map[key] = value }
    override suspend fun delete(key: String) { map.remove(key) }
}
