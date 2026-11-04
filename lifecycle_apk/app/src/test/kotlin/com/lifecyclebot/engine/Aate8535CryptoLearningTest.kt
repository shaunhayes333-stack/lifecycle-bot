package com.lifecyclebot.engine

import com.lifecyclebot.perps.CryptoCandidateRevision8535
import com.lifecyclebot.perps.CryptoStrategyCandidateBooks7803
import com.lifecyclebot.perps.CryptoStrategyCandidateBooks7803.State
import com.lifecyclebot.perps.crypto.brain.CryptoFluidLearning
import com.lifecyclebot.perps.crypto.brain.CryptoLosingPatternMemory
import com.lifecyclebot.perps.crypto.brain.CryptoOutcome8535
import com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher
import com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.Tactic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8535 — audit C06 (READY demoted by WATCH), C07 (clock-bucket versions), C08 (scratch denominator), C09 (tactic attribution), C10 (win definitions). */
class Aate8535CryptoLearningTest {

    private fun entry(state: State, version: Long, updatedAtMs: Long) = CryptoStrategyCandidateBooks7803.Entry(
        assetKey = "a", symbol = "A", strategy = "S", candidateVersion = version, state = state, score = 70, confidence = 60,
        conviction = 66.0, reason = "r", firstSeenMs = 0L, updatedAtMs = updatedAtMs,
    )

    // ── C06 ──

    @Test fun a_watch_or_qualify_of_the_same_revision_cannot_demote_a_live_ready() {
        val ready = entry(State.READY, 77L, 1_000L)
        assertEquals(State.READY to 77L, CryptoStrategyCandidateBooks7803.transition8535(ready, State.WATCHING, 0L, 2_000L))
        assertEquals(State.READY to 77L, CryptoStrategyCandidateBooks7803.transition8535(ready, State.QUALIFIED, 77L, 2_000L))
        // a different revision (the inputs changed) applies; an expired READY applies; LOST always applies
        assertEquals(State.QUALIFIED to 78L, CryptoStrategyCandidateBooks7803.transition8535(ready, State.QUALIFIED, 78L, 2_000L))
        assertEquals(State.WATCHING to 77L, CryptoStrategyCandidateBooks7803.transition8535(ready, State.WATCHING, 0L, 1_000L + 46_000L))
        assertEquals(State.LOST to 77L, CryptoStrategyCandidateBooks7803.transition8535(ready, State.LOST, 0L, 2_000L))
        assertEquals(State.READY to 79L, CryptoStrategyCandidateBooks7803.transition8535(ready, State.READY, 79L, 2_000L))
        assertEquals(State.WATCHING to 0L, CryptoStrategyCandidateBooks7803.transition8535(null, State.WATCHING, 0L, 2_000L))
        val qualified = entry(State.QUALIFIED, 77L, 1_000L)
        assertEquals(State.WATCHING to 77L, CryptoStrategyCandidateBooks7803.transition8535(qualified, State.WATCHING, 0L, 2_000L))
    }

    // ── C07 ──

    @Test fun a_candidate_revision_follows_its_inputs_not_the_clock() {
        val a = CryptoCandidateRevision8535.of("asset", "MOMENTUM", 1_700_000_000_000L, 70, 60, "reason")
        val b = CryptoCandidateRevision8535.of("asset", "MOMENTUM", 1_700_000_000_000L, 70, 60, "reason")
        assertEquals(a, b)
        assertTrue(a > 0L)
        assertNotEquals(a, CryptoCandidateRevision8535.of("asset", "MOMENTUM", 1_700_000_000_000L, 71, 60, "reason"))
        assertNotEquals(a, CryptoCandidateRevision8535.of("asset", "PULLBACK", 1_700_000_000_000L, 70, 60, "reason"))
        assertNotEquals(CryptoCandidateRevision8535.of("x", 1.0), CryptoCandidateRevision8535.of("x", 1.5))
        assertEquals(CryptoCandidateRevision8535.of("x", listOf("a", "b")), CryptoCandidateRevision8535.of("x", listOf("a", "b")))
    }

    // ── C08 ──

    @Test fun scratches_do_not_enter_the_trainable_mean_denominator_or_numerator() {
        val tier = "T8535C08"
        CryptoLosingPatternMemory.record(tier, 65, -4.0)
        repeat(10) { CryptoLosingPatternMemory.record(tier, 65, -0.9) }
        val s = CryptoLosingPatternMemory.snapshot8535(tier, 65)!!
        assertEquals(0.0, s[0], 0.0); assertEquals(1.0, s[1], 0.0); assertEquals(10.0, s[2], 0.0)
        assertEquals("trainable mean is the one loss", -4.0, s[3], 1e-9)
        assertEquals("mean over every settled close", -13.0 / 11.0, s[4], 1e-9)
        assertEquals(1.0, CryptoLosingPatternMemory.recommendedSizeMult(tier, 65), 0.0)   // n=1 is no danger
    }

    // ── C09 ──

    @Test fun an_outcome_trains_only_the_tactic_it_was_entered_under() {
        assertTrue(CryptoTacticSwitcher.attributable8535(Tactic.MOMENTUM, null))
        assertTrue(CryptoTacticSwitcher.attributable8535(Tactic.MOMENTUM, Tactic.MOMENTUM))
        assertFalse(CryptoTacticSwitcher.attributable8535(Tactic.MOMENTUM, Tactic.PULLBACK))
        val tier = "T8535C09"
        assertEquals(Tactic.MOMENTUM, CryptoTacticSwitcher.activeTacticFor(tier, 65))
        // six losses from positions entered under PULLBACK do not rotate a cell that holds MOMENTUM
        repeat(6) { CryptoTacticSwitcher.onOutcome(tier, 65, false, Tactic.PULLBACK) }
        assertEquals(Tactic.MOMENTUM, CryptoTacticSwitcher.activeTacticFor(tier, 65))
        // six attributed MOMENTUM losses do
        repeat(6) { CryptoTacticSwitcher.onOutcome(tier, 65, false, Tactic.MOMENTUM) }
        assertEquals(Tactic.PULLBACK, CryptoTacticSwitcher.activeTacticFor(tier, 65))
        // and a late MOMENTUM loss arriving after the rotation does not reset or rotate the new tactic
        CryptoTacticSwitcher.onOutcome(tier, 65, false, Tactic.MOMENTUM)
        assertEquals(Tactic.PULLBACK, CryptoTacticSwitcher.activeTacticFor(tier, 65))
    }

    // ── C10 ──

    @Test fun one_outcome_definition_and_maturity_on_settled_samples() {
        assertEquals(CryptoOutcome8535.Kind.NET_WIN, CryptoOutcome8535.classify(1.0))
        assertEquals(CryptoOutcome8535.Kind.NET_LOSS, CryptoOutcome8535.classify(-1.0))
        assertEquals(CryptoOutcome8535.Kind.SCRATCH, CryptoOutcome8535.classify(0.1))
        assertEquals(CryptoOutcome8535.Kind.SCRATCH, CryptoOutcome8535.classify(-0.1))
        assertEquals(CryptoOutcome8535.Kind.UNKNOWN, CryptoOutcome8535.classify(Double.NaN))
        assertFalse(CryptoOutcome8535.trainable(CryptoOutcome8535.Kind.SCRATCH))
        val settled0 = CryptoFluidLearning.tradeCount(); val w0 = CryptoFluidLearning.winCount(); val l0 = CryptoFluidLearning.lossCount()
        val opens0 = CryptoFluidLearning.openCount8535(); val scratches0 = CryptoFluidLearning.scratchCount8535()
        repeat(100) { CryptoFluidLearning.recordTradeStart() }
        assertEquals("100 opens settle nothing", settled0, CryptoFluidLearning.tradeCount())
        assertEquals(opens0 + 100, CryptoFluidLearning.openCount8535())
        repeat(10) { CryptoFluidLearning.recordScratch(true, 0.2) }
        assertEquals(settled0 + 10, CryptoFluidLearning.tradeCount())
        assertEquals("scratches are settled but neither won nor lost", scratches0 + 10, CryptoFluidLearning.scratchCount8535())
        assertEquals(w0, CryptoFluidLearning.winCount()); assertEquals(l0, CryptoFluidLearning.lossCount())
        CryptoFluidLearning.recordLiveTrade(true); CryptoFluidLearning.recordPaperTrade(false, -3.0)
        assertEquals(settled0 + 12, CryptoFluidLearning.tradeCount())
        assertEquals(w0 + 1, CryptoFluidLearning.winCount()); assertEquals(l0 + 1, CryptoFluidLearning.lossCount())
    }
}
