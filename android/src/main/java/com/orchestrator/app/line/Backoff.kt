package com.orchestrator.app.line

import com.orchestrator.api.Battery

/** How long to wait before trying the line again: 1 s, doubling, to 5 minutes (docs/PROTOCOL.md). */
object Backoff {
    const val FIRST_MS = 1_000L
    const val LONGEST_MS = 300_000L

    /** The pause after [failures] tries in a row that didn't connect (0: the first). */
    fun pause(failures: Int): Long = if (failures >= 9) LONGEST_MS else (FIRST_MS shl failures.coerceAtLeast(0)).coerceAtMost(LONGEST_MS)
}

/** When the battery is worth telling the server about again. */
object BatteryReport {
    /** A different level (whole percent), or charging started or stopped. */
    fun changed(last: Battery?, now: Battery): Boolean = last == null || last.charging != now.charging || last.level != now.level

    /** The level as a whole percent, from Android's level and scale; null when it isn't known. */
    fun percent(level: Int, scale: Int): Int? = if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
}
