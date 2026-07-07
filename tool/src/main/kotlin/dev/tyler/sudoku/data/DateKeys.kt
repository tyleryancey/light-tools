package dev.tyler.sudoku.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateKeys {
    private fun keyFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun today(now: Long): String = keyFmt().format(Date(now))

    /** Today first, then n-1 previous days. */
    fun lastNDays(n: Int, now: Long): List<String> {
        val fmt = keyFmt()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return List(n) { i ->
            if (i > 0) cal.add(Calendar.DAY_OF_YEAR, -1)
            fmt.format(cal.time)
        }
    }

    /** "2026-07-06" -> "Mon, Jul 6" (device locale). Falls back to the key on parse failure. */
    fun prettyShort(key: String): String = try {
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(keyFmt().parse(key)!!)
    } catch (_: Exception) {
        key
    }
}
