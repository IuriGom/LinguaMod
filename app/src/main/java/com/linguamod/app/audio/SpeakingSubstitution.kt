package com.linguamod.app.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-app-session state for speaking→listening substitutions (Stage 3 §3):
 * counts substitutions (logged) and makes the explanation snackbar show
 * exactly once per session.
 */
@Singleton
class SpeakingSubstitution @Inject constructor() {
    private val snackbarShown = AtomicBoolean(false)
    val substitutionCount = AtomicInteger(0)

    /** Records a substitution; returns true only the first time this session. */
    fun recordAndShouldNotify(): Boolean {
        substitutionCount.incrementAndGet()
        return !snackbarShown.getAndSet(true)
    }
}
