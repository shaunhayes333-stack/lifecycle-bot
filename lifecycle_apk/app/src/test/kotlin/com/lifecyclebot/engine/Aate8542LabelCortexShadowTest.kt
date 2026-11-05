package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexTailEv8324
import com.lifecyclebot.engine.cortex.CortexVsStack8539
import com.lifecyclebot.engine.truth.LabelEvidence8386
import com.lifecyclebot.engine.truth.LabelMark8386
import com.lifecyclebot.engine.truth.ShadowMark8360
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8542 — directive P0.1 / P0.2 / P0.3 / P0.8 on the 8539 log:
 *  - a known entry with no priced outcome closes as NO_OUTCOME_MARK, not UNKNOWN_BASIS (328 mis-filed);
 *  - only prediction observations are paired with a Cortex prediction in the checkpoint (1,495 "original unavailable"
 *    were gate / plan / missed-lane observations that never had one);
 *  - the Cortex is compared with the stack only when its outcomes are >= 70 % known (missing-outcome bias);
 *  - a ranking that orders outcomes the right way reads RANKS, the reverse INVERTED (no global negation anywhere);
 *  - a shadow coin off the watchlist is priced from its own pool on chain (1,119 clock exits waited for a mark).
 */
class Aate8542LabelCortexShadowTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun mark(usd: Double, at: Long, src: String = "DEX") = LabelMark8386("M", "v", usd, at, at + 1, src)

    // 1. missing historical labels / missing-outcome classification
    @Test fun aMissingOutcomeIsNotAnUnknownBasis() {
        assertEquals("OBSERVED_ONLY_NO_OUTCOME_MARK", LabelEvidence8386.status(mark(1.0, 1_000), null))
        assertEquals("OBSERVED_ONLY_UNKNOWN_BASIS", LabelEvidence8386.status(null, mark(1.0, 60_000)))
        assertEquals("OBSERVED_ONLY_UNKNOWN_BASIS", LabelEvidence8386.status(LabelMark8386("M", "", 1.0, 0L, 1L, "UNKNOWN"), mark(1.0, 60_000)))
        assertEquals("VALID_8386", LabelEvidence8386.status(mark(1.0, 1_000), mark(1.2, 60_000)))
        assertEquals("DEADLINE_NO_OUTCOME_MARK", Cortex7885.lostKey8441("OBSERVATION_DEADLINE_OBSERVED_ONLY_NO_OUTCOME_MARK"))
        assertFalse("never booked as a return", LabelEvidence8386.eligible(mark(1.0, 1_000), null))
    }

    // 13. restart / persistence: the checkpoint pairs only episodes that can own a Cortex prediction
    @Test fun onlyPredictionObservationsArePairedInTheCheckpoint() {
        assertTrue(NativeDecisionEnrollment8372.predictionObservation8404("CORTEX_SHADOW_STRONG"))
        assertTrue(NativeDecisionEnrollment8372.predictionObservation8404("NATIVE_READY"))
        assertFalse(NativeDecisionEnrollment8372.predictionObservation8404("CANDIDATE_ADMISSION_8136_ENTRY_NO_RECLAIM_8506"))
        assertFalse(NativeDecisionEnrollment8372.predictionObservation8404(null))
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        val cp = l.substringAfter("val paired = try { com.lifecyclebot.engine.cortex.Cortex7885.checkpointForward8401(").substringBefore(".commit()")
        assertTrue(cp.contains("NativeDecisionEnrollment8372.predictionObservation8404(it.reason7967)"))
    }

    // 3 / 4. polarity, monotonicity and missing-outcome sensitivity
    @Test fun cortexIsComparedOnlyOnMostlyKnownOutcomes() {
        assertEquals(0.7, CortexVsStack8539.MIN_COVERAGE_8541, 0.0)
        assertEquals("8539 TREASURY rest coverage 0.54", "CORTEX_INCOMPLETE_OUTCOMES", CortexVsStack8539.code(60, 6.0, 1.0, 300, -5.0, 1.0, 0.54))
        assertEquals(CortexVsStack8539.PROVEN, CortexVsStack8539.code(60, 6.0, 1.0, 300, -5.0, 1.0, 0.86))
        assertEquals("unknown coverage proves nothing", "CORTEX_INCOMPLETE_OUTCOMES", CortexVsStack8539.code(60, 6.0, 1.0, 300, -5.0, 1.0, Double.NaN))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("stack?.stderr60Pct ?: Double.NaN, minOf(r.strongCoverage8392, r.restCoverage8392))"))
    }

    @Test fun rankingIsMonotoneAndTheVerdictFollowsTheMeasuredDirection() {
        val pred = (1..50).map { it.toDouble() }
        val up = pred.map { it * 0.5 - 10.0 }
        val down = up.reversed()
        assertTrue(CortexTailEv8324.rankIc8324(pred, up) > 0.9)
        assertTrue(CortexTailEv8324.rankIc8324(pred, down) < -0.9)
        val qUp = CortexTailEv8324.quintiles8324(pred, up); val qDown = CortexTailEv8324.quintiles8324(pred, down)
        assertTrue("top fifth above bottom", qUp[0] > qUp[1])
        assertEquals("RANKS", CortexTailEv8324.verdict8324(CortexTailEv8324.rankIc8324(pred, up), qUp[0], qUp[1]))
        assertEquals("INVERTED", CortexTailEv8324.verdict8324(CortexTailEv8324.rankIc8324(pred, down), qDown[0], qDown[1]))
        // an inversion must be proven beyond noise before any power is revoked: a 2-point gap on thin, noisy cohorts is not
        val strong = CortexLedger7885.labelStat8187(); val neutral = CortexLedger7885.labelStat8187()
        listOf(-30.0, 20.0, -25.0, 15.0, -40.0, 30.0, -20.0, 10.0, -35.0, 25.0, -15.0, 5.0).forEach { strong.add(it, false) }
        listOf(-25.0, 22.0, -20.0, 17.0, -35.0, 32.0).forEach { neutral.add(it, false) }
        assertFalse(CortexScoreboard7885.invertedProven7948(strong, neutral))
    }

    // 12. shadow mark-wait completion: the pool on chain is a mark source, and a missing mark asks for one
    @Test fun shadowMarksReadTheCoinsOwnPool() {
        assertEquals("POOL", ShadowMark8360.SRC_POOL_8541)
        val s = src("engine/truth/ShadowMark8360.kt").substringAfter("fun observedTimed8372(").substringBefore("fun pick8360(")
        assertTrue(s.contains("OnChainDepth8038.freshRead8198(mint, MAX_AGE_MS_8360, nowMs)"))
        assertTrue(s.contains("OnChainDepth8038.requestMint8540(mint)"))
        assertNull("nothing observed is no mark", ShadowMark8360.observedTimed8372(null, "not-a-mint", 1_000_000L))
        val d = src("engine/truth/OnChainDepth8038.kt")
        assertTrue("a pool-less mint is not looked up every loop", d.contains("if (noPoolAt8541[m]?.let { now8541 - it < NO_POOL_RETRY_MS_8541 } == true)"))
    }
}
