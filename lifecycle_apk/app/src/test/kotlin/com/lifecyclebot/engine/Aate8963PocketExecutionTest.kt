package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ChainPrice8963
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8963 — the proven pockets' coins reach execution: a chain read that is a price writes it (PRICE_STALE defers meet
 * the chain's answer), and a causal REVALIDATE is re-decided on the next pass instead of locking (mint, lane) for 15 s.
 */
class Aate8963PocketExecutionTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aChainReadWritesItsPriceOnlyWhenNewer() {
        assertTrue(ChainPrice8963.newer8963(heldAtMs = 1_000L, observedAtMs = 2_000L))
        assertFalse(ChainPrice8963.newer8963(heldAtMs = 2_000L, observedAtMs = 2_000L))
        assertFalse(ChainPrice8963.newer8963(heldAtMs = 0L, observedAtMs = 0L))
        assertTrue("an empty price is stale", ChainPrice8963.wasStale8963(0L, 100_000L))
        assertTrue(ChainPrice8963.wasStale8963(80_000L, 100_000L))
        assertFalse(ChainPrice8963.wasStale8963(90_000L, 100_000L))
        val p = src("engine/truth/ChainPrice8963.kt")
        assertTrue(p.contains("TokenFacts.writePrice(ts, usd, source, TokenFacts.Rank.CHAIN, observedAtMs, nowMs, pool = pool)"))
        assertTrue(p.contains("CandidateAdmission8136.evidenceArrived8252(mint, setOf(\"price\"), nowMs)"))
    }

    @Test fun theCurveAndVaultReadsPublishBeforeTheirRerun() {
        val curve = src("engine/truth/CurveReserves7951.kt")
        assertTrue(curve.indexOf("priceFromCurve8963(m, vSol, vTok, atMs)") in 0 until curve.indexOf("ChainConfirm8950.marketObserved8959(m, atMs)"))
        assertTrue(curve.contains("ChainPrice8963.publish8963(mint, priceSol7951(vSol, vTok) * solUsd, ChainPrice8963.CURVE_SOURCE_8963, atMs)"))
        val depth = src("engine/truth/OnChainDepth8038.kt").substringAfter("internal fun accept8418(").substringBefore("private fun recordStatePrice8418")
        val publish = depth.indexOf("ChainPrice8963.publish8963(mint, priceUsd8038(read.quoteSol, read.baseTokens, solUsd()),")
        assertTrue(publish > depth.indexOf("reads[mint] = read"))
        assertTrue("the rerun reads the new price", publish < depth.lastIndexOf("ChainConfirm8950.marketObserved8959(mint, observedAt)"))
        assertTrue("a concentrated vault ratio is depth: never a price",
            publish > depth.indexOf("return Accept8418.CONCENTRATED"))
        assertTrue(depth.contains("if (owner8418 == PUMP_AMM && !com.lifecyclebot.network.PoolVirtualQuote8952.known8952(p)) {"))
    }

    @Test fun aCausalRevalidationComesBackOnTheNextPass() {
        val gate = src("engine/ExecutableOpenGate.kt")
        assertTrue(gate.contains("r.contains(\"_REVALIDATE_6715\") -> 0L"))
        assertTrue(gate.indexOf("r.contains(\"_REVALIDATE_6715\") -> 0L") < gate.indexOf("log.contains(\"FDG\") -> 30_000L"))
        assertTrue(src("engine/truth/CausalFeedbackAuthority6715.kt").contains("CAUSAL_LANE_DRIFT_8963_"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("ChainPrice8963.statusLine8963()"))
    }
}
