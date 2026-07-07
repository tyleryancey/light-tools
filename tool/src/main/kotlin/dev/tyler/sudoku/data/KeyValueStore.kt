package dev.tyler.sudoku.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * The prototype's `window.storage` seam. Backed by the SDK's process-wide
 * DataStore in production; in-memory in tests.
 */
interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun delete(key: String)
}

class DataStoreKeyValueStore(private val dataStore: DataStore<Preferences>) : KeyValueStore {
    override suspend fun get(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun set(key: String, value: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun delete(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }
}

object StoreKeys {
    const val SETTINGS = "settings"
    const val INDEX = "index"
    fun puzzle(dateKey: String, difficulty: String) = "puz:$dateKey:$difficulty"
    fun progress(dateKey: String, difficulty: String) = "prog:$dateKey:$difficulty"
}
