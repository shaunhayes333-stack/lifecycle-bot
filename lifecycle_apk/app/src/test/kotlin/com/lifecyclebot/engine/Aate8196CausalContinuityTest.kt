package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexWalkForward8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 8196: end-to-end Cortex provenance and native-handoff regression contracts. */
class Aate8196CausalContinuityTest {
    private fun source(relative: String) =
        File("src/main/kotlin/com/lifecyclebot/$relative").readText()

    @Test fun secondSpecialistCannotLoseASeparateMissingFeatureBehindFirstThrottle() {
        val pb = com.lifecyclebot.engine.cortex.LanePlaybook7907
        val t0 = 2_000_000_000_000L
        val timestamps = mutableMapOf<String, Long>()
        val first = pb.dueMissingInputs8196(listOf("shape:INVALID", "tape:NO_PRINTS"), { timestamps[it] }, t0)
        assertEquals(listOf("shape:INVALID", "tape:NO_PRINTS"), first)
        first.forEach { timestamps[it.substringBefore(':')] = t0 }
        val second = pb.dueMissingInputs8196(
            listOf("shape:INVALID", "bp:DEFAULT_UNOBSERVED", "chg:DEFAULT_UNOBSERVED", "tape:UNKNOWN"), { timestamps[it] }, t0 + 1000L)
        assertEquals(listOf("bp:DEFAULT_UNOBSERVED", "chg:DEFAULT_UNOBSERVED"), second)
        assertTrue(pb.dueMissingInputs8196(listOf("shape:INVALID", "tape:NO_PRINTS"), { timestamps[it] }, t0 + 1000L).isEmpty())
        assertEquals(listOf("shape:INVALID"), pb.dueMissingInputs8196(listOf("shape:INVALID", "shape:UNKNOWN"), { timestamps[it] }, t0 + 30_000L))
    }

    @Test fun unfinishedProviderHydrationNeverBecomesHardUnavailableAtTimeout() {
        val h = com.lifecyclebot.engine.truth.PairHydrationState6398
        assertFalse(h.fallbackExhausted8196(emptyList()))
        assertFalse(h.fallbackExhausted8196(listOf("DEXSCREENER", "BIRDEYE")))
        assertTrue(h.fallbackExhausted8196(listOf("DEXSCREENER", "BIRDEYE", "ORACLE")))
        val now = System.currentTimeMillis()
        val t = com.lifecyclebot.data.TokenState(mint = "mint8196", symbol = "TEST")
        val state = h.resolveFromToken8196(t, false, now - 120_000L, now)
        assertEquals(com.lifecyclebot.engine.truth.PairHydrationState6398.State.PAIR_PENDING_HYDRATION, state.state)
        assertEquals("PROVIDER_ATTEMPTS_UNPROVEN_8196", state.reason)
        assertFalse(state.providerAttempted.contains("ORACLE"))
        // A positive dated Helius pool witness wins immediately even after TTL.
        t.tokenMap.poolAddress = "route-pool8196"
        t.tokenMap.hydrationComplete = true
        t.tokenMap.updatedAtMs = now
        t.tokenMap.providerTimestamps["HELIUS"] = now
        val recovered = h.resolveFromToken8196(t, false, now - 120_000L, now)
        assertEquals(com.lifecyclebot.engine.truth.PairHydrationState6398.State.PAIR_CONFIRMED, recovered.state)
        assertEquals("route-pool8196", recovered.pairAddress)
        val bot = source("engine/BotService.kt")
        assertTrue(bot.contains("PairHydrationState6398.resolveFromToken8196("))
        assertFalse(bot.contains("providersAttempted = listOf(\"DEXSCREENER\", \"BIRDEYE\", \"ORACLE\")"))
    }

    @Test fun successfulHeliusProofReopensSourcesWithoutForgingProviderHealth() {
        val h = com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
        assertTrue(h.providerRecovered8196(200, "{\"jsonrpc\":\"2.0\",\"result\":123}"))
        assertFalse(h.providerRecovered8196(429, "{\"error\":\"credits exceeded\"}"))
        assertFalse(h.providerRecovered8196(200, "{\"error\":{\"code\":-32005,\"message\":\"rate exceeded\"}}"))
        val now = System.currentTimeMillis()
        h.noteExhausted8094(now)
        assertTrue(h.exhaustedLatched8094(now + 1L))
        h.clearExhaustionOnProof8196(now + 2L)
        assertFalse(h.exhaustedLatched8094(now + 3L))
        val meter = source("network/HeliusCreditMeterInterceptor7881.kt")
        assertTrue(meter.contains("providerRecovered8196(resp.code, resp.peekBody(4_096).string())"))
        assertTrue(meter.contains("clearExhaustionOnProof8196()"))
        assertTrue(meter.indexOf("authorizeWire8397(") < meter.indexOf("clearExhaustionOnProof8196()"))
    }

    @Test fun quickFinalisedCloseUsesItsOwnImmutablePendingCortexRead() {
        val cx = com.lifecyclebot.engine.cortex.Cortex7885
        val at = 2_000_000_000_000L
        assertTrue(cx.pendingReadMatches8196(true, "LIVE", "LIVE", at - 2_000L, at, at + 5_000L, false))
        assertFalse(cx.pendingReadMatches8196(false, "LIVE", "LIVE", at - 2_000L, at, at + 5_000L, false))
        assertFalse(cx.pendingReadMatches8196(true, "PAPER", "LIVE", at - 2_000L, at, at + 5_000L, false))
        assertFalse(cx.pendingReadMatches8196(true, "LIVE", "LIVE", at + 100L, at, at + 5_000L, false))
        assertFalse(cx.pendingReadMatches8196(true, "LIVE", "LIVE", at - 1_000L, at, at - 100L, false))
        assertFalse(cx.pendingReadMatches8196(true, "LIVE", "LIVE", at - 1_000L, at, at + 20L * 60_000L, false))
        assertFalse(cx.pendingReadMatches8196(true, "LIVE", "LIVE", at - 1_000L, at, at + 5_000L, true))
        val code = source("engine/cortex/Cortex7885.kt")
        assertTrue(code.contains("REALIZED_BOUND_FROM_PENDING_DECISION_8196"))
        assertTrue(code.indexOf("val stillPending8196 =") < code.indexOf("val d = lanes.asSequence()"))
    }

    @Test fun physicalOrOutOfMandateNativeNoDoesNotGenerateExecutableCortexTrainingProof() {
        assertFalse(CortexLabelHygiene8144.executableDecision8144(false, "NATIVE_MCAP_TOO_LOW"))
        assertFalse(CortexLabelHygiene8144.executableDecision8144(false, "OUTSIDE_NATIVE_AGE"))
        assertFalse(CortexLabelHygiene8144.executableDecision8144(false, "NATIVE_SELLABILITY_REJECT"))
        // superseded by V5.0.8418 — 8372 "a specialist's scored NO is the counterfactual being measured": only decisions that
        // were actually executable grade authority; a native NO (nativeEligible=false) is not one.
        assertFalse(CortexLabelHygiene8144.executableDecision8144(false, "SCORE_TOO_LOW", false))
        assertTrue(CortexLabelHygiene8144.executableDecision8144(false, "SCORE_TOO_LOW", true))
        assertFalse(CortexLabelHygiene8144.counterfactualExecutable8372(false, "SCORE_TOO_LOW", false, false, "PRICE_NOT_FRESH"))
        assertFalse(CortexLabelHygiene8144.counterfactualExecutable8372(false, "SCORE_TOO_LOW", false, true, "MCAP_TOO_LOW"))
        // A landed authorised buy is economic truth, even if a diagnostic reason was later applied.
        assertTrue(CortexLabelHygiene8144.executableDecision8144(true, "NATIVE_MCAP_TOO_LOW"))
    }

    @Test fun chronologicalWalkForwardDoesNotBorrowRivalsFromAnotherMarketRegime() {
        val t0 = 2_000_000_000_000L
        val minute = 60_000L
        val positive = List(60) { CortexWalkForward8144.Timed8144(t0 + it * 10L * minute, 11.0) }
        val stale = List(30) { CortexWalkForward8144.Timed8144(t0 - (30 - it) * 10L * minute, 200.0) }
        val rival = List(30) { CortexWalkForward8144.Timed8144(t0 + (20L + it) * 10L * minute, 18.0) }
        assertEquals(CortexWalkForward8144.Verdict8144.VALIDATED,
            CortexWalkForward8144.promoteTimed8196(positive, listOf(stale to 1.0)).verdict)
        assertEquals(CortexWalkForward8144.Verdict8144.INVERTED_OOS,
            CortexWalkForward8144.promoteTimed8196(positive, listOf(rival to 1.0)).verdict)
        assertEquals(CortexWalkForward8144.Verdict8144.UNRESOLVED_COHORT,
            CortexWalkForward8144.promoteTimed8196(positive, emptyList(), 0.3).verdict)
    }

    @Test fun registeredNativeOpinionsPrewarmActualCortexAndFetchMissingSources() {
        val native = source("engine/SpecialistBrainBridge7542.kt")
        val cortex = source("engine/cortex/Cortex7885.kt")
        assertTrue(native.contains("registerElection8136(it);primeDecisionData8196(ts,it)"))
        assertTrue(native.contains("Cortex7885.prewarmNative8196(ts, o.lane)"))
        // 8197 widened upstream data recovery from top-two Cortex candidates
        // to every qualified specialist, with one feature-coalesced refresh.
        assertTrue(native.contains("val qualified = nativeEvidenceLanes8197(snap)"))
        assertTrue(native.contains("warmNativeLanes8417(qualified) { o ->"))
        assertTrue(native.contains("qualified.forEach { o ->"))
        assertTrue(native.contains("LanePlaybook7907.requestEvidence8153(ts, missingByFeature.values.toList(), nowMs)"))
        assertTrue(cortex.contains("fun prewarmNative8196(ts: TokenState, lane: String)"))
        assertTrue(cortex.contains("val nativeEligible8196 ="))
        assertTrue(cortex.contains("CortexLabelHygiene8144.counterfactualExecutable8372("))
        assertTrue(cortex.contains("admitted, reason, nativeEligible8196, opinion8372?.gradeable, opinion8372?.reason"))
        assertFalse(native.contains("PipelineHealthCollector.labelInc(\"SPECIALIST_UPSTREAM_EVIDENCE_FAKE_OBSERVED"))
    }

    @Test fun newReceiverInvalidatesOldFdgVerdictButRetainsHardSafetyAndRealFinality() {
        val task = source("engine/HandoffReceiverTasks8153.kt")
        val fdg = source("engine/FinalDecisionGate.kt")
        val bot = source("engine/BotService.kt")
        val native = source("engine/SpecialistOwnership7951.kt")
        val start = task.indexOf("fun openTask8153(")
        val end = task.indexOf("fun refreshTask8153(", start)
        assertTrue(start >= 0 && end > start)
        val method = task.substring(start, end)
        assertTrue(method.contains("opened8196 = it != null && it.pending && it !== prior"))
        assertTrue(method.contains("if (opened8196)"))
        assertTrue(method.contains("FinalDecisionGate.invalidateCandidate6734(mint)"))
        assertTrue(fdg.contains("SpecialistOwnership7951.onFdgVerdict7951("))
        assertTrue(native.contains("HandoffReceiverTasks8153.onFdgVerdict8153("))
        assertTrue(task.contains("fun due8153("))
        assertTrue(task.contains("fun onFdg8153("))
        // The entry spine still requires a separately sealed FDG verdict and an authoriser.
        assertTrue(bot.contains("fdgDecision7835 = moonshotFdgDecision"))
        assertTrue(bot.contains("TradeAuthorizer.authorize("))
        assertTrue(bot.contains("FinalExecutionPermit.tryAcquireExecution("))
    }

    @Test fun everySpecialistFdgEarlyOutcomeHasOneReceiverReceipt() {
        val adapter = source("engine/FdgOutcomeContinuity8197.kt")
        val bot = source("engine/BotService.kt")
        val cyclic = source("engine/CyclicTradeEngine.kt")
        val fdg = source("engine/FinalDecisionGate.kt")
        assertTrue(adapter.contains("SpecialistOwnership7951.onFdgVerdict7951("))
        assertTrue(adapter.contains("val result = FinalDecisionGate.evaluate("))
        assertTrue(adapter.contains("if (needsEarlyRecord8197(result))"))
        // 9 meme/specialist gates, one cyclic gate. The V3 trunk stays unchanged
        // and is tested separately by Aate7835ProducerWiringTest.
        assertEquals(9, Regex("FdgOutcomeContinuity8197[.]evaluate[(]").findAll(bot).count())
        assertEquals(1, Regex("FdgOutcomeContinuity8197[.]evaluate[(]").findAll(cyclic).count())
        assertTrue(bot.contains("val v3Fdg6533 = FinalDecisionGate.evaluate("))
        assertTrue(fdg.contains("return rememberFdgVerdict(fdgCacheKey, FinalDecision("))
        val blocked = FinalDecisionGate.FinalDecision(
            false, FinalDecisionGate.TradeMode.LIVE, FinalDecisionGate.ApprovalClass.BLOCKED,
            "C", 0.0, FinalDecisionGate.EdgeVerdict.SKIP, "TEST_EDGE", FinalDecisionGate.BlockLevel.EDGE,
            0.0, emptyList(), "mint8196", "TEST", "blocked", emptyList())
        assertTrue(FdgOutcomeContinuity8197.needsEarlyRecord8197(blocked))
        assertFalse(FdgOutcomeContinuity8197.needsEarlyRecord8197(blocked.copy(
            candidateVersion7835 = 1234L, canonicalLane7835 = "MOONSHOT")))
    }

    @Test fun handoffPurityRetainsOneTaskAndOneTerminalVerdict() {
        val t0 = 2_000_000_000_000L
        val created = HandoffReceiverTasks8153.open8153(
            null, "mint8196", "MOONSHOT", "CORE", "YIELD", 100L, t0, t0
        )!!
        val deduped = HandoffReceiverTasks8153.open8153(
            created, "mint8196", "MOONSHOT", "CORE", "YIELD", 100L, t0 + 10L, t0 + 10L
        )
        assertTrue(created === deduped)
        val scheduled = HandoffReceiverTasks8153.scheduled8153(created, "MOONSHOT", t0 + 1000L)
        val evaluated = HandoffReceiverTasks8153.onFdg8153(
            scheduled, "MOONSHOT", true, null, t0 + 1200L, 100L
        )
        assertEquals(HandoffReceiverTasks8153.Outcome8153.EVALUATED, evaluated.outcome)
        assertEquals("FDG_ALLOW", evaluated.verdict)
        assertFalse(evaluated.pending)
        assertFalse(HandoffReceiverTasks8153.due8153(evaluated, "MOONSHOT", t0 + 1300L, 100L))
    }
}
