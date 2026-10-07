package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450
import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
import com.lifecyclebot.engine.truth.LearnedAdmissionAuthority6846
import com.lifecyclebot.engine.truth.PredictiveEntryOracle6915
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class Aate7835AuthorityContinuityTest {
    @Before fun reset() {
        ExecutableOpenGate.resetForTests()
        ExecutionDecisionSnapshot6510.resetForTest()
        LaneExecutionCoordinator.resetForTests()
        TradeAuthorizer.reset()
        RuntimeConfigOverlay.resetForTests()
        mode(true)
    }

    @After fun cleanup() { mode(true); ExecutableOpenGate.resetForTests(); ExecutionDecisionSnapshot6510.resetForTest() }

    private fun mode(paper: Boolean) {
        RuntimeModeAuthority.publishConfig(paper, true)
        RuntimeModeAuthority.publishUiMode(paper)
        RuntimeModeAuthority.publishExecutorMode(paper)
        RuntimeModeAuthority.publishPipelineMode(paper)
    }

    private fun decision(mint: String, lane: String, version: Long = 1234L) = FinalDecisionGate.FinalDecision(
        shouldTrade = true, mode = FinalDecisionGate.TradeMode.PAPER,
        approvalClass = FinalDecisionGate.ApprovalClass.PAPER_BENCHMARK,
        quality = "A", confidence = 80.0, edge = FinalDecisionGate.EdgeVerdict.STRONG,
        blockReason = null, blockLevel = null, sizeSol = 0.08, tags = emptyList(),
        mint = mint, symbol = "TEST", approvalReason = "positive setup", gateChecks = emptyList(),
        effectiveEntryScore7687 = 80, candidateVersion7835 = version, canonicalLane7835 = lane,
    )

    @Test fun post_fdg_size_rewrite_cannot_shrink_the_sealed_ticket_7853() {
        val mint = "Authority7853_SIZE"
        val ts = TokenState(mint, symbol = "TEST", lastLiquidityUsd = 100_000.0,
            safety = SafetyReport(rugcheckScore = 90))
        val d = decision(mint, "MOONSHOT", 7853L)
        // A quiet-hour 0.35x or 0.01 SOL bridge figure after the verdict is evidence only.
        val intent = SpecialistPreauthSeal7834.ensure(ts, d, "MOONSHOT", 0.01)
        assertNotNull(intent)
        assertEquals(d.sizeSol, intent!!.resolvedSize, 1e-9)
    }

    @Test fun bot_service_passes_fdg_size_unshaped_to_authorizer_7853() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("val actualInitialSizeForAuth6649 = fdgDecision.sizeSol"))
        assertFalse(bot.contains("modeConf?.let { finalSizeForAuth6649 *= it.positionSizeMultiplier }"))
        assertFalse(bot.contains("executor.graduatedInitialSize(finalSizeForAuth6649"))
        val seal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SpecialistPreauthSeal7834.kt").readText()
        assertFalse(seal.contains("minOf(decision.sizeSol, maximumSizeSol)"))
    }

    @Test fun every_specialist_seals_the_exact_decision_and_size() {
        val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
            "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")
        lanes.forEachIndexed { i, lane ->
            val mint = "Authority7835_${lane}"
            val ts = TokenState(mint, symbol = "TEST", lastLiquidityUsd = 100_000.0,
                safety = SafetyReport(rugcheckScore = 90))
            val d = decision(mint, lane, 2000L + i)
            val intent = SpecialistPreauthSeal7834.ensure(ts, d, lane, 0.10)
            assertNotNull(lane, intent)
            assertEquals(lane, intent!!.canonicalLane)
            assertEquals(d.candidateVersion7835, intent.candidateVersion)
            assertEquals(0.08, intent.resolvedSize, 1e-9)
            assertSame(intent, ExecutableOpenGate.activeExecutionIntent6519("PAPER", mint, d.candidateVersion7835))
            assertSame(intent, ExecutableOpenGate.ticketForAttempt(intent.attemptId))
        }
    }

    @Test fun a_different_lane_mode_or_version_cannot_borrow_authority() {
        val d = decision("M7835", "QUALITY")
        assertEquals("FDG_LANE_MISMATCH_7835", SpecialistPreauthSeal7834.refusal(d, d.mint, "MOONSHOT", true))
        assertEquals("FDG_MODE_MISMATCH_7835", SpecialistPreauthSeal7834.refusal(d, d.mint, "QUALITY", false))
        val ts = TokenState(d.mint, lastLiquidityUsd = 100_000.0, safety = SafetyReport(rugcheckScore = 90))
        assertNotNull(SpecialistPreauthSeal7834.ensure(ts, d, "QUALITY", 0.08))
        assertNull(ExecutableOpenGate.activeExecutionIntent6519("PAPER", d.mint, 1235L))
        assertNull(ExecutableOpenGate.activeExecutionIntent6519("LIVE", d.mint, 1234L))
    }

    @Test fun refusal_or_missing_FDG_cannot_be_promoted_by_authorizer() {
        val d = decision("M7835", "QUALITY").copy(shouldTrade = false, blockReason = "NEGATIVE_NET_EV")
        assertEquals("NEGATIVE_NET_EV", SpecialistPreauthSeal7834.refusal(d, d.mint, "QUALITY", true))
        val auth = TradeAuthorizer.authorize(mint = d.mint, symbol = "TEST", score = 90,
            confidence = 90.0, quality = "A", isPaperMode = true,
            requestedBook = TradeAuthorizer.ExecutionBook.QUALITY, preResolvedSizeSol = 0.10,
            fdgDecision7835 = d, tokenState7835 = TokenState(d.mint))
        assertFalse(auth.isExecutable())
        assertNull(auth.executionIntent7835)
        assertEquals("NEGATIVE_NET_EV", auth.reason)
    }

    @Test fun authorization_carries_the_same_lane_version_and_size_through_finality() {
        com.lifecyclebot.engine.truth.PaperAccountLedger6430.resetForTest()
        com.lifecyclebot.engine.truth.PaperAccountLedger6430.initialize(10.0)
        ExecutableEntryAuthority6450.resetForTest6487()
        ToxicModeCircuitBreaker.resetForTests()
        val mint = "Auth7835Positive"
        val ts = TokenState(mint, symbol = "TEST", lastLiquidityUsd = 100_000.0,
            lastMcap = 10_000_000.0, lastPrice = 0.01, lastPriceUpdate = System.currentTimeMillis(),
            lastPriceSource = "DEXSCREENER", lastPricePoolAddr = "Pool7835", source = "DEXSCREENER",
            safety = SafetyReport(rugcheckScore = 90))
        val sealedSol = maxOf(0.08, com.lifecyclebot.engine.truth.OrderSizeResolver6441.paperExecutableMinimumSol() + 0.01)
        val d = decision(mint, "CORE", LaneExecutionCoordinator.candidateVersionFor(mint)).copy(sizeSol = sealedSol)
        val result = TradeAuthorizer.authorize(mint, "TEST", 80, 80.0, "A", true,
            TradeAuthorizer.ExecutionBook.CORE, rugcheckScore = 90, liquidity = 100_000.0,
            preResolvedSizeSol = sealedSol, fdgDecision7835 = d, tokenState7835 = ts)
        assertTrue(result.reason, result.isExecutable())
        val intent = requireNotNull(result.executionIntent7835)
        assertEquals("CORE", intent.canonicalLane)
        assertEquals(d.candidateVersion7835, intent.candidateVersion)
        assertEquals(sealedSol, intent.resolvedSize, 1e-9)
        assertEquals(intent.attemptId, result.attemptId)
        assertSame(intent, ExecutableOpenGate.ticketForAttempt(result.attemptId))
        assertEquals(10.0, com.lifecyclebot.engine.truth.PaperAccountLedger6430.cashSol(), 1e-9)
    }

    @Test fun rejected_record_cannot_fabricate_a_fallback_intent() {
        val mint = "Denied7835"
        ExecutableOpenGate.recordEntryAuthority6487(mint, 1234L,
            ExecutableEntryAuthority6450.Decision(ExecutableEntryAuthority6450.Verdict.DENY_LEARNED_NEGATIVE_6846, 0.0, "negative"))
        val intent = ExecutableOpenGate.recordFdgAndGetIntent6533(mint, "TEST", "QUALITY", true, null,
            rugScore = 90, safetyTier = "SAFE", liquidityUsd = 100_000.0,
            candidateVersion = 1234L, entryScore = 80, resolvedSizeSol6558 = 0.08)
        assertNull(intent)
        assertNull(ExecutableOpenGate.activeExecutionIntent6519("PAPER", mint, 1234L))
    }

    @Test fun new_hard_safety_fact_revokes_the_same_version_buy() {
        val ts = TokenState("Unsafe7835", lastLiquidityUsd = 100_000.0, safety = SafetyReport(rugcheckScore = 90))
        val d = decision(ts.mint, "QUALITY")
        val before = SpecialistPreauthSeal7834.ensure(ts, d, "QUALITY", 0.08)
        assertNotNull(before)
        ts.safety = SafetyReport(rugcheckScore = 0, hardBlockReasons = listOf("CONFIRMED_RUG"))
        assertNull(SpecialistPreauthSeal7834.ensure(ts, d, "QUALITY", 0.08))
        assertNull(ExecutableOpenGate.ticketForAttempt(before!!.attemptId))
    }

    @Test fun live_nonpositive_expectancy_cannot_be_admitted_by_oracle_label() {
        mode(false)
        for (ev in listOf(-0.05, 0.0, Double.NaN)) {
            val inputs = LearnedAdmissionAuthority6846.Inputs(
                lane = "QUALITY", mint = "Ev7835", requestedSizeSol = 0.08,
                scoreBand = 80, regime = "NORMAL", livePWin = 0.80, expectedPnl = ev,
                cohortSample = 0, oracleVerdict6915 = PredictiveEntryOracle6915.Verdict.ADMIT,
                laneWrPct = 80.0, laneLossRatePct = 20.0, sourceFamily = "TEST",
                sourcePWin = 0.80, sourceExpectedPnl = 0.10, sourceSample = 0,
                policyHardBlock = false, brainSoftBlock = false, losingPatternMatch = false,
                laneCapitalUsedSol = 0.0, laneCapitalTargetSol = 1.0, laneCapitalReservedSol = 0.0,
                minExecutableSol = 0.04, probeSizeSol = 0.04,
            )
            val result = LearnedAdmissionAuthority6846.evaluate(inputs)
            assertEquals(LearnedAdmissionAuthority6846.Verdict.DENY, result.verdict)
            assertEquals("LIVE_NON_POSITIVE_EXPECTANCY_7828", result.denyCategory)
        }
    }
    @Test fun fdg_cache_never_substitutes_another_lane_size_or_changed_evidence() {
        val ts = TokenState("Cache7835")
        val c = com.lifecyclebot.data.CandidateDecision(
            entryScore = 80.0, exitScore = 0.0, phase = "BREAKOUT", signal = "BUY",
            setupQuality = "A", edgeQuality = "A", finalQuality = "A", edgePhase = "EXPANSION",
            edgeConfidence = 80.0, isOptimalEntry = true, edgeVeto = false, shouldTrade = true,
            finalSignal = "BUY", blockReason = "", qualityPenalty = 1.0, aiConfidence = 80.0,
            meta = com.lifecyclebot.data.StrategyMeta(),
        )
        fun key(lane: String, size: Double, candidate: com.lifecyclebot.data.CandidateDecision = c) =
            FinalDecisionGate.fdgCacheKey(ts, candidate, lane, "BUY", 80.0, 1234L, size)
        val original = key("QUALITY", 0.08)
        assertEquals(original, key("QUALITY", 0.08))
        assertNotEquals(original, key("MOONSHOT", 0.08))
        assertNotEquals(original, key("QUALITY", 0.04))
        assertNotEquals(original, key("QUALITY", 0.08, c.copy(edgeVeto = true)))
        assertNotEquals(original, key("QUALITY", 0.08, c.copy(qualityPenalty = 0.25)))
    }

}
