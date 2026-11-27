package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EvidenceWait8948
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate9043EvidenceWaitTest {
    private val wall = 1_800_000_000_000L
    private val elapsed = 10_000L
    private val direct = mutableListOf<String>()
    @Before fun before() {
        EvidenceWait8948.resetForTest8948()
        EvidenceWait8948.direct8948 = { mint, _ -> direct += mint }
    }
    @After fun after() {
        EvidenceWait8948.direct8948 = null
        EvidenceWait8948.resetForTest8948()
    }

    private fun wait(ts: TokenState, lane: String, opportunity: String, terminal: String = "DEFER_DATA", delta: Long = 0L,
                     wallDelta: Long = delta, sourceDeadlineElapsed: Long? = null) = EvidenceWait8948.note9043(
        ts, "LANE_POCKET", terminal, if (terminal == "DEFER_DATA") "DATA_INCOMPLETE_DEFER_8136" else "SPECIFIC_EDGE_PROVEN_8136",
        listOf("PRICE_STALE"), lane, opportunity, wall + wallDelta, elapsed + delta, deadlineElapsedMs = sourceDeadlineElapsed)

    @Test fun oneLanesTerminalCannotCloseAnotherLanesWaitOrResetTheMintThrottle() {
        val ts = TokenState(mint = "wait-9043", symbol = "WAIT")
        wait(ts, "EXPRESS", "pocket-a")
        wait(ts, "CORE", "unrelated-completed-pocket", "ADMIT", 1_000L)
        assertEquals(1, EvidenceWait8948.targets8948(wall + 1_000L, elapsed + 1_000L).size)
        wait(ts, "CORE", "pocket-b", delta = 2_000L)
        assertEquals("two lanes, one network request", 1, direct.size)
        wait(ts, "EXPRESS", "pocket-a", "ADMIT", 3_000L)
        assertTrue(EvidenceWait8948.statusLine8948(wall + 3_000L, elapsed + 3_000L).contains("waiting=1"))
        wait(ts, "CORE", "pocket-b", "ADMIT", 4_000L)
        wait(ts, "CORE", "new-source-event", delta = 5_000L)
        assertEquals("closing every obligation cannot clear the per-mint request throttle", 1, direct.size)
        wait(ts, "CORE", "new-source-event", delta = 21_000L)
        assertEquals(2, direct.size)
    }

    @Test fun repeatedRevisionsAndWallRollbackCannotRenewExpiredOpportunity() {
        val ts = TokenState(mint = "deadline-9043", symbol = "DUE")
        wait(ts, "EXPRESS", "stable-opportunity")
        wait(ts, "EXPRESS", "stable-opportunity", delta = 110_000L, wallDelta = -50_000L)
        val before = EvidenceWait8948.targets8948(wall - 50_000L, elapsed + 110_000L)
        assertEquals("only ten monotonic seconds remain", listOf(ts.mint to wall - 40_000L), before)
        wait(ts, "EXPRESS", "stable-opportunity", delta = 120_001L, wallDelta = -40_000L)
        assertTrue(EvidenceWait8948.targets8948(wall - 40_000L, elapsed + 120_001L).isEmpty())
        wait(ts, "EXPRESS", "stable-opportunity", delta = 130_000L)
        assertTrue("same opportunity remains expired", EvidenceWait8948.targets8948(wall + 130_000L, elapsed + 130_000L).isEmpty())
        wait(ts, "EXPRESS", "new-real-source-event", delta = 131_000L)
        assertEquals(1, EvidenceWait8948.targets8948(wall + 131_000L, elapsed + 131_000L).size)
    }

    @Test fun aLaunchWaitSpendsTheSourceWindowInsteadOfGettingANewTwoMinutes() {
        val ts = TokenState(mint = "source-clock-9043", symbol = "SRC")
        wait(ts, "EXPRESS", "fire-a", delta = 15_000L, sourceDeadlineElapsed = elapsed + 20_000L)
        assertEquals(listOf(ts.mint to wall + 20_000L), EvidenceWait8948.targets8948(wall + 15_000L, elapsed + 15_000L))
        wait(ts, "EXPRESS", "fire-a", delta = 20_001L, wallDelta = -100_000L, sourceDeadlineElapsed = elapsed + 20_000L)
        assertTrue(EvidenceWait8948.targets8948(wall - 100_000L, elapsed + 20_001L).isEmpty())
    }

    @Test fun delayedOldTerminalDoesNotCloseNewOpportunityInTheSameLane() {
        val ts = TokenState(mint = "same-lane-9043", symbol = "LANE")
        wait(ts, "EXPRESS", "old", delta = 0L)
        wait(ts, "EXPRESS", "new", delta = 1_000L)
        wait(ts, "EXPRESS", "old", "ADMIT", 2_000L)
        assertTrue(EvidenceWait8948.statusLine8948(wall + 2_000L, elapsed + 2_000L).contains("waiting=1"))
        assertEquals(listOf(ts.mint to wall + 121_000L), EvidenceWait8948.targets8948(wall + 2_000L, elapsed + 2_000L))
    }

    @Test fun frozenWallClockCannotReuseTheDirectRequestsOnceKey() {
        val ts = TokenState(mint = "wake-key-9043", symbol = "WAKE")
        val slots = mutableListOf<Long>()
        EvidenceWait8948.direct8948 = { _, slot -> slots += slot }
        wait(ts, "EXPRESS", "stable-opportunity")
        wait(ts, "EXPRESS", "stable-opportunity", delta = 21_000L, wallDelta = 0L)
        assertEquals(2, slots.size)
        assertNotEquals("a due proof request must not be deduplicated as the earlier wake", slots[0], slots[1])
        assertEquals((elapsed + 21_000L) / EvidenceWait8948.DIRECT_GAP_MS_8948, slots[1])
    }

    @Test fun retiredDetailsCannotReopenOldWaitAndRealLaterTradeGetsANewEpisode() {
        val ts = TokenState(mint = "retired-wait-9043", symbol = "OLD")
        val old = EvidenceWait8948.evidenceOpportunity9043("LANE_POCKET", "cell", "IGNITION_5M", 0L)
        wait(ts, "EXPRESS", old)
        wait(ts, "EXPRESS", old, "ADMIT", 1L)
        repeat(1_100) { i ->
            wait(ts, "EXPRESS", "other-source-$i", delta = 2L + i)
            wait(ts, "EXPRESS", "other-source-$i", "ADMIT", 2L + i)
        }
        wait(ts, "EXPRESS", old, delta = 150_000L)
        assertTrue("trimmed details cannot erase terminal identity", EvidenceWait8948.targets8948(wall + 150_000L, elapsed + 150_000L).isEmpty())
        val next = EvidenceWait8948.evidenceOpportunity9043("LANE_POCKET", "cell", "IGNITION_5M", wall + 150_000L)
        assertNotEquals(old, next)
        wait(ts, "EXPRESS", next, delta = 151_000L)
        assertEquals(1, EvidenceWait8948.targets8948(wall + 151_000L, elapsed + 151_000L).size)
    }
}
