package dev.tyler.sudoku.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolveFeedbackTest {
    @Test fun pcmIsDeterministicBoundedAndNonSilent() {
        val a = SolveFeedback.buildArpeggioPcm(44100)
        val b = SolveFeedback.buildArpeggioPcm(44100)
        assertTrue(a.contentEquals(b), "synthesis must be deterministic")
        assertEquals((44100 * 0.77).toInt(), a.size, "0.32s offset + 0.45s tail")
        assertTrue(a.any { it != 0.toShort() }, "must not be silence")
        // normalized with 0.7 master gain: no clipping at the rails
        assertTrue(a.all { it > Short.MIN_VALUE }, "no negative clipping")
    }
}
