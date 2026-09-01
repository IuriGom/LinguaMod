package com.linguamod.app.core

import java.time.LocalDate

/** Test seam (Orchestrator §C.5): everything date/time goes through Clock. */
interface Clock {
    fun today(): LocalDate
    fun nowMillis(): Long
}

class SystemClock @javax.inject.Inject constructor() : Clock {
    override fun today(): LocalDate = LocalDate.now()
    override fun nowMillis(): Long = System.currentTimeMillis()
}
