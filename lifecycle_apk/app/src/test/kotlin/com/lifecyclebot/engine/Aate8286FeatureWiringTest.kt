package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FeatureRevision8283
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8286 — tape prints and curve ticks advance the feature revision, throttled per mint + kind. */
class Aate8286FeatureWiringTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun throttleFiresAtMostEveryTwoSeconds() {
        assertTrue(FeatureRevision8283.throttleDue8286(null, 10_000L))
        assertFalse(FeatureRevision8283.throttleDue8286(10_000L, 11_999L))
        assertTrue(FeatureRevision8283.throttleDue8286(10_000L, 12_000L))
    }

    @Test fun everyProviderArrivalIsWired() {
        val tape = src("engine/truth/TradeTape8100.kt")
        assertTrue(tape.contains("FeatureRevision8283.bumpThrottled8286(mint, FeatureKind8283.TAPE)"))
        assertTrue(tape.contains("FeatureRevision8283.bumpThrottled8286(mint, FeatureKind8283.PRICE)"))
        assertTrue(src("network/CurveTicks7968.kt").contains("FeatureRevision8283.bumpThrottled8286(mint, com.lifecyclebot.engine.truth.FeatureKind8283.LIQUIDITY)"))
        // Helius candles reach the revision through evidenceArrived8252; DexScreener rows through PairFlow8169.
        assertTrue(src("engine/truth/HeliusSwapCandles7819.kt").contains("CandidateAdmission8136.evidenceArrived8252(ts.mint, setOf(\"shape\"), nowMs)"))
    }
}
