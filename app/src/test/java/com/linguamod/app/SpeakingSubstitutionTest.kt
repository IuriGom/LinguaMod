package com.linguamod.app

import com.linguamod.app.audio.SpeakingSubstitution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3 §3: the speaking→listening substitution snackbar shows exactly once
 * per session, however many speaking exercises get substituted.
 */
class SpeakingSubstitutionTest {

    @Test fun `snackbar notification fires exactly once per session`() {
        val s = SpeakingSubstitution()
        assertTrue(s.recordAndShouldNotify()) // first substitution: show
        assertFalse(s.recordAndShouldNotify())
        assertFalse(s.recordAndShouldNotify())
        assertEquals(3, s.substitutionCount.get()) // every substitution is logged
    }
}
