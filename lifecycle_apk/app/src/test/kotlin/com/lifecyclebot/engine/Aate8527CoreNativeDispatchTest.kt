package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527CoreNativeDispatchTest {
    private val p = CoreNativeDispatch8527
    private val original = SpecialistBrainBridge7542.Opinion("CORE", true, 62, 29, 0.01,
        "CORE_ENSEMBLE_QUALITY+BLUECHIP", "BREAKOUT", "core_native", "core_exit")
    private val snap = SpecialistBrainBridge7542.Snapshot("mint8527", 1, 10_000L, mapOf("CORE" to original))
    private fun core(primary: String = "CORE", s: SpecialistBrainBridge7542.Snapshot = snap,
                     current: SpecialistBrainBridge7542.Snapshot = s, ready: Long? = 9L,
                     version: Long = 9L, owner: String? = null, sameMode: Boolean = true,
                     occupied: Boolean = false, canPropose: Boolean = true, shadow: Boolean = false,
                     now: Long = 11_000L, choice: NativeOpportunityPolicy8522.Decision? = null) =
        p.currentCore8527(primary, s, current, ready, version, owner, sameMode, occupied, canPropose, shadow, now, choice)

    @Test fun currentElectedCoreIsAProposalWithoutRequiringTheGenericV3BuySignal() {
        val proposal = requireNotNull(core())
        assertEquals(62, proposal.score)
        assertEquals(29, proposal.confidence)
        assertNotNull(core(owner = "CORE"))
        for (lane in p.nativeLanes8527 - "CORE") assertNull(lane, core(primary = lane))
        assertNull(core(primary = "UNKNOWN"))
    }

    @Test fun electionModeGenerationOccupancyAndFreshnessAreBinding() {
        assertNull(core(owner = "MOONSHOT"))
        assertNull(core(ready = null))
        assertNull(core(ready = 8L))
        assertNull(core(version = 10L))
        assertNull(core(sameMode = false))
        assertNull(core(occupied = true))
        assertNull(core(canPropose = false))
        assertNull(core(shadow = true))
        assertNull(core(current = snap.copy(fingerprint = 2)))
        assertNull(core(now = 15_001L))
        assertNull(core(now = 9_999L))
        assertNull(core(s = snap.copy(opinions = mapOf("CORE" to original.copy(eligible = false)),
            originalOpinions8522 = mapOf("CORE" to original.copy(eligible = false)))))
    }

    @Test fun ttlOnlyNativeRefreshKeepsTheElectionButChangedEvidenceOrCompetitorDoesNot() {
        val refreshed = snap.copy(evaluatedAtMs = 12_600L)
        assertNotNull(core(current = refreshed, now = 12_700L))
        assertNull(core(current = refreshed.copy(fingerprint = 2), now = 12_700L))
        assertNull(core(current = refreshed.copy(mint = "different"), now = 12_700L))
        assertNull(core(current = refreshed.copy(opinions = refreshed.opinions +
            ("MOONSHOT" to original.copy(lane = "MOONSHOT"))), now = 12_700L))
        assertNull(core(current = refreshed.copy(originalOpinions8522 =
            mapOf("CORE" to original.copy(score = 63))), now = 12_700L))
        assertNull(core(current = refreshed.copy(evaluatedAtMs = 12_800L), now = 12_700L))
        assertNull(core(current = refreshed.copy(evaluatedAtMs = 7_699L), now = 12_700L))
    }

    @Test fun inversePolicyIsConsumedOnceAndAnExpiredProjectionCannotRemainExecutable() {
        val refuse = NativeOpportunityPolicy8522.decision8522(original, "REFUSE", 15.0,
            "mint8527", "LIVE", 9L, 4L, 10_000L, 10_000L, 10_000L, true)!!
        val refused = snap.copy(opportunityDecisions8522 = mapOf("CORE" to refuse))
        assertNull(core(s = refused, choice = refuse))
        assertNull(core(s = refused, choice = null))
        val rawNo = original.copy(eligible = false, reason = "CORE_YIELD_CLEAR_SPECIALIST_OWNER")
        val take = NativeOpportunityPolicy8522.decision8522(rawNo, "TAKE_CANDIDATE", -35.0,
            "mint8527", "LIVE", 9L, 4L, 10_000L, 10_000L, 10_000L, true)!!
        val takeSnap = snap.copy(originalOpinions8522 = mapOf("CORE" to rawNo),
            opportunityDecisions8522 = mapOf("CORE" to take))
        val got = requireNotNull(core(s = takeSnap, choice = take))
        assertEquals(rawNo.score, got.score)
        assertEquals(rawNo.confidence, got.confidence)
        assertEquals(85.0, got.selectionPriority8522!!, 0.0)
        val stale = NativeOpportunityPolicy8522.current8522(take, "mint8527", "CORE", "LIVE", 9L, 4L, 12_501L)
        assertNull(stale)
        assertNull(core(s = takeSnap, choice = stale))
    }

    @Test fun actualV3PathRunsCurrentNativeCoreBeforeEarlyReturnsAndKeepsSealedIntent() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val cycle = b.substringAfter("private fun processTokenCycle(")
        val at = cycle.indexOf("if (executeNativeCore8527(")
        // Deferred election is first read only after processToken publishes THIS cycle's score.
        val lazyAt = cycle.indexOf("val cycleSheet8522 by lazy")
        val publishedAt = cycle.indexOf("ts.lastV3Score = fresh7357.score")
        val firstReadAt = cycle.indexOf("V3_CANONICAL_HANDOFF_PENDING_6533")
        assertTrue(lazyAt >= 0 && publishedAt > lazyAt && firstReadAt > publishedAt)
        val beforePublication = cycle.substring(lazyAt, publishedAt)
        assertEquals(2, Regex("cycleSheet8522").findAll(beforePublication).count()) // declaration + lazy primary initializer
        assertTrue(firstReadAt < at)
        assertTrue(at > cycle.indexOf("V3_REJECTED_TERMINAL_EARLY_RETURN"))
        assertTrue(at < cycle.indexOf("val treasuryLaneAllowedThisCycle4483"))
        val body = b.substringAfter("private fun executeNativeCore8527(").substringBefore("private fun laneScoredBase7389(")
        assertTrue(body.contains("SpecialistBrainBridge7542.evaluate(ts)"))
        assertTrue(body.contains("CoreNativeDispatch8527.currentCore8527("))
        assertTrue(body.contains("SpecialistBrainBridge7542.opportunityDecision8522"))
        assertTrue(body.contains("shouldRunBuyLaneForCycle(ts, \"CORE\", primaryLane, sheet)"))
        assertTrue(body.contains("val coreFdg8527 = FdgOutcomeContinuity8197.evaluate("))
        assertTrue(body.contains("specialistLane = \"CORE\""))
        assertTrue(body.contains("fdgDecision7835 = coreFdg8527, tokenState7835 = ts"))
        assertTrue(body.contains("val intent = auth.executionIntent7835"))
        assertTrue(body.contains("intent.candidateVersion != version"))
        assertTrue(body.contains("sizeSol = intent.resolvedSize"))
        assertTrue(body.contains("attemptId = intent.attemptId"))
        val tail = cycle.substringAfter("// END Treasury Mode evaluation - now proceed with V3 decision handling")
        val execute = tail.substringAfter("is com.lifecyclebot.v3.V3Decision.Execute -> {")
            .substringBefore("is com.lifecyclebot.v3.V3Decision.Watch -> {")
        assertFalse(execute.contains("executor.v3Buy("))
        assertTrue(execute.contains("hasNativeExecutor8527(cyclePrimaryLane)"))
        assertTrue(execute.contains("V3_EXECUTE_UNSUPPORTED_OWNER_8527"))
    }

    @Test fun everyNamedDeskHasOneOwnExecutorNotAnUnknownGenericFallback() {
        assertEquals(12, p.nativeLanes8527.size)
        p.nativeLanes8527.forEach { assertTrue(p.hasNativeExecutor8527(it)) }
        assertTrue(p.hasNativeExecutor8527("BLUE_CHIP"))
        assertFalse(p.hasNativeExecutor8527("UNKNOWN"))
        assertFalse(p.hasNativeExecutor8527("V3_CORE"))
        val cyclic = File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        assertTrue(cyclic.contains("FdgOutcomeContinuity8197.evaluate("))
        assertTrue(cyclic.contains("currentMint   = best.mint"))
        assertTrue(cyclic.contains("entrySizeSol  = cyclicAuthorizedSize7835"))
    }
}
