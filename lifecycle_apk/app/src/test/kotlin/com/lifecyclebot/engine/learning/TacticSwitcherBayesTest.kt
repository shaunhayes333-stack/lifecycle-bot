package com.lifecyclebot.engine.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.9.1563 — pins fast Bayesian tactic rotation. */
class TacticSwitcherBayesTest {

    @Test
    fun posterior_probability_trips_on_obvious_losing_tactic() {
        val p = TacticSwitcher.posteriorLossProbAboveForTest(losses = 8, wins = 0, threshold = 0.70)
        assertTrue("8 straight losses should imply >85% probability loss-rate is >70%; got $p", p > 0.85)
    }

    @Test
    fun eight_bad_closes_rotate_before_full_trial_window() {
        val lane = "TEST_BAYES_${System.nanoTime()}".take(22)
        val band = "S41-60"
        assertEquals(TacticSwitcher.Tactic.MOMENTUM, TacticSwitcher.currentTactic(lane, band))

        repeat(8) {
            TacticSwitcher.onTradeClosed(lane, band, pnlPct = -6.0)
        }

        assertEquals(
            "Bayesian early-stop should rotate MOMENTUM after 8 decisive bad closes",
            TacticSwitcher.Tactic.PULLBACK,
            TacticSwitcher.currentTactic(lane, band),
        )
    }

    @Test
    fun mixed_small_sample_does_not_rotate() {
        val lane = "TEST_MIXED_${System.nanoTime()}".take(22)
        val band = "S41-60"
        repeat(8) { i ->
            TacticSwitcher.onTradeClosed(lane, band, pnlPct = if (i % 2 == 0) -4.0 else 3.0)
        }
        assertEquals(TacticSwitcher.Tactic.MOMENTUM, TacticSwitcher.currentTactic(lane, band))
    }

    @Test
    fun memory_sweep_bayesian_path_is_present_for_existing_bad_buckets() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(source.contains("mem-bayes"))
        assertTrue(source.contains("totalSamples < BAYES_MIN_SAMPLES"))
    }
}

