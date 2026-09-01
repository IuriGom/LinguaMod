package com.linguamod.app.debug

import com.linguamod.app.core.Clock
import java.time.LocalDate

/** Shared fake clock for unit + instrumented tests (Orchestrator §C.5). Debug-only. */
class FakeClock(
    var date: LocalDate = LocalDate.of(2026, 1, 1),
    var millis: Long = 1_700_000_000_000L,
) : Clock {
    override fun today(): LocalDate = date
    override fun nowMillis(): Long = millis
    fun advanceDays(n: Long) { date = date.plusDays(n); millis += n * 86_400_000L }
    fun advanceMinutes(n: Long) { millis += n * 60_000L }
    fun advanceHours(n: Long) { millis += n * 3_600_000L }
}
