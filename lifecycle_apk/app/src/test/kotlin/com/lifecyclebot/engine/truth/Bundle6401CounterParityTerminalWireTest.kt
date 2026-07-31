package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6401 P1 — CanonicalEntryPipeline6398.decide MUST record every
 * terminal outcome into CounterParityLedger6399. Prior to this fix,
 * only live tickets landed in the ledger; blocks / defers / holds were
 * silent, breaking FDG_TOTAL = ALLOW_LIVE + ALLOW_SHADOW + BLOCK_* + DEFER_*.
 */
class Bundle6401CounterParityTerminalWireTest {

    @Before fun setUp() { CounterParityLedger6399.clearAllForTest() }
    @After fun tearDown() { CounterParityLedger6399.clearAllForTest() }

    private fun scoreEnv(
        mint: String = "MINT_ABCDEFGH12",
        effectiveScore: Double = 20.0,
        hardSafetyPassed: Boolean = true,
        hardSafetyReasons: List<String> = emptyList(),
        evidenceCompleteness: Double = 1.0,
    ) = EntryScoreEnvelope6398(
        evaluationId = "EVAL_${mint.take(6)}_${effectiveScore.toInt()}",
        mint = mint, symbol = "SYM",
        lane = TraderLane.SHITCOIN, trader = TraderId.MEME,
        tactic = EntryTactic.STANDARD,
        lifecycleStage = LifecycleStage.GATE,
        sourceSet = setOf(DiscoverySource.PUMPFUN),
        rawScore = effectiveScore,
        effectiveScore = effectiveScore,
        confidence = 1.0,
        evidenceCompleteness = evidenceCompleteness,
        componentScores = emptyMap(),
        softPenalties = emptyMap(),
        hardSafetyPassed = hardSafetyPassed,
        hardSafetyReasons = hardSafetyReasons,
        dataVersion = 1L,
        scoreModelVersion = "6398",
    )

    private fun floorEnv(effectiveFloor: Double = 15.0) = DynamicFloorEnvelope6398(
        evaluationId = "EVAL", lane = TraderLane.SHITCOIN,
        trader = TraderId.MEME, tactic = EntryTactic.STANDARD,
        baseLaneFloor = 15.0, governorDelta = 0.0, lanePerformanceDelta = 0.0,
        traderPerformanceDelta = 0.0, tacticPerformanceDelta = 0.0,
        lifecycleDelta = 0.0, regimeDelta = 0.0, personalityDelta = 0.0,
        evidenceDelta = 0.0, gateRelaxerDelta = 0.0, warmupDelta = 0.0,
        effectiveFloor = effectiveFloor, floorModelVersion = "6398",
    )

    @Test fun allow_terminal_records_fdg_allow_live() {
        CanonicalEntryPipeline6398.decide(scoreEnv(effectiveScore = 20.0), floorEnv(15.0))
        assertEquals(1L, CounterParityLedger6399.fdgCount(FdgTerminalOutcome6399.FDG_ALLOW_LIVE))
        assertEquals(1L, CounterParityLedger6399.fdgTotal())
    }

    @Test fun score_below_floor_records_block_score() {
        CanonicalEntryPipeline6398.decide(scoreEnv(effectiveScore = 5.0), floorEnv(15.0))
        assertEquals(1L, CounterParityLedger6399.fdgCount(FdgTerminalOutcome6399.FDG_BLOCK_SCORE))
    }

    @Test fun hard_safety_veto_records_block_hard_safety() {
        CanonicalEntryPipeline6398.decide(
            scoreEnv(hardSafetyPassed = false, hardSafetyReasons = listOf("HONEYPOT")),
            floorEnv(15.0),
        )
        assertEquals(1L, CounterParityLedger6399.fdgCount(FdgTerminalOutcome6399.FDG_BLOCK_HARD_SAFETY))
    }

    @Test fun evidence_incomplete_records_defer_hydration() {
        CanonicalEntryPipeline6398.decide(
            scoreEnv(evidenceCompleteness = 0.1), floorEnv(15.0),
        )
        assertEquals(1L, CounterParityLedger6399.fdgCount(FdgTerminalOutcome6399.FDG_DEFER_HYDRATION))
    }

    @Test fun intake_block_records_defer_hydration() {
        CanonicalEntryPipeline6398.decide(
            scoreEnv(), floorEnv(15.0), hydrationHardUnavailable = true,
        )
        assertEquals(1L, CounterParityLedger6399.fdgCount(FdgTerminalOutcome6399.FDG_DEFER_HYDRATION))
    }

    @Test fun parity_holds_for_pure_decide_stream_with_no_tickets() {
        repeat(6) {
            CanonicalEntryPipeline6398.decide(scoreEnv(effectiveScore = 1.0), floorEnv(30.0))
        }
        val report = CounterParityLedger6399.checkParity()
        assertTrue(report.violations.joinToString(","), report.ok)
    }

    @Test fun total_equals_sum_of_terminals() {
        CanonicalEntryPipeline6398.decide(scoreEnv(effectiveScore = 20.0), floorEnv(15.0)) // ALLOW
        CanonicalEntryPipeline6398.decide(scoreEnv(effectiveScore = 5.0),  floorEnv(15.0)) // BLOCK_SCORE
        CanonicalEntryPipeline6398.decide(
            scoreEnv(hardSafetyPassed = false, hardSafetyReasons = listOf("R")), floorEnv(15.0),
        )                                                                                  // BLOCK_HARD_SAFETY
        assertEquals(3L, CounterParityLedger6399.fdgTotal())
    }
}
