package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Trade One regression witnesses: real stops remain executable, fresh coins
 * are not declared flat from two candles, and hydration cannot forge recency.
 */
class Aate8037TradeOneEvidenceTest {
    @Test fun aHardStopNeverBecomesAProfitFloorHold() {
        assertTrue(NetEdge8030.structural8030("HARD_STOP"))
        assertTrue(NetEdge8030.structural8030("UNIVERSAL_SL"))
        assertTrue(NetEdge8030.structural8030("RAPID_STOP"))
        assertFalse(NetEdge8030.profitExitHeld8030(
            pnlPct = 3.0,
            floorPct = 6.0,
            structural = NetEdge8030.structural8030("HARD_STOP"),
        ))
        assertFalse(NetEdge8030.structural8030("PROFIT_LOCK_+2"))
    }

    @Test fun flatlineNeedsFiveDistinctObservedCandles() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/FlatChart8027.kt").readText()
        assertTrue(source.contains("recentBars8027.size >= FLAT_MIN_BARS"))
        assertTrue(FlatChart8027.FLAT_MIN_BARS == 5)
        assertTrue(FlatChart8027.flatline8027(List(5) { 0.0000044 }))
        assertFalse(FlatChart8027.flatline8027(List(4) { 0.0000044 }))
    }

    @Test fun theRiskClockWitnessCountsOnlyArmedThresholdComparisons() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/ProtectiveExitScheduler6450.kt").readText()
        val armed = source.indexOf("armed.incrementAndGet()")
        val witness = source.indexOf("ExecutionSpineAcceptanceWindow6647.onExitEvaluation()", armed)
        val checks = source.indexOf("val kind: TriggerKind?", armed)
        assertTrue(armed >= 0 && witness > armed && checks > witness)
        assertTrue(source.indexOf("if (!markPx.isFinite()", 0) < armed)
        assertTrue(source.indexOf("noThreshold.incrementAndGet()", 0) < armed)
    }

    @Test fun archivedPreProofSupplyCannotCertifyLiveIdentity() {
        val metrics = File("src/main/kotlin/com/lifecyclebot/engine/truth/TokenMetricsAuthority7069.kt").readText()
        val method = metrics.substringAfter("private fun storedSupplyOf(").substringBefore("fun acceptOnChainSupply7075")
        assertTrue(method.contains("OnChainSupplyAuthority7075.supplyOf7075(mint)"))
        assertTrue(method.contains("OnChainSupplyAuthority7075.requestAsync7075(mint)"))
        assertFalse(method.contains("return durable"))
        val cache = File("src/main/kotlin/com/lifecyclebot/engine/TokenMetaCache.kt").readText()
        val upsert = cache.substringAfter("fun upsertSupply7069(").substringBefore("fun supplyOf7069(")
        assertTrue(upsert.contains("if (ratio in 0.995..1.005) return"))
        assertTrue(upsert.indexOf("e.supplyTokens = supplyTokens") > upsert.indexOf("if (ratio in 0.995..1.005) return"))
    }

    @Test fun localHydrationNeverClaimsNewProviderObservation() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/RouteTruthHydrator.kt").readText()
        assertFalse(source.contains("tm.providerTimestamps[src] = now"))
        assertTrue(source.contains("Real fetchers own providerTimestamps"))
    }
}
