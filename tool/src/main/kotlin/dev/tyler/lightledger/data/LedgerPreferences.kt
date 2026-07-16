package dev.tyler.lightledger.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object LedgerPreferences {
    val ACCESS_BLOB = stringPreferencesKey("simplefin_access_blob")
    val LAST_SYNC_EPOCH_MS = longPreferencesKey("simplefin_last_sync_epoch_ms")
    val SYNC_START_EPOCH_S = longPreferencesKey("simplefin_sync_start_epoch_s")
    val LAST_ERROR = stringPreferencesKey("simplefin_last_error")
    val BACKGROUND_SYNC_ENABLED = booleanPreferencesKey("background_sync_enabled")
    val BACKGROUND_SYNC_HOURS = longPreferencesKey("background_sync_hours")
}
