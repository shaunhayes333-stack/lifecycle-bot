package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals

/**
 * V5.0.6611 — Bayesian learning from trade one (operator directive Feb 2026):
 *   "Use Bayesian/regularized adaptation: N=1 gives small influence. N=2
 *   gives slightly more. Influence rises continuously with evidence quality
 *   and sample size. But soft changes begin after the first terminal trade."
 *
 * Prior behaviour: LanePolicy.rollingWr / rollingWrForBucket returned null
 * until OUTCOME_WINDOW_MIN_SAMPLES=12 samples accumulated. Every learned
 * signal was silent for the first ~5-8 minutes of trading each cold
 * session — during which the classic 60-loss streak formed.
 *
 * V5.0.6611 §BAYESIAN_LEARNING_FROM_TRADE_ONE:
 *   Added posteriorWr6611(lane) + posteriorWrForBucket6611(lane, band)
 *   using a regularised Beta-Bernoulli posterior with Beta(2,2) prior:
 *     posterior = (w + 2) / (n + 4)
 *   Yields:
 *     n=0            -> 0.500 (neutral, no cap applied)
 *     n=1 W          -> 0.600 (mild bullish nudge)
 *     n=1 L          -> 0.400 (mild bearish nudge)
 *     n=5 W=0 L=5    -> 0.222 (clear bearish -> caps at 0.35)
 *     n=12 all L     -> 0.125 (matches the DEMOTE_WR 0.18 gate)
 *
 *   bleedExecutionCap now consults the posterior when the strict window
 *   sample size hasn't been reached — but only fires the cap when at
 *   least ONE real terminal outcome exists AND the posterior is
 *   meaningfully bearish (< 0.35). Neutral posterior (0.50 with n=0)
 *   never applies a cap so cold sessions still get their full allocated
 *   discovery breadth.
 */
class Aate6611BayesianLearningFromTradeOneCoverageTest {

    @Test
    fun aate6611_posterior_wr_reflects_beta22_prior() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/learning/LanePolicy.kt"
        ).readText()
        assertTrue(
            "V5.0.6611: LanePolicy must expose posteriorWr6611(lane): Double + posteriorWrForBucket6611(lane, band): Double + evidenceSamples6611(lane): Int",
            src.contains("fun posteriorWr6611(lane: String): Double") &&
                src.contains("fun posteriorWrForBucket6611(lane: String, scoreBand: String): Double") &&
                src.contains("fun evidenceSamples6611(lane: String): Int")
        )
        assertTrue(
            "V5.0.6611: BETA_PRIOR_6611 must equal 2.0 (weak WR~50% prior)",
            src.contains("BETA_PRIOR_6611: Double = 2.0")
        )
        assertTrue(
            "V5.0.6611: posterior formula must be (w + alpha) / (n + 2*alpha) so N=0 returns 0.5 exactly",
            src.contains("(w + BETA_PRIOR_6611) / (n + BETA_PRIOR_6611 * 2.0)")
        )
    }

    @Test
    fun aate6611_bleed_cap_consults_posterior_when_strict_window_short() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/learning/LanePolicy.kt"
        ).readText()
        assertTrue(
            "V5.0.6611: bleedExecutionCap must fall back to the posterior when both rollingWr and rollingWrForBucket return null",
            src.contains("§BAYESIAN_FROM_TRADE_ONE") &&
                src.contains("posteriorWr6611(lane)") &&
                src.contains("posteriorWrForBucket6611(lane, scoreBand)")
        )
        assertTrue(
            "V5.0.6611: posterior-driven cap must require at least one real terminal outcome (evidenceSamples >= 1)",
            src.contains("evidenceN6611 = evidenceSamples6611(lane)") &&
                src.contains("evidenceN6611 >= 1 && minPost6611 < 0.35")
        )
        assertTrue(
            "V5.0.6611: posterior-driven cap must emit LANE_BLEED_EXECUTION_CAP_POSTERIOR_6611 for operator grep",
            src.contains("LANE_BLEED_EXECUTION_CAP_POSTERIOR_6611_")
        )
        // Soft cap tier for early bearish posterior (0.25..0.35 -> 0.55x).
        assertTrue(
            "V5.0.6611: soft cap tier for 0.25..0.35 posterior must exist",
            src.contains("wr < 0.35 -> 0.55")
        )
    }
}
