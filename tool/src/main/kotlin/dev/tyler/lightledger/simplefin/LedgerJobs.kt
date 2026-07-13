package dev.tyler.lightledger.simplefin

import androidx.datastore.preferences.core.edit
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.buildDatabase
import dev.tyler.lightledger.data.AndroidAccessUrlCipher
import dev.tyler.lightledger.data.LedgerDatabase
import dev.tyler.lightledger.data.LedgerPreferences
import dev.tyler.lightledger.data.RoomLedgerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** First-ever sync looks back this far (CLAUDE-light-ledger.md §6.2 step 2). */
private const val FIRST_SYNC_LOOKBACK_S = 60L * 24 * 3600

/** Every subsequent sync re-fetches this much before the last watermark, to absorb late
 * settlements/edits the bank made to already-synced days. */
private const val WATERMARK_OVERLAP_S = 7L * 24 * 3600

/**
 * SimpleFIN sync job (CLAUDE-light-ledger.md §6.2, steps 1-5 and 7 — §6.2.6 pending-settle
 * churn is deferred to M3b). Self-contained per the `@LightJob` contract: builds its own
 * repo from the [com.thelightphone.sdk.SealedLightContext] it receives rather than closing
 * over any app-level singleton.
 *
 * The Access URL is a bearer-equivalent credential (§6.1 step 2) — it is decrypted into a
 * local `val` for the duration of this handler and never logged.
 */
@LightJob("simplefin-sync")
val simpleFinSyncJob: LightJobHandler = { ctx, _ ->
    val repo = RoomLedgerRepository.getInstance {
        ctx.buildDatabase(LedgerDatabase::class.java, RoomLedgerRepository.DATABASE_NAME)
    }
    val prefs = ctx.dataStore.data.first()
    val blob = prefs[LedgerPreferences.ACCESS_BLOB]

    if (blob == null) {
        // Not configured yet is not an error.
        LightJobResult.Success()
    } else {
        // A rotated/invalidated Keystore key (e.g. after a lock-screen change) makes decrypt
        // throw. That's permanent, not transient — surface it as Error so Settings prompts
        // the user to reconnect, rather than crashing this handler uncaught.
        val accessUrl = try {
            AndroidAccessUrlCipher().decryptFromBase64(blob)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

        if (accessUrl == null) {
            LightJobResult.Error(mapOf("reason" to "decrypt"))
        } else {
            val nowMs = System.currentTimeMillis()
            val nowS = nowMs / 1000
            val startEpochS = prefs[LedgerPreferences.SYNC_START_EPOCH_S]
                ?.let { it - WATERMARK_OVERLAP_S }
                ?: (nowS - FIRST_SYNC_LOOKBACK_S)

            val result = SimpleFinSyncRunner(repo, SimpleFinClient()).sync(accessUrl, startEpochS)

            result.fold(
                onSuccess = { newCount ->
                    ctx.dataStore.edit { mutablePrefs ->
                        mutablePrefs[LedgerPreferences.LAST_SYNC_EPOCH_MS] = nowMs
                        mutablePrefs[LedgerPreferences.SYNC_START_EPOCH_S] = nowS
                    }
                    LightJobResult.Success(mapOf("new" to newCount.toString()))
                },
                onFailure = { error ->
                    when {
                        error is CancellationException -> throw error
                        // 403 => revoked token; other 4xx => surfaced client error. Neither is
                        // transient, so don't retry — Settings prompts the user to reconnect.
                        error is SimpleFinHttpException && error.statusCode in 400..499 ->
                            LightJobResult.Error(mapOf("reason" to error.statusCode.toString()))
                        // IOException, 5xx (surfaced as SimpleFinHttpException outside 4xx), or
                        // anything else unexpected: transient, let WorkManager back off and retry.
                        else -> LightJobResult.Retry
                    }
                },
            )
        }
    }
}
