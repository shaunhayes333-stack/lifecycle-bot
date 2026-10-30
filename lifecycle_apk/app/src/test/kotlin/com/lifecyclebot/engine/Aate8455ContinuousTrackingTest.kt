package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8455 — owner: "everything is meant to be tracked from discovery until disposal with consistent true data". A tracked
 * coin without a current price is read continuously between its horizons (lowest rank, after every due read), and the
 * off-watch batch is 300 coins every 10 s.
 */
class Aate8455ContinuousTrackingTest {
    @Test fun aTrackedCoinIsReadAtLeastEveryMinute() {
        val now = 10_000_000L
        assertEquals(60_000L, ForwardReturnLabeler7731.PATH_REFRESH_MS_8455)
        assertTrue("never priced since its decision a minute ago", ForwardReturnLabeler7731.trackPath8455(0L, now - 60_000L, now))
        assertFalse("decided 30 s ago", ForwardReturnLabeler7731.trackPath8455(0L, now - 30_000L, now))
        assertTrue("last priced 90 s ago", ForwardReturnLabeler7731.trackPath8455(now - 90_000L, now - 600_000L, now))
        assertFalse("priced 20 s ago", ForwardReturnLabeler7731.trackPath8455(now - 20_000L, now - 600_000L, now))
    }

    @Test fun pathReadsRideTheBatchBehindEveryDueRead() {
        val l = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(l.contains("else if (trackPath8455(o.lastPxAtMs, o.atMs, nowMs)) dueUnpriced7737.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(9, maxOf(o.lastPxAtMs, o.atMs)))"))
        assertTrue(l.contains("private const val OFFWATCH_BATCH_7737 = 300"))
        // rank 9 sorts after every rank a due read can carry
        assertTrue(com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(9, 0L) > com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(8, 4_000_000_000_000L))   // any epoch-ms time before the year 2096
    }
}
