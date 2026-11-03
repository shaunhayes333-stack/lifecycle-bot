package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Exercises the qualification that actually supplies bounded native evaluation slots. */
class Aate8522CurrentLaneElectionTest {
    @After fun reset() { SpecialistElection8136.resetForTests8136() }

    private fun sheet(vararg lanes: String): ToolkitSignalSheet.Sheet {
        val hypotheses = lanes.associateWith { lane -> ToolkitSignalSheet.DeskHypothesis(
            lane, ToolkitSignalSheet.Setup.CHART_PULLBACK_RECLAIM, 80.0,
            "observed_reclaim", "native_exit", 1.0, 1.0, 1.0, "native_ready") }
        return ToolkitSignalSheet.Sheet(ToolkitSignalSheet.Setup.CHART_PULLBACK_RECLAIM, 80.0,
            "reclaim", "native_entry", "native_exit", 1.0, 1.0, 1.0,
            lanes.toSet(), hypotheses, emptySet(), emptyList(), executableLanes7863 = lanes.toSet())
    }

    @Test fun changedEvidenceGetsCurrentQualifiedLaneAtBothCacheBoundaryAndThreeSecondCycle() {
        val prior = sheet("QUALITY")
        for (elapsed in listOf(2_499L, 2_500L, 2_501L, 3_000L)) {
            var builds = 0
            val current = ToolkitSignalSheet.electionSheet8522(prior, elapsed, false, true) {
                builds++
                sheet("BLUECHIP", "CASHGEN", "CYCLIC", "CORE")
            }
            assertEquals(1, builds)
            assertFalse("a prior qualification cannot survive changed evidence", "QUALITY" in current.executableLanes7863)
            for (lane in listOf("BLUECHIP", "CASHGEN", "CYCLIC", "CORE")) {
                assertTrue("$lane gets its existing bounded evaluation slot at ${elapsed}ms",
                    SpecialistElection8136.nativeEvalSlot8214("cycle8522-$elapsed", lane, "QUALITY", 1L,
                        lane in current.executableLanes7863))
            }
        }
        assertTrue(ToolkitSignalSheet.reusableDuringRefresh8491(2_500L))
        assertFalse("the old async fallback lost these qualifications at the normal loop interval",
            ToolkitSignalSheet.reusableDuringRefresh8491(3_000L))
    }

    @Test fun unchangedEvidenceReusesButColdExpiredModeChangedAndFutureDatedReadsRebuild() {
        val prior = sheet("DIP_HUNTER")
        for (elapsed in listOf(0L, 1_000L, 2_500L))
            assertSame(prior, ToolkitSignalSheet.electionSheet8522(prior, elapsed, true, true) {
                fail("unchanged evidence should not re-run the brains"); sheet()
            })
        for ((cached, elapsed, sameMode) in listOf(
            Triple(null, 0L, true), Triple(prior, 2_501L, true), Triple(prior, 3_000L, true),
            Triple(prior, 1L, false), Triple(prior, -1L, true))) {
            val current = sheet("EXPRESS")
            assertSame(current, ToolkitSignalSheet.electionSheet8522(cached, elapsed, true, sameMode) { current })
        }
    }

    @Test fun oldAsyncCompletionCannotReplaceTheNewerWorkerQualification() {
        var storedId: Long? = null
        var stored = sheet()
        fun publish(id: Long, incoming: ToolkitSignalSheet.Sheet) {
            if (ToolkitSignalSheet.newerBuild8522(storedId, id)) { storedId = id; stored = incoming }
        }
        // Work 11 began before work 12, but completed afterwards.
        publish(12L, sheet("CASHGEN"))
        publish(11L, sheet("QUALITY"))
        publish(12L, sheet("QUALITY"))
        assertEquals(setOf("CASHGEN"), stored.executableLanes7863)
        assertTrue(SpecialistElection8136.nativeEvalSlot8214("async8522", "CASHGEN", "QUALITY", 1L,
            "CASHGEN" in stored.executableLanes7863))
        assertFalse(SpecialistElection8136.nativeEvalSlot8214("async8522", "BLUECHIP", "QUALITY", 1L,
            "BLUECHIP" in stored.executableLanes7863))
    }

    @Test fun aNewSafetyObservationCannotReuseAnEarlierEligibleSheet() {
        val ts = TokenState("risk8522")
        ts.safety = SafetyReport(checkedAt = 10L, lpLockPct = 100.0, topHolderPct = 20.0)
        val priorRisk = SpecialistMarketInputs8522.riskFingerprint8522(ts)
        val prior = sheet("MOONSHOT", "EXPRESS")
        // An asynchronous holder/LP read updates the same check; clock and quote remain unchanged.
        ts.safety = ts.safety.copy(tier = SafetyTier.HARD_BLOCK, lpLockPct = 0.0,
            topHolderPct = 85.0, hardBlockReasons = listOf("UNLOCKED_LP", "TOP_HOLDERS"))
        val currentRisk = SpecialistMarketInputs8522.riskFingerprint8522(ts)
        assertNotEquals(priorRisk, currentRisk)
        val current = ToolkitSignalSheet.electionSheet8522(prior, 1L, priorRisk == currentRisk, true) { sheet() }
        assertFalse(SpecialistElection8136.nativeEvalSlot8214(ts.mint, "EXPRESS", "QUALITY", 1L,
            "EXPRESS" in current.executableLanes7863))
        assertTrue(ts.safety.isBlocked)
    }

    @Test fun currentQualificationDoesNotLiftTheExistingNativeFanoutLimit() {
        val mint = "bounded8522"
        for (lane in listOf("BLUECHIP", "CASHGEN", "CYCLIC", "CORE", "DIP_HUNTER", "EXPRESS"))
            assertTrue(SpecialistElection8136.nativeEvalSlot8214(mint, lane, "QUALITY", 1L, true))
        assertFalse("a seventh claim without a READY book still waits", SpecialistElection8136.nativeEvalSlot8214(
            mint, "MOONSHOT", "QUALITY", 1L, true))
        assertEquals(6, SpecialistElection8136.NATIVE_EVAL_CAP_8214)
        assertFalse(SpecialistElection8136.nativeEvalSlot8214(mint, "SHITCOIN", "QUALITY", 1L, false))
    }
}
