package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
import com.lifecyclebot.engine.truth.ExecutionSnapshotAuthority6496
import com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Repair6512ExecutionAuthorityTest {
    private fun reset(paper: Boolean = true) {
        RuntimeConfigOverlay.resetForTests()
        ExecutableOpenGate.resetForTests()
        LaneExecutionCoordinator.resetForTests()
        ExecutionDecisionSnapshot6510.resetForTest()
        ExecutionSnapshotAuthority6496.resetForTest()
        ExecutableEntryAuthority6450.resetForTest6487()
        ToxicModeCircuitBreaker.resetForTests()
        BirdeyeBudgetGate.resetForTests()
        FinalExecutionPermit.resetForTests()
        LiveSafetyCircuitBreaker.reset()
        RuntimeModeAuthority.publishConfig(paperMode = paper, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(paper)
        RuntimeModeAuthority.publishExecutorMode(paper)
        RuntimeModeAuthority.publishPipelineMode(paper)
    }

    @Test fun same_version_buy_then_watch_preserves_complete_buy_tuple() {
        reset(); val mint = "RaceWatch6512${System.nanoTime()}"; val cv = 12L
        ExecutableOpenGate.recordFdg(mint, "A", "PROJECT_SNIPER", true, "A_OK", signal = "BUY", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 4000.0, preFdgVerdict = "BUY", candidateVersion = cv, entryScore = 88)
        ExecutableOpenGate.recordFdg(mint, "B", "EXPRESS", false, "B_WATCH", signal = "WATCH", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 4000.0, preFdgVerdict = "WATCH", candidateVersion = cv, entryScore = 40)
        val elected = requireNotNull(ExecutionDecisionSnapshot6510.get(mint, cv, "PROJECT_SNIPER"))
        assertEquals("BUY", elected.verdict)
        assertEquals("PROJECT_SNIPER", elected.executionLane)
        assertNull(ExecutionDecisionSnapshot6510.get(mint, cv, "EXPRESS"))
    }

    @Test fun same_version_buy_then_buy_keeps_one_elected_lane() {
        reset(); val mint = "RaceBuy6512${System.nanoTime()}"; val cv = 13L
        ExecutableOpenGate.recordFdg(mint, "A", "SHITCOIN", true, null, signal = "BUY", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 4000.0, preFdgVerdict = "BUY", candidateVersion = cv)
        ExecutableOpenGate.recordFdg(mint, "B", "EXPRESS", true, null, signal = "BUY", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 4000.0, preFdgVerdict = "BUY", candidateVersion = cv)
        assertNotNull(ExecutionDecisionSnapshot6510.get(mint, cv, "SHITCOIN"))
        assertNull(ExecutionDecisionSnapshot6510.get(mint, cv, "EXPRESS"))
    }

    @Test fun lane_rebind_does_not_manufacture_occupancy_drift() {
        reset(); val mint = "Occupancy6512${System.nanoTime()}"
        ExecutionSnapshotAuthority6496.record(mint, "SHITCOIN", "SAFE", "PAPER:$mint", 0.05)
        assertNull(ExecutionSnapshotAuthority6496.matchOrDriftReason(mint, "EXPRESS", "SAFE", "PAPER:$mint", 0.05))
    }

    @Test fun approved_fdg_with_unknown_or_watch_raw_signal_creates_ticket() {
        listOf("UNKNOWN", "WATCH").forEachIndexed { i, raw ->
            reset(); val mint = "Raw${raw}6512${System.nanoTime()}"; val lane = "SHITCOIN"
            val cv = LaneExecutionCoordinator.candidateVersionFor(mint)
            ExecutableOpenGate.recordEntryAuthority6487(mint, cv, ExecutableEntryAuthority6450.gate(lane, mint, 1.0))
            ExecutableOpenGate.recordFdg(mint, "R$i", lane, true, null, signal = raw, rugScore = 90, safetyTier = "SAFE", liquidityUsd = 3500.0, preFdgVerdict = "BUY", candidateVersion = cv)
            val verdict = ExecutableOpenGate.canOpenExecutablePosition(mint, "R$i", 90, "PAPER", lane, "test.6512.$raw", liveLiquidityUsd = 3500.0, liveSafetyTier = "SAFE", preResolvedSizeSol6490 = 0.05)
            assertTrue("sealed FDG authority must survive raw $raw reason=${verdict.reason} active=${ExecutableOpenGate.activeExecutionIntent6519("PAPER", mint, cv)}", verdict.allowed)
            assertNotNull(ExecutableOpenGate.ticketForAttempt(verdict.attemptId))
        }
    }

    @Test fun provider_rotation_is_real_and_optional_providers_are_late() {
        val agg = File("src/main/kotlin/com/lifecyclebot/perps/PriceAggregator.kt").readText()
        val dex = File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        val quorum = File("src/main/kotlin/com/lifecyclebot/engine/LiveProviderQuorum.kt").readText()
        assertTrue(agg.contains("https://api.dexpaprika.com/networks/solana/tokens/"))
        assertTrue(agg.contains("https://data-api.binance.vision/api/v3/ticker/24hr"))
        assertTrue(agg.indexOf("DataSource.DEXPAPRIKA") < agg.indexOf("DataSource.DEXSCREENER"))
        assertTrue(dex.contains("fetchDexPaprikaToken6512") && dex.contains("baseTokenAddress = mint"))
        assertTrue(quorum.contains("DEXSCREENER_ENRICHMENT") && quorum.contains("JUPITER_ROUTE") && quorum.contains("HELIUS_ONCHAIN"))
    }

    @Test fun sealed_envelope_links_position_and_credits_real_learners_once() {
        reset(); com.lifecyclebot.engine.truth.AateDecisionFabric6512.resetForTest()
        val mint = "Reward6512${System.nanoTime()}"; val lane = "PROJECT_SNIPER"; val cv = 88L
        val signals = UnifiedPolicyHead.Signals(0.7, 0.8, 0.7, 0.65, 0.75, 0.8)
        UnifiedPolicyHead.stamp(mint, lane, signals)
        AutonomousMetaPolicy.stampDecision(mint, lane, 82, "NORMAL")
        StrategyHypothesisEngine.getSizeBias(lane, 82, "NORMAL", mint)
        val uphBefore = UnifiedPolicyHead.trainedCount()
        val metaBefore = AutonomousMetaPolicy.totalUpdateCount6512()
        val hypoBefore = StrategyHypothesisEngine.outcomeUpdateCount6512()
        val graphBefore = SemanticPatternGraph.nodeCount6512()
        val context = com.lifecyclebot.engine.truth.AateStrategyContext6512("$mint:$cv", BotRuntimeController.currentGeneration(), "PAPER", mint, "RWD", cv, lane, "TEST", "NORMAL")
        com.lifecyclebot.engine.truth.AateDecisionFabric6512.record(
            com.lifecyclebot.engine.truth.PolicySynthesizer6512.synthesize(
                context, "BUY", 80.0, 86.0, 0.05, 0.07, "RUNNER",
                emptyList(), listOf(com.lifecyclebot.engine.truth.AateBrainContribution6512("UnifiedPolicyHead", "CONTRIBUTOR", 0.8, 0.4, pWin = 0.75, expectedPnlPct = 18.0)), "LEARNED",
            )
        )
        assertNotNull(com.lifecyclebot.engine.truth.AateDecisionFabric6512.sealForExecution("attempt-$cv", "PAPER", mint, cv, lane))
        assertTrue(com.lifecyclebot.engine.truth.AateDecisionFabric6512.attachPosition("position-$cv", "paper", mint, lane))
        val env = com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "trade-$cv", positionId = "position-$cv", atMs = System.currentTimeMillis(),
            realizedPnlSol = 0.02, realizedReturnPct = 40.0, mint = mint, lane = lane,
            mode = "paper", proofState = "SELL_CONFIRMED", holdingTimeMs = 60_000L,
            entryScore = 82, entryTactic = "RUNNER",
        )
        assertTrue(com.lifecyclebot.engine.truth.AateDecisionFabric6512.onFinalized(env))
        assertTrue(UnifiedPolicyHead.trainedCount() > uphBefore)
        assertTrue(AutonomousMetaPolicy.totalUpdateCount6512() > metaBefore)
        assertTrue(StrategyHypothesisEngine.outcomeUpdateCount6512() > hypoBefore)
        assertTrue(SemanticPatternGraph.nodeCount6512() > graphBefore)
        assertTrue(com.lifecyclebot.engine.truth.AateDecisionFabric6512.onFinalized(env))
        assertTrue(com.lifecyclebot.engine.truth.AateDecisionFabric6512.statusLine().contains("rewards=1"))
    }

    @Test fun canonical_exit_feed_replaces_mutable_open_filter_without_fake_marks() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("canonicalExitTokenSnapshot6512()"))
        assertTrue(bot.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(bot.contains("CANONICAL_EXIT_FEED_6512"))
        assertFalse(bot.contains("status.tokens.values.filter { it.position.isOpen"))
        assertTrue(bot.contains("missingMark") && bot.contains("entryPrice <= 0.0") && bot.contains("lastPrice <= 0.0"))
    }

}
