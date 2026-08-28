package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V5_0_6568AcceptanceTest {
    @Test fun meme_entry_policy_and_tactic_rewards_are_causal() {
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val tactic = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(exec.contains("entryTactic=$" + "electedTactic6568"))
        assertTrue(exec.contains("brainConsensus=$" + "brainVerdict6568") && exec.contains("policyPWin=$" + "{policyPWin6568.fmt(3)}"))
        assertTrue(exec.contains("LIVE_ENTRY_POLICY_SNAPSHOT_CANONICAL_6568") && exec.contains("entryThresholdSnapshot = ts.position.entryPolicySnapshot"))
        assertTrue(tactic.contains("TACTIC_HISTORICAL_OUTCOME_ATTRIBUTED_6568") && tactic.contains("if (elected.name == current) onTradeClosed"))
        assertFalse(tactic.contains("entered.isBlank() || entered == current"))
        assertTrue(bot.contains("authoritativePolicyPositive6568") && bot.contains("NEGATIVE_CONSENSUS_NORMAL_BUY_SUPPRESSED_6568"))
        assertTrue(bot.contains("liqOk && authoritativePolicyPositive6568"))
    }

    @Test fun hundred_close_replay_populates_exact_entry_cohorts_and_shaping() {
        val causal = com.lifecyclebot.engine.truth.MemeCausalLearning6568
        causal.resetForTest()
        val before = com.lifecyclebot.engine.learning.TacticSwitcher.historicalOutcomeCount6486()
        repeat(100) { n ->
            val pid = "6568-replay-$n-${System.nanoTime()}"
            val tactic = if (n < 25) "PULLBACK" else "MOMENTUM"
            com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.setEntry(
                com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot(
                    positionId=pid,mint="mint$n",entryLane="MOONSHOT",entryStrategyPid="p",entryTactic=tactic,
                    entryRiskProfile="r",entryExitProfile="e",entrySource="TEST",entryScore=55,entryLiquiditySol=0.0,
                    entryMarketCapUsd=10000.0,entryTimestampMs=n.toLong(),entryThresholdSnapshot="policyPWin=0.2",
                    entryPolicySnapshotId="$pid:6568",entryTacticVersion="6568",entryLiquidityUsd=5000.0,
                    entryVolumeVelocity=30.0,entryBuyPressurePct=45.0,entrySellPressurePct=55.0,
                    policyProbability=0.2,forwardPWin=0.2,authorizationReason="REPLAY_TEST"
                )
            )
            val pnl = if (n % 10 == 0) 10.0 else -5.0
            val env = com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope(
                tradeId=pid,positionId=pid,atMs=n.toLong(),realizedPnlSol=pnl/1000.0,realizedReturnPct=pnl,
                mint="mint$n",lane="MOONSHOT",mode="paper",entryScore=55,entryTactic=tactic,learningEligible=true
            )
            assertTrue(causal.record(env))
            com.lifecyclebot.engine.learning.TacticSwitcher.onCanonicalTradeClosed6486("MOONSHOT","S41-60",tactic,pnl)
        }
        assertTrue(causal.rowCountForTest() == 100)
        assertTrue(com.lifecyclebot.engine.learning.TacticSwitcher.historicalOutcomeCount6486() >= before + 100)
        assertTrue(causal.sizeMultiplier("MOONSHOT","MOMENTUM") in 0.20..0.70)
    }

    @Test fun remaining_6568_integrity_and_causal_contracts_are_wired() {
        val advisor=File("src/main/kotlin/com/lifecyclebot/engine/truth/AutoPipelineAdvisor6462.kt").readText()
        val journal=File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        val bridge=File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val snap=File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        val sizer=File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
        assertTrue(advisor.contains("ADVISOR_INTEGRITY_DIAGNOSTIC_ONLY_6568"))
        assertFalse(advisor.contains("ADVISOR_R2_REPLAY_COOLDOWN_EXTEND_6507"))
        assertTrue(journal.contains("JOURNAL_STRATEGY_LEARNING_QUARANTINED_6568"))
        assertTrue(bridge.contains("MemeCausalLearning6568") && bridge.contains("DamageControlGate.noteOutcome"))
        assertTrue(snap.contains("MEME_WINNER_LOSER_CAUSAL_REPORT_6568") && snap.contains("entryPolicySnapshotId") && snap.contains("brainConsensusVerdict") && snap.contains("specialistContributions"))
        assertTrue(sizer.contains("MEME_CAUSAL_PERFORMANCE_SHAPED_6568") && sizer.contains("causalMult6568"))
    }

}
