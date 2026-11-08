package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8640 §LIVE_NEEDS_THE_CURVE — owner: "its literally looking for the wrong tokens at the wrong time". The 8537 edge
 * export: of 3,547 admits, 1,124 (32%) were decided with the entry shape unobserved and returned a median -11.9% vs -4.1%
 * when it was observed; 820 were structurally at the high (pxPeak>=0.97, run-up>=5%) yet admitted — the 8545 / 8582 high
 * guards cannot refuse a peak they cannot see. A LIVE buy now defers until the coin's curve position (shape) is observed.
 * Paper / shadow keep entering blind and grading, so the teaching book is untouched. Shape a coin is too young to have is
 * historyUnavailable8283, never missingInputs, so fresh-launch snipes on launch evidence are unaffected.
 */
class Aate8640LiveCurveFreshnessTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun candidate(live: Boolean = true, missing: List<String> = emptyList()) = CandidateAdmission8136.Candidate8136(
        mint = "curve8640", lane = "MOONSHOT", selectedSetup = null, live = live, stage = "CONTROLLED_MARKUP",
        priceAgeMs = 10L, expectedGrossPct = 30.0, costPct = 4.5, genericScore = 70.0, genericFloor = 15.0,
        nativeQualified8213 = true, nativeSetup8213 = "BREAKOUT_RUNNER", liveEdge8114 = "CHART_BUY",
        missingInputs = missing,
    )

    @Test fun aLiveBuyWithUnobservedShapeDefersInsteadOfBuyingBlind() {
        val d = CandidateAdmission8136.decide8136(candidate(live = true, missing = listOf("shape:UNKNOWN")))
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA, d.terminal)
        assertEquals("DATA_INCOMPLETE_DEFER_8136", d.reason)
        assertTrue("the defer names the unobserved curve as a critical wait",
            d.trace.any { it.startsWith("criticalUnobserved8170=") && it.contains("shape") })
    }

    @Test fun theShapeDeferPreemptsTheAtHighRefusal() {
        // The curve-freshness defer sits before the 8545 high guard: a blind buy cannot even be judged "into the high".
        val d = CandidateAdmission8136.decide8136(candidate(live = true, missing = listOf("shape:UNKNOWN")))
        assertNotEquals(CandidateAdmission8136.LIVE_INTO_HIGH_UNPROVEN_8545, d.reason)
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue("shape criticality is added before the data-incomplete defer returns",
            adm.indexOf("liveCurveUnobserved8640") < adm.indexOf("return done(Terminal8136.DEFER_DATA, \"DATA_INCOMPLETE_DEFER_8136\""))
    }

    @Test fun paperKeepsEnteringAndGradingBlindCoins() {
        // Paper keeps the 8531 optional treatment: shape stays advisory so the teaching book never stops exploring.
        val d = CandidateAdmission8136.decide8136(candidate(live = false, missing = listOf("shape:UNKNOWN")))
        assertNotEquals("paper does not defer on an unobserved curve", CandidateAdmission8136.Terminal8136.DEFER_DATA, d.terminal)
    }

    @Test fun aLiveBuyThatCanSeeTheCurveIsUnaffected() {
        // With shape observed (no shape in missingInputs), the candidate reaches the existing high guard exactly as before.
        val d = CandidateAdmission8136.decide8136(candidate(live = true, missing = emptyList()))
        assertNotEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA, d.terminal)
    }

    @Test fun onlyShapeIsLiveCritical_notEveryOptionalInput() {
        assertTrue(CandidateAdmission8136.liveCurveUnobserved8640("shape:UNKNOWN"))
        assertTrue(CandidateAdmission8136.liveCurveUnobserved8640("SHAPE:DEFAULT_UNOBSERVED"))
        for (m in listOf("bp:UNKNOWN", "chg:UNKNOWN", "volAccel:UNKNOWN", "top:UNKNOWN", "context:UNKNOWN"))
            assertTrue("$m stays optional for live", !CandidateAdmission8136.liveCurveUnobserved8640(m))
    }
}
