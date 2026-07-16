package dev.tyler.lightledger.data

/** Shared identifier for the SimpleFIN background sync job — the single source of truth for
 * `@LightJob(...)` in `simplefin/LedgerJobs.kt` and every `LightWork.enqueue`/`observe`/
 * `cancel`/`getState` call site in `ui/home/HomeScreen.kt` and `ui/settings/SettingsScreen.kt`.
 * A typo in a duplicated literal would silently make `LightWork.enqueue` a no-op. */
const val SIMPLEFIN_SYNC_JOB_KEY = "simplefin-sync"

/** Distinct WorkManager unique-work slot for the periodic background-sync schedule. Must differ
 * from [SIMPLEFIN_SYNC_JOB_KEY] — otherwise scheduling the periodic job and the one-shot
 * "Sync now" job would collide on the same unique-work name, and enqueuing one would silently
 * cancel the other. */
const val SIMPLEFIN_PERIODIC_TAG = "simplefin-sync-periodic"

object AccountKind {
    const val MANUAL = "MANUAL"
    const val CSV = "CSV"
    const val SIMPLEFIN = "SIMPLEFIN"
}

object TransactionStatus {
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
    const val CONFIRMED = "CONFIRMED"
}

object TransactionSource {
    const val MANUAL = "MANUAL"
    const val CSV = "CSV"
    const val SIMPLEFIN = "SIMPLEFIN"
}
