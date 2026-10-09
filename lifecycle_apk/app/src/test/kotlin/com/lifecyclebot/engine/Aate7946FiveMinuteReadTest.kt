package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7946 — the read the learners use is five minutes, not an hour. */
class Aate7946FiveMinuteReadTest {
    @Test fun graceScalesWithTheHorizon() {
        assertEquals(150_000L, ForwardReturnLabeler7731.graceFor7946(5L * 60_000L))
        assertEquals(60_000L, ForwardReturnLabeler7731.graceFor7946(2L * 60_000L))
        assertEquals(600_000L, ForwardReturnLabeler7731.graceFor7946(60L * 60_000L))
    }

    @Test fun theFiveMinuteReadBooksOnlyInsideItsWindow() {
        assertTrue(ForwardReturnLabeler7731.horizonOpen7809(5L * 60_000L, 5L * 60_000L))
        assertFalse(ForwardReturnLabeler7731.horizonOpen7809(8L * 60_000L, 5L * 60_000L))
    }
}
