package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexForwardEpoch8521
import com.lifecyclebot.engine.cortex.LanePlaybookDecision8871
import com.lifecyclebot.engine.cortex.LaneSetupEpisodes8372
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8871PlaybookEpisodeClockTest {
    private val at = 10_000_000L
    private val horizon = CortexLabelHygiene8144.EPISODE_MS_8144

    private fun grade(episodes: LaneSetupEpisodes8372, mint: String, time: Long, received: Long = time) {
        assertTrue(episodes.capture(mint, "EXPRESS", time, "EXPRESS", "SETUP#V=8871", "EARLY"))
        val decision = episodes.take(mint, "EXPRESS", time)!!
        assertTrue(episodes.record(decision, mapOf("SETUP" to LaneSetupEpisodes8372.Contribution(0.0, 4.0, 7.0)), received))
    }

    private fun owned(mint: String, time: Long, admitted: Boolean = false): LaneSetupEpisodes8372.Pending {
        val id = requireNotNull(LaneSetupEpisodes8372.key(mint, "EXPRESS", time))
        val proof = LanePlaybookDecision8871(id, admitted, true, false, true, "POOL",
            CortexForwardEpoch8521.targetKind("EXPRESS"), 1.0, time)
        return LaneSetupEpisodes8372.Pending(id, "$mint|EXPRESS", "EXPRESS", time, "SETUP#V=8871", "EARLY", proof)
    }

    @Test fun anOwnedOriginalReservesWithoutCompetingWithNewerPendingRows() {
        for (admitted in listOf(false, true)) {
            val episodes = LaneSetupEpisodes8372(capacity = 2)
            val original = owned("base|older", at, admitted)
            assertTrue(episodes.capture("base|older", "EXPRESS", at + 1_000L, "EXPRESS", "NEWER", "MID"))
            assertTrue(episodes.capture("other", "EXPRESS", at + 2_000L, "EXPRESS", "OTHER", "LATE"))
            val before = episodes.pendingSnapshot().toString()
            val evictedBefore = episodes.evicted
            assertTrue(episodes.matchesIndependent8871(original))
            val leased = requireNotNull(episodes.reserveIndependent8871(original))
            assertSame(original, leased)
            assertEquals(2, episodes.pendingCount())
            assertEquals("NEWER", episodes.latestTag("base|older", "EXPRESS"))
            assertEquals("OTHER", episodes.pending("other", "EXPRESS", at + 2_000L)!!.tag)
            assertTrue(episodes.record(leased, mapOf("SETUP" to LaneSetupEpisodes8372.Contribution(0.0, 4.0, 7.0)), at + 3_000L))
            episodes.releaseIndependent8871(leased)
            assertFalse(episodes.inFlight("base|older", "EXPRESS", at))
            assertEquals(before, episodes.pendingSnapshot().toString())
            assertEquals("owned delivery must not evict pending decisions", evictedBefore, episodes.evicted)
            assertNull("a completed original cannot reserve a second grade", episodes.reserveIndependent8871(original))
        }
    }

    @Test fun anOwnedDeliveryCannotStealAnExistingLeaseOrReleaseAnEqualCopy() {
        val episodes = LaneSetupEpisodes8372(capacity = 1)
        val original = owned("leased", at)
        assertTrue(episodes.capture("leased", "EXPRESS", at, original.lane, original.tag, original.timing, original.independent8871))
        val existingLease = requireNotNull(episodes.take("leased", "EXPRESS", at))
        val before = episodes.pendingSnapshot().toString()
        assertTrue(episodes.matchesIndependent8871(original))
        assertNull("an equal original does not own another callback's lease", episodes.reserveIndependent8871(original))
        episodes.releaseIndependent8871(existingLease.copy())
        assertTrue(episodes.inFlight("leased", "EXPRESS", at))
        assertEquals(before, episodes.pendingSnapshot().toString())
        episodes.releaseIndependent8871(existingLease)
        assertFalse(episodes.inFlight("leased", "EXPRESS", at))
        val retry = requireNotNull(episodes.reserveIndependent8871(original))
        episodes.releaseIndependent8871(retry)
        assertFalse("an unfinished owned application leaves no extra queued lease", episodes.inFlight("leased", "EXPRESS", at))
    }

    @Test fun anOwnedDeliveryStillRejectsConflictingOriginalIdentityWithoutChangingPending() {
        val episodes = LaneSetupEpisodes8372(capacity = 1)
        val original = owned("conflict", at)
        assertTrue(episodes.capture("conflict", "EXPRESS", at, original.lane, original.tag, original.timing, original.independent8871))
        val before = episodes.pendingSnapshot().toString()
        val conflict = original.copy(tag = "DIFFERENT#V=8871")
        assertFalse(episodes.matchesIndependent8871(conflict))
        assertNull(episodes.reserveIndependent8871(conflict))
        assertEquals(before, episodes.pendingSnapshot().toString())
        assertEquals(original, episodes.pending("conflict", "EXPRESS", at))
    }

    @Test fun anOwnedDispositionStillPreventsLaterLearningAfterOrdinaryPendingEviction() {
        val episodes = LaneSetupEpisodes8372(capacity = 1)
        val original = owned("excluded", at)
        assertTrue(episodes.capture("newer", "EXPRESS", at + 1_000L, "EXPRESS", "NEWER", "MID"))
        val before = episodes.pendingSnapshot().toString()
        val leased = requireNotNull(episodes.reserveIndependent8871(original))
        assertTrue(episodes.dispose("excluded", "EXPRESS", at, "HYGIENE_EXCLUDED_8372"))
        episodes.releaseIndependent8871(leased)
        assertNull(episodes.reserveIndependent8871(original))
        assertEquals("HYGIENE_EXCLUDED_8372", episodes.disposition("excluded", "EXPRESS", at))
        assertEquals(before, episodes.pendingSnapshot().toString())
    }

    @Test fun detailCapacityCannotForgetAnActiveRefusalEpisodeAndRestartKeepsTheClock() {
        val episodes = LaneSetupEpisodes8372(capacity = 2)
        grade(episodes, "base|first", at)
        grade(episodes, "second", at + 1_000L)
        grade(episodes, "third", at + 2_000L)
        assertEquals(2, episodes.gradeCount())
        assertNull("full grade detail was evicted", episodes.grade("base|first", "EXPRESS", at))
        assertEquals(at, episodes.lastGradedAt8871("base|first", "EXPRESS"))
        val compact = episodes.episodeClocksSnapshot8871(at + 3_000L).toString()
        val restored = LaneSetupEpisodes8372(capacity = 2)
        restored.restoreGrades(episodes.gradesSnapshot(), at + 3_000L)
        restored.restoreEpisodeClocks8871(JSONObject(compact), at + 3_000L)
        val previous = restored.lastGradedAt8871("base|first", "EXPRESS")
        assertEquals(at, previous)
        assertTrue(CortexLabelHygiene8144.repeatInEpisode8144(false, previous, at + horizon - 1L))
        assertFalse(CortexLabelHygiene8144.repeatInEpisode8144(false, previous, at + horizon))
        assertFalse("admissions retain their existing exception", CortexLabelHygiene8144.repeatInEpisode8144(true, previous, at + 3_000L))
    }

    @Test fun expiredClocksArePrunedAndAnAncientPendingCaptureCannotPinThemForever() {
        val episodes = LaneSetupEpisodes8372(capacity = 2)
        grade(episodes, "graded", at)
        episodes.capture("awaiting", "EXPRESS", at + horizon - 60L * 60_000L, "EXPRESS", "PENDING", "EARLY")
        episodes.episodeClocksSnapshot8871(at + horizon)
        assertEquals("a still-retained pending episode keeps its preceding six-hour window", at,
            episodes.lastGradedAt8871("graded", "EXPRESS"))
        val after = episodes.episodeClocksSnapshot8871(at + horizon + 31L * 60_000L)
        assertNull(episodes.lastGradedAt8871("graded", "EXPRESS"))
        assertEquals(0, after.getJSONObject("rows").length())
        assertNotNull("pruning a guard never invents an outcome for the old pending decision",
            episodes.pending("awaiting", "EXPRESS", at + horizon - 60L * 60_000L))
    }

    @Test fun aDelayedOriginalRetainsItsDecisionClockSeparatelyFromRetentionTime() {
        val received = at + 3L * 86_400_000L
        val episodes = LaneSetupEpisodes8372(capacity = 1)
        grade(episodes, "delayed", at, received)
        grade(episodes, "other", at + 1L, received + 1L)
        assertNull(episodes.grade("delayed", "EXPRESS", at))
        val restored = LaneSetupEpisodes8372(capacity = 1)
        restored.restoreEpisodeClocks8871(episodes.episodeClocksSnapshot8871(received + 2L), received + 2L)
        assertEquals("delivery must never rewrite the immutable decision clock", at, restored.lastGradedAt8871("delayed", "EXPRESS"))
        assertTrue(CortexLabelHygiene8144.repeatInEpisode8144(false,
            restored.lastGradedAt8871("delayed", "EXPRESS"), at + 10L * 60_000L))
    }

    @Test fun historicalSnapshotsRebuildOnlyTheirKnownClocksWithoutInventingMissingHistory() {
        val episodes = LaneSetupEpisodes8372()
        grade(episodes, "known", at)
        val oldGrades = episodes.gradesSnapshot()
        val restored = LaneSetupEpisodes8372()
        restored.restoreGrades(oldGrades, at + 1_000L)
        restored.restoreEpisodeClocks8871(null, at + 1_000L)
        assertEquals(at, restored.lastGradedAt8871("known", "EXPRESS"))
        assertNull(restored.lastGradedAt8871("not-in-history", "EXPRESS"))
        assertEquals(oldGrades.toString(), restored.gradesSnapshot().toString())
    }
}
