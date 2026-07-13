package dev.tyler.lightledger.data

/** Shared identifier for the SimpleFIN background sync job — the single source of truth for
 * `@LightJob(...)` in `simplefin/LedgerJobs.kt` and every `LightWork.enqueue`/`observe`/
 * `cancel`/`getState` call site in `ui/home/HomeScreen.kt` and `ui/settings/SettingsScreen.kt`.
 * A typo in a duplicated literal would silently make `LightWork.enqueue` a no-op. */
const val SIMPLEFIN_SYNC_JOB_KEY = "simplefin-sync"

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
